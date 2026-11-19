package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.dto.RequestBoardDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.RequestAttachment;
import com.hamonsoft.netismaker.entity.RequestComment;
import com.hamonsoft.netismaker.entity.RequestCommentKind;
import com.hamonsoft.netismaker.entity.RequestIssueType;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestPost;
import com.hamonsoft.netismaker.entity.RequestPriority;
import com.hamonsoft.netismaker.entity.RequestStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.RequestAttachmentRepository;
import com.hamonsoft.netismaker.repository.RequestCommentRepository;
import com.hamonsoft.netismaker.repository.RequestPostRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.util.RepoUrlParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 요청 게시판 서비스 규칙 (스펙 2026-10-19). 남의 요청은 존재 자체를 숨겨 404로 답한다.
 */
@Service
@Profile("api")
public class RequestBoardService {

    private static final String NOT_FOUND_MESSAGE = "요청을 찾을 수 없습니다";
    private static final int LIST_LIMIT = 200;
    private static final int MAX_ALIAS_LENGTH = 100;
    private static final Set<RequestStatus> OPEN_STATUSES = Set.of(RequestStatus.RECEIVED, RequestStatus.REVIEWING);

    private final RequestPostRepository posts;
    private final RequestCommentRepository comments;
    private final RequestAttachmentRepository attachments;
    private final RepoCatalogRepository catalog;
    private final RepoCatalogService repoCatalogService;
    private final TaskRepository tasks;
    private final AttachmentStorage storage;
    private final int maxOpenPerUser;
    private final String gitlabBaseUrl;

    public RequestBoardService(RequestPostRepository posts,
                               RequestCommentRepository comments,
                               RequestAttachmentRepository attachments,
                               RepoCatalogRepository catalog,
                               RepoCatalogService repoCatalogService,
                               TaskRepository tasks,
                               AttachmentStorage storage,
                               @Value("${app.request-board.max-open-per-user:20}") int maxOpenPerUser,
                               @Value("${app.gitlab.base-url:}") String gitlabBaseUrl) {
        this.posts = posts;
        this.comments = comments;
        this.attachments = attachments;
        this.catalog = catalog;
        this.repoCatalogService = repoCatalogService;
        this.tasks = tasks;
        this.storage = storage;
        this.maxOpenPerUser = maxOpenPerUser;
        this.gitlabBaseUrl = gitlabBaseUrl;
    }

    @Transactional
    public RequestBoardDto.ListItem create(RequestBoardDto.CreateRequest req, String userId) {
        return create(req, List.of(), userId);
    }

    @Transactional
    public RequestBoardDto.ListItem create(RequestBoardDto.CreateRequest req, List<MultipartFile> files,
                                           String userId) {
        List<MultipartFile> attached = files == null ? List.of() : files;
        if (req.kind() == null || !req.kind().creatable()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "더 이상 받지 않는 요청 종류입니다");
        }
        if (!attached.isEmpty() && req.kind() != RequestKind.DEV) throw attachmentsOnlyForDev();
        if (posts.countByRequesterIdAndStatusIn(userId, OPEN_STATUSES) >= maxOpenPerUser) {
            throw new TaskException(HttpStatus.TOO_MANY_REQUESTS,
                    "처리 중인 요청이 너무 많습니다(최대 " + maxOpenPerUser + "건)");
        }
        RequestPost post;
        if (req.kind() == RequestKind.DEV) {
            if (req.issueType() == null) throw new TaskException(HttpStatus.BAD_REQUEST, "유형을 선택하세요");
            checkTargetRepo(req.targetRepoCatalogId());
            post = RequestPost.createDev(req.title(), req.content(), req.issueType(),
                    req.priority() == null ? RequestPriority.NORMAL : req.priority(),
                    req.dueDate(), req.targetRepoCatalogId(), userId);
        } else {
            post = RequestPost.create(req.kind(), req.title(), req.content(), req.repoHint(), userId);
        }
        storage.validate(attached, 0);
        RequestPost saved = posts.save(post);
        writeAttachments(saved.getId(), 1, attached, new ArrayList<>());
        return toListItems(List.of(saved), p -> false).get(0);
    }

    private static TaskException attachmentsOnlyForDev() {
        return new TaskException(HttpStatus.BAD_REQUEST, "첨부는 개발 요청에만 올릴 수 있습니다");
    }

    /**
     * 파일을 디스크에 쓰고 메타 행을 남긴다(순번은 firstOrdinal부터). 쓰기보다 먼저 정리 목록에 넣어 반쯤 쓰인
     * 파일도 지운다 — 실패하면 쓴 파일을 지우고 예외를 다시 던져 트랜잭션을 롤백한다(TaskService.create와 같은 규칙).
     */
    private void writeAttachments(Long requestId, int firstOrdinal, List<MultipartFile> files, List<String> written) {
        try {
            int ordinal = firstOrdinal;
            for (MultipartFile f : files) {
                String name = f.getOriginalFilename();
                String rel = storage.relativePathForRequest(requestId, ordinal, name);
                written.add(rel);
                storage.write(rel, f);
                attachments.save(RequestAttachment.create(requestId, ordinal,
                        AttachmentStorage.sanitize(name), rel, f.getContentType(), f.getSize()));
                ordinal++;
            }
        } catch (RuntimeException e) {
            written.forEach(storage::deleteQuietly);
            throw e;
        }
    }

    /** 커밋 뒤에 실행한다(롤백이면 실행하지 않는다). 트랜잭션 밖에서 불렸으면 바로 실행한다. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 대상 레포가 있으면 카탈로그에서 확인한다(없는·비활성이면 그 메서드의 400). */
    private void checkTargetRepo(Long targetRepoCatalogId) {
        if (targetRepoCatalogId != null) repoCatalogService.resolveForRegistration(targetRepoCatalogId);
    }

    @Transactional(readOnly = true)
    public List<RequestBoardDto.ListItem> listMine(String userId) {
        return toListItems(posts.findByRequesterIdOrderByUpdatedAtDesc(userId, Limit.of(LIST_LIMIT)), p -> p.unreadFor(false));
    }

    /** 개발 요청 전원 공개 목록. statuses가 비면 전체 상태. unread는 작성자·관리자일 때만 그 기준, 그 밖은 false. */
    @Transactional(readOnly = true)
    public List<RequestBoardDto.ListItem> listDev(RequestIssueType issueType, Set<RequestStatus> statuses,
                                                  String viewerId, boolean viewerAdmin) {
        boolean anyStatus = statuses == null || statuses.isEmpty();
        // 빈 IN 절 방지: anyStatus면 자리표시로 전체 상태를 넘긴다.
        Set<RequestStatus> effective = anyStatus ? EnumSet.allOf(RequestStatus.class) : statuses;
        List<RequestPost> found = posts.findDev(RequestKind.DEV, issueType, effective, anyStatus,
                PageRequest.of(0, LIST_LIMIT));
        return toListItems(found, p -> viewerUnread(p, viewerId, viewerAdmin));
    }

    /** 3인자 호출(관리자 아님). */
    @Transactional(readOnly = true)
    public List<RequestBoardDto.ListItem> listDev(RequestIssueType issueType, Set<RequestStatus> statuses,
                                                  String viewerId) {
        return listDev(issueType, statuses, viewerId, false);
    }

    private static boolean viewerUnread(RequestPost p, String viewerId, boolean viewerAdmin) {
        if (p.isOwnedBy(viewerId)) return p.unreadFor(false);
        if (viewerAdmin) return p.unreadFor(true);
        return false;
    }

    @Transactional(readOnly = true)
    public List<RequestBoardDto.ListItem> listForAdmin(RequestKind kind, Set<RequestStatus> statuses,
                                                       boolean includeUnread) {
        // 빈 IN 절은 질의가 깨지거나 아무것도 못 찾으므로 기본값(열린 상태)으로 대체한다.
        Set<RequestStatus> effective = (statuses == null || statuses.isEmpty()) ? OPEN_STATUSES : statuses;
        return toListItems(posts.findForAdmin(kind, effective, includeUnread, PageRequest.of(0, LIST_LIMIT)),
                p -> p.unreadFor(true));
    }

    @Transactional
    public RequestBoardDto.Detail getAndMarkRead(Long id, String viewerId, boolean admin) {
        RequestPost post = findVisible(id, viewerId, admin);
        // 읽음은 작성자·관리자만 갱신한다. 공개된 개발 요청을 보는 제3자는 아무 시각도 바꾸지 않는다.
        post.markRead(post.isOwnedBy(viewerId), admin, OffsetDateTime.now());
        return toDetail(post, viewerId, admin);
    }

    @Transactional
    public RequestBoardDto.Detail update(Long id, RequestBoardDto.UpdateRequest req, String userId) {
        return update(id, req, List.of(), userId, false);
    }

    /** 호출자가 관리자가 아닌 것으로 보고 수정한다(응답의 convertible은 false). */
    @Transactional
    public RequestBoardDto.Detail update(Long id, RequestBoardDto.UpdateRequest req, List<MultipartFile> files,
                                         String userId) {
        return update(id, req, files, userId, false);
    }

    /** callerAdmin은 응답 Detail의 관리자 전용 표시(convertible)에만 쓴다 — 수정 권한은 늘 작성자 본인. */
    @Transactional
    public RequestBoardDto.Detail update(Long id, RequestBoardDto.UpdateRequest req, List<MultipartFile> files,
                                         String userId, boolean callerAdmin) {
        RequestPost post = findOwnedForUpdate(id, userId);
        if (post.getStatus() != RequestStatus.RECEIVED) {
            throw TaskException.conflict("접수 상태에서만 수정할 수 있습니다");
        }
        boolean dev = post.getKind() == RequestKind.DEV;
        List<MultipartFile> attached = files == null ? List.of() : files;
        if (!attached.isEmpty() && !dev) throw attachmentsOnlyForDev();
        if (dev && req.issueType() == null) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "유형을 선택하세요");
        }
        if (dev) checkTargetRepo(req.targetRepoCatalogId());
        // 첨부 변경은 디스크에 닿기 전에 모두 검증한다: 지울 id는 이 요청의 것이어야 하고, 한도는 (남는 수 + 새 수).
        List<RequestAttachment> current = attachments.findByRequestIdOrderByOrdinalAsc(id);
        List<RequestAttachment> removed = new ArrayList<>();
        if (req.removeAttachmentIds() != null) {
            for (Long attId : new LinkedHashSet<>(req.removeAttachmentIds())) {
                RequestAttachment found = current.stream().filter(a -> a.getId().equals(attId)).findFirst()
                        .orElseThrow(() -> new TaskException(HttpStatus.BAD_REQUEST,
                                "삭제할 첨부를 찾을 수 없습니다: " + attId));
                removed.add(found);
            }
        }
        storage.validate(attached, current.size() - removed.size());
        int nextOrdinal = current.stream().mapToInt(RequestAttachment::getOrdinal).max().orElse(0) + 1;
        post.setTitle(req.title());
        post.setContent(req.content());
        post.setRepoHint(post.getKind() == RequestKind.REPO ? req.repoHint() : null);
        if (dev) {
            post.setIssueType(req.issueType());
            post.setPriority(req.priority() == null ? RequestPriority.NORMAL : req.priority());
            post.setDueDate(req.dueDate());
            post.setTargetRepoCatalogId(req.targetRepoCatalogId());
        }
        removed.forEach(attachments::delete);
        writeAttachments(id, nextOrdinal, attached, new ArrayList<>());
        // 지운 행의 파일은 커밋 뒤에 지운다(롤백되면 행이 살아 있으므로 파일도 남겨야 한다).
        List<String> removedPaths = removed.stream().map(RequestAttachment::getStoredPath).toList();
        afterCommit(() -> removedPaths.forEach(storage::deleteQuietly));
        post.touchRequester(OffsetDateTime.now());
        return toDetail(post, userId, callerAdmin);
    }

    @Transactional
    public void cancel(Long id, String userId) {
        RequestPost post = findOwnedForUpdate(id, userId);
        // 연결 검사가 상태 검사보다 먼저 — 연결된 요청은 보통 처리완료라 순서가 바뀌면 이 문구가 나오지 않는다.
        if (post.getTaskId() != null) {
            throw TaskException.conflict("작업에 연결된 요청은 취소할 수 없습니다");
        }
        if (post.getStatus() != RequestStatus.RECEIVED) {
            throw TaskException.conflict("접수 상태에서만 취소할 수 있습니다");
        }
        if (post.getKind() == RequestKind.DEV) {
            if (comments.existsByRequestIdAndKindAndAuthorIdNot(id, RequestCommentKind.COMMENT, userId)) {
                throw TaskException.conflict("다른 사람의 답글이 있는 요청은 취소할 수 없습니다");
            }
        } else if (comments.existsByRequestIdAndKindAndAuthorAdminTrue(id, RequestCommentKind.COMMENT)) {
            throw TaskException.conflict("관리자 답글이 있는 요청은 취소할 수 없습니다");
        }
        posts.deleteById(id);
        afterCommit(() -> storage.deleteQuietly(storage.requestRootRelative(id)));
    }

    /** 첨부 내려받기 재료. */
    public record AttachmentDownload(Path path, String name, String mimeType) {}

    /**
     * 첨부 내려받기. 요청을 볼 수 있는 사람(findVisible)만 받는다 — 다른 종류의 남의 요청은 404.
     * 행은 있는데 파일이 없으면 410(정리됐거나 유실).
     */
    @Transactional(readOnly = true)
    public AttachmentDownload download(Long id, Long attId, String viewerId, boolean admin) {
        findVisible(id, viewerId, admin);
        RequestAttachment att = attachments.findByIdAndRequestId(attId, id)
                .orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, "첨부를 찾을 수 없습니다"));
        if (!storage.exists(att.getStoredPath())) {
            throw new TaskException(HttpStatus.GONE, "첨부 파일이 서버에 남아 있지 않습니다");
        }
        return new AttachmentDownload(storage.resolve(att.getStoredPath()), att.getOriginalName(), att.getMimeType());
    }

    @Transactional
    public RequestBoardDto.CommentView addComment(Long id, RequestBoardDto.CommentRequest req,
                                                  String userId, boolean admin) {
        RequestPost post = findVisible(id, userId, admin);
        RequestComment saved = comments.save(
                RequestComment.create(id, RequestCommentKind.COMMENT, userId, admin, req.body()));
        OffsetDateTime now = OffsetDateTime.now();
        if (admin) post.touchAdmin(now);
        // 관리자가 자기 요청에 단 답글은 요청자 활동이기도 하다.
        if (post.isOwnedBy(userId)) post.touchRequester(now);
        // 공개된 개발 요청에 제3자가 단 답글은 작성자와 관리자 모두에게 새 활동이다.
        if (!admin && !post.isOwnedBy(userId)) post.touchByOther(now);
        return toCommentView(saved);
    }

    @Transactional
    public RequestBoardDto.Detail changeStatus(Long id, RequestBoardDto.StatusChangeRequest req, String adminId) {
        RequestPost post = posts.findByIdForUpdate(id).orElseThrow(RequestBoardService::notFound);
        RequestStatus current = post.getStatus();
        RequestStatus next = req.status();
        if (current == next) {
            return toDetail(post, adminId, true);
        }
        if (!current.canTransitionTo(next)) {
            throw TaskException.conflict("바꿀 수 없는 상태입니다: " + current.dbValue() + " → " + next.dbValue());
        }
        String reason = req.reason() == null ? "" : req.reason().strip();
        if (next == RequestStatus.REJECTED && reason.isEmpty()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "반려 사유를 입력하세요");
        }
        // 되돌리기 가드 (스펙 2026-11-19 §5.4): 작업이 연결된 처리완료 요청은 연결 작업이 소프트 삭제(또는 행이 없음)됐을
        // 때만 다시 열 수 있고, 그때 연결을 끊는다.
        Long unlinkedTaskId = null;
        if (current == RequestStatus.DONE && post.getTaskId() != null) {
            Long linked = post.getTaskId();
            Task task = tasks.findById(linked).orElse(null);
            if (task != null && task.getDeletedAt() == null) {
                throw TaskException.conflict("작업 #" + linked + "에 연결된 요청은 되돌릴 수 없습니다");
            }
            unlinkedTaskId = linked;
        }
        String body = "관리자(" + adminId + ")가 상태를 " + next.dbValue() + withParticle(next.dbValue()) + " 변경";
        if (next == RequestStatus.REJECTED) body += " — 사유: " + reason;
        if (unlinkedTaskId != null) {
            body += " (작업 #" + unlinkedTaskId + " 연결 해제)";
            post.setTaskId(null);
        }
        post.setStatus(next);
        comments.save(RequestComment.create(id, RequestCommentKind.SYSTEM, adminId, true, body));
        post.touchAdmin(OffsetDateTime.now());
        return toDetail(post, adminId, true);
    }

    /** 작업 등록 재료 — 요청 id, 원 요청자, 첨부(순번 순). */
    public record RequestSource(Long id, String requesterId, List<RequestAttachment> attachments) {}

    /**
     * 개발 요청을 작업 등록용으로 잡는다 (스펙 2026-11-19 §5.4). 행 락(FOR UPDATE)을 잡고 검증하므로 같은 요청의 두 번째
     * 등록은 앞선 커밋을 기다렸다가 연결 검사에서 409로 거절된다. 호출자(TaskService.create)의 트랜잭션에 합류해야 한다 —
     * 락이 커밋까지 유지되고, 뒤에서 실패하면 {@link #linkTask}까지 함께 롤백된다.
     * 연결 검사를 상태 검사보다 먼저 한다 — 연결된 요청은 늘 처리완료라 순서가 바뀌면 연결 문구가 나오지 않는다.
     */
    @Transactional
    public RequestSource claimForTask(Long requestId, boolean callerIsAdmin) {
        if (!callerIsAdmin) throw new TaskException(HttpStatus.FORBIDDEN, "요청을 작업으로 등록할 권한이 없습니다");
        RequestPost post = posts.findByIdForUpdate(requestId).orElseThrow(RequestBoardService::notFound);
        if (post.getKind() != RequestKind.DEV) throw TaskException.conflict("개발 요청이 아닙니다");
        if (post.getTaskId() != null) {
            throw TaskException.conflict("이미 작업 #" + post.getTaskId() + "으로 등록된 요청입니다");
        }
        if (!post.getStatus().isOpen()) throw TaskException.conflict("이미 처리된 요청입니다");
        return new RequestSource(post.getId(), post.getRequesterId(),
                attachments.findByRequestIdOrderByOrdinalAsc(requestId));
    }

    /** 등록한 작업을 요청에 연결하고 처리완료로 닫는다(시스템 답글). claimForTask와 같은 트랜잭션에서 부른다. */
    @Transactional
    public void linkTask(Long requestId, Long taskId, String adminId) {
        RequestPost post = posts.findByIdForUpdate(requestId).orElseThrow(RequestBoardService::notFound);
        post.setTaskId(taskId);
        post.setStatus(RequestStatus.DONE);
        comments.save(RequestComment.create(requestId, RequestCommentKind.SYSTEM, adminId, true,
                "관리자(" + adminId + ")가 작업 #" + taskId + "으로 등록 — 처리완료"));
        post.touchAdmin(OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public RequestBoardDto.Summary summary() {
        return new RequestBoardDto.Summary(posts.countByStatus(RequestStatus.RECEIVED), posts.countAttention());
    }

    /**
     * 레포 등록 전에 GitLab 호출 없이 빠르게 실패시키는 확인용(락 없음).
     * 실제 완료 처리는 {@link #completeRepoRequest}가 행 락을 잡고 다시 검사한다.
     */
    @Transactional(readOnly = true)
    public void assertOpenRepoRequest(Long id) {
        requireOpenRepo(posts.findById(id).orElseThrow(RequestBoardService::notFound));
    }

    /** REPO 추가 요청을 카탈로그 등록(또는 기존 항목 연결)과 함께 처리완료로 닫는다. */
    @Transactional
    public RequestBoardDto.Detail completeRepoRequest(Long id, GitLabProjectClient.GitLabProject project,
                                                      String gitUrl, String adminId) {
        RequestPost post = posts.findByIdForUpdate(id).orElseThrow(RequestBoardService::notFound);
        requireOpenRepo(post);

        RepoUrlParser.Parsed parsed;
        try {
            parsed = RepoUrlParser.parse(gitUrl, gitlabBaseUrl);
        } catch (IllegalArgumentException ex) {
            throw new TaskException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        RepoCatalogEntry linked = catalog.findByGitUrl(parsed.canonicalUrl()).orElse(null);
        boolean existing = linked != null;
        RepoCatalogEntry entry = existing ? linked : registerNew(id, project, gitUrl, adminId);

        post.setRepoCatalogId(entry.getId());
        post.setRepoGitUrl(parsed.canonicalUrl());
        post.setStatus(RequestStatus.DONE);
        comments.save(RequestComment.create(id, RequestCommentKind.SYSTEM, adminId, true,
                "관리자(" + adminId + ")가 레포 카탈로그에 " + entry.getAlias()
                        + " (" + project.pathWithNamespace() + ") " + (existing ? "연결" : "등록") + " — 처리완료"));
        post.touchAdmin(OffsetDateTime.now());
        return toDetail(post, adminId, true);
    }

    private RepoCatalogEntry registerNew(Long requestId, GitLabProjectClient.GitLabProject project,
                                         String gitUrl, String adminId) {
        // repo_catalog.alias는 VARCHAR(100) — 넘치는 후보는 건너뛴다.
        String alias = null;
        boolean fitted = false;
        for (String candidate : new String[]{project.name(), project.pathWithNamespace()}) {
            if (candidate == null || candidate.isBlank() || candidate.length() > MAX_ALIAS_LENGTH) continue;
            fitted = true;
            if (catalog.findByAlias(candidate).isEmpty()) {
                alias = candidate;
                break;
            }
        }
        if (alias == null) {
            throw TaskException.conflict(fitted
                    ? "이미 존재하는 별칭 — 레포 카탈로그 화면에서 등록하세요"
                    : "별칭으로 쓸 수 있는 이름이 없습니다 — 레포 카탈로그 화면에서 등록하세요");
        }
        return repoCatalogService.create(new RepoCatalogDto.UpsertRequest(
                alias, gitUrl, project.defaultBranch(), "요청 #" + requestId + "에서 등록", true), adminId);
    }

    /** 방향격 조사: 받침이 있고 ㄹ이 아니면 "으로", 아니면 "로". 한글이 아니면 "로". */
    private static String withParticle(String label) {
        char last = label.charAt(label.length() - 1);
        if (last >= 0xAC00 && last <= 0xD7A3) {
            int jong = (last - 0xAC00) % 28;
            if (jong != 0 && jong != 8) return "으로";
        }
        return "로";
    }

    private static void requireOpenRepo(RequestPost post) {
        if (post.getKind() != RequestKind.REPO) {
            throw TaskException.conflict("사이트 추가 요청이 아닙니다");
        }
        if (!post.getStatus().isOpen()) {
            throw TaskException.conflict("이미 처리된 요청입니다");
        }
    }

    // ---- 조회·변환 ----

    private static TaskException notFound() {
        return new TaskException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }

    /** 상태를 확인하고 쓰는 경로는 행 락을 잡아 관리자 상태 변경과 직렬화한다(없거나 남의 것이면 같은 404). */
    private RequestPost findOwnedForUpdate(Long id, String userId) {
        RequestPost post = posts.findByIdForUpdate(id).orElseThrow(RequestBoardService::notFound);
        if (!post.isOwnedBy(userId)) throw notFound();
        return post;
    }

    private RequestPost findVisible(Long id, String viewerId, boolean admin) {
        RequestPost post = posts.findById(id).orElseThrow(RequestBoardService::notFound);
        // 전원 공개는 개발 요청(DEV)뿐이다. 그 밖의 종류는 본인·관리자만, 아니면 존재를 숨긴다.
        if (!post.getKind().publicToAll() && !admin && !post.isOwnedBy(viewerId)) throw notFound();
        return post;
    }

    /** 목록 변환. 연결 작업·대상 레포는 id 묶음으로 한 번에 조회한다. */
    private List<RequestBoardDto.ListItem> toListItems(List<RequestPost> list,
                                                       java.util.function.Predicate<RequestPost> unread) {
        Set<Long> taskIds = new HashSet<>();
        Set<Long> repoIds = new HashSet<>();
        for (RequestPost p : list) {
            if (p.getTaskId() != null) taskIds.add(p.getTaskId());
            if (p.getTargetRepoCatalogId() != null) repoIds.add(p.getTargetRepoCatalogId());
        }
        Map<Long, Task> taskById = new HashMap<>();
        if (!taskIds.isEmpty()) tasks.findAllById(taskIds).forEach(t -> taskById.put(t.getId(), t));
        Map<Long, String> aliasById = new HashMap<>();
        if (!repoIds.isEmpty()) catalog.findAllById(repoIds).forEach(e -> aliasById.put(e.getId(), e.getAlias()));
        return list.stream().map(p -> new RequestBoardDto.ListItem(p.getId(), p.getKind(), p.getKind().label(),
                p.getTitle(), p.getStatus(), p.getStatus().dbValue(), p.getRequesterId(), unread.test(p),
                p.getIssueType(), p.getIssueType() == null ? null : p.getIssueType().label(),
                p.getPriority(), p.getPriority() == null ? null : p.getPriority().label(),
                p.getDueDate(), p.getTargetRepoCatalogId() == null ? null : aliasById.get(p.getTargetRepoCatalogId()),
                attachments.countByRequestId(p.getId()),
                comments.countByRequestIdAndKind(p.getId(), RequestCommentKind.COMMENT),
                p.getTaskId(), taskStatusLabel(p.getTaskId(), taskById.get(p.getTaskId())),
                p.getCreatedAt(), p.getUpdatedAt())).toList();
    }

    /** 연결 작업이 없으면 null, 작업이 없거나 소프트 삭제면 "삭제됨", 아니면 작업 상태 라벨. */
    private static String taskStatusLabel(Long taskId, Task task) {
        if (taskId == null) return null;
        if (task == null || task.getDeletedAt() != null) return "삭제됨";
        return task.getStatus().dbValue();
    }

    private RequestBoardDto.CommentView toCommentView(RequestComment c) {
        return new RequestBoardDto.CommentView(c.getId(), c.getKind(), c.getAuthorId(), c.isAuthorAdmin(),
                c.getBody(), c.getCreatedAt());
    }

    private RequestBoardDto.Detail toDetail(RequestPost p, String viewerId, boolean viewerAdmin) {
        String repoAlias = p.getRepoCatalogId() == null ? null
                : catalog.findById(p.getRepoCatalogId()).map(RepoCatalogEntry::getAlias).orElse(null);
        String targetAlias = p.getTargetRepoCatalogId() == null ? null
                : catalog.findById(p.getTargetRepoCatalogId()).map(RepoCatalogEntry::getAlias).orElse(null);
        List<RequestBoardDto.CommentView> commentViews =
                comments.findByRequestIdOrderByCreatedAtAscIdAsc(p.getId()).stream()
                        .map(this::toCommentView)
                        .toList();
        List<RequestBoardDto.AttachmentView> attachmentViews =
                attachments.findByRequestIdOrderByOrdinalAsc(p.getId()).stream()
                        .map(this::toAttachmentView)
                        .toList();
        Task task = p.getTaskId() == null ? null : tasks.findById(p.getTaskId()).orElse(null);
        boolean owner = p.isOwnedBy(viewerId);
        boolean editable = owner && p.getStatus() == RequestStatus.RECEIVED;
        boolean dev = p.getKind() == RequestKind.DEV;
        return new RequestBoardDto.Detail(p.getId(), p.getKind(), p.getKind().label(), p.getTitle(), p.getContent(),
                p.getRepoHint(), p.getStatus(), p.getStatus().dbValue(), p.getRequesterId(),
                p.getRepoCatalogId(), repoAlias, p.getRepoGitUrl(),
                p.getRepoGitUrl() != null && p.getRepoCatalogId() == null,
                p.getIssueType(), p.getIssueType() == null ? null : p.getIssueType().label(),
                p.getPriority(), p.getPriority() == null ? null : p.getPriority().label(),
                p.getDueDate(), p.getTargetRepoCatalogId(), targetAlias,
                p.getTaskId(), taskStatusLabel(p.getTaskId(), task),
                attachmentViews,
                editable,
                editable && p.getTaskId() == null && cancelAllowedByComments(p, viewerId),
                viewerAdmin && dev && p.getStatus().isOpen() && p.getTaskId() == null,
                p.getCreatedAt(), p.getUpdatedAt(), commentViews);
    }

    /** cancel()의 답글 가드와 같은 규칙: DEV는 작성자 아닌 답글, 그 밖은 관리자 답글이 없어야 한다. */
    private boolean cancelAllowedByComments(RequestPost p, String viewerId) {
        if (p.getKind() == RequestKind.DEV) {
            return !comments.existsByRequestIdAndKindAndAuthorIdNot(p.getId(), RequestCommentKind.COMMENT, viewerId);
        }
        return !comments.existsByRequestIdAndKindAndAuthorAdminTrue(p.getId(), RequestCommentKind.COMMENT);
    }

    private RequestBoardDto.AttachmentView toAttachmentView(RequestAttachment a) {
        return new RequestBoardDto.AttachmentView(a.getId(), a.getOriginalName(), a.getSizeBytes(),
                !storage.exists(a.getStoredPath()));
    }
}
