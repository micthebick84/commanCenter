package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.dto.RequestBoardDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.RequestComment;
import com.hamonsoft.netismaker.entity.RequestCommentKind;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestPost;
import com.hamonsoft.netismaker.entity.RequestStatus;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.RequestCommentRepository;
import com.hamonsoft.netismaker.repository.RequestPostRepository;
import com.hamonsoft.netismaker.util.RepoUrlParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 요청 게시판 서비스 규칙 (스펙 2026-10-19). 남의 요청은 존재 자체를 숨겨 404로 답한다.
 */
@Service
@Profile("api")
public class RequestBoardService {

    private static final String NOT_FOUND_MESSAGE = "요청을 찾을 수 없습니다";
    private static final int LIST_LIMIT = 200;
    private static final Set<RequestStatus> OPEN_STATUSES = Set.of(RequestStatus.RECEIVED, RequestStatus.REVIEWING);

    private final RequestPostRepository posts;
    private final RequestCommentRepository comments;
    private final RepoCatalogRepository catalog;
    private final RepoCatalogService repoCatalogService;
    private final int maxOpenPerUser;
    private final String gitlabBaseUrl;

    public RequestBoardService(RequestPostRepository posts,
                               RequestCommentRepository comments,
                               RepoCatalogRepository catalog,
                               RepoCatalogService repoCatalogService,
                               @Value("${app.request-board.max-open-per-user:20}") int maxOpenPerUser,
                               @Value("${app.gitlab.base-url:}") String gitlabBaseUrl) {
        this.posts = posts;
        this.comments = comments;
        this.catalog = catalog;
        this.repoCatalogService = repoCatalogService;
        this.maxOpenPerUser = maxOpenPerUser;
        this.gitlabBaseUrl = gitlabBaseUrl;
    }

    @Transactional
    public RequestBoardDto.ListItem create(RequestBoardDto.CreateRequest req, String userId) {
        if (posts.countByRequesterIdAndStatusIn(userId, OPEN_STATUSES) >= maxOpenPerUser) {
            throw new TaskException(HttpStatus.TOO_MANY_REQUESTS,
                    "처리 중인 요청이 너무 많습니다(최대 " + maxOpenPerUser + "건)");
        }
        RequestPost saved = posts.save(
                RequestPost.create(req.kind(), req.title(), req.content(), req.repoHint(), userId));
        return toListItem(saved, false);
    }

    @Transactional(readOnly = true)
    public List<RequestBoardDto.ListItem> listMine(String userId) {
        return posts.findByRequesterIdOrderByUpdatedAtDesc(userId, Limit.of(LIST_LIMIT)).stream()
                .map(p -> toListItem(p, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RequestBoardDto.ListItem> listForAdmin(RequestKind kind, Set<RequestStatus> statuses,
                                                       boolean includeUnread) {
        // 빈 IN 절은 질의가 깨지거나 아무것도 못 찾으므로 기본값(열린 상태)으로 대체한다.
        Set<RequestStatus> effective = (statuses == null || statuses.isEmpty()) ? OPEN_STATUSES : statuses;
        return posts.findForAdmin(kind, effective, includeUnread, PageRequest.of(0, LIST_LIMIT)).stream()
                .map(p -> toListItem(p, true))
                .toList();
    }

    @Transactional
    public RequestBoardDto.Detail getAndMarkRead(Long id, String viewerId, boolean admin) {
        RequestPost post = findVisible(id, viewerId, admin);
        post.markRead(post.isOwnedBy(viewerId), admin, OffsetDateTime.now());
        return toDetail(post, viewerId);
    }

    @Transactional
    public RequestBoardDto.Detail update(Long id, RequestBoardDto.UpdateRequest req, String userId) {
        RequestPost post = findOwned(id, userId);
        if (post.getStatus() != RequestStatus.RECEIVED) {
            throw TaskException.conflict("접수 상태에서만 수정할 수 있습니다");
        }
        post.setTitle(req.title());
        post.setContent(req.content());
        post.setRepoHint(post.getKind() == RequestKind.REPO ? req.repoHint() : null);
        post.touchRequester(OffsetDateTime.now());
        return toDetail(post, userId);
    }

    @Transactional
    public void cancel(Long id, String userId) {
        RequestPost post = findOwned(id, userId);
        if (post.getStatus() != RequestStatus.RECEIVED) {
            throw TaskException.conflict("접수 상태에서만 취소할 수 있습니다");
        }
        if (comments.existsByRequestIdAndKindAndAuthorAdminTrue(id, RequestCommentKind.COMMENT)) {
            throw TaskException.conflict("관리자 답글이 있는 요청은 취소할 수 없습니다");
        }
        posts.deleteById(id);
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
        return toCommentView(saved);
    }

    @Transactional
    public RequestBoardDto.Detail changeStatus(Long id, RequestBoardDto.StatusChangeRequest req, String adminId) {
        RequestPost post = posts.findByIdForUpdate(id).orElseThrow(RequestBoardService::notFound);
        RequestStatus current = post.getStatus();
        RequestStatus next = req.status();
        if (current == next) {
            return toDetail(post, adminId);
        }
        if (!current.canTransitionTo(next)) {
            throw TaskException.conflict("바꿀 수 없는 상태입니다: " + current.dbValue() + " → " + next.dbValue());
        }
        String reason = req.reason() == null ? "" : req.reason().strip();
        if (next == RequestStatus.REJECTED && reason.isEmpty()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "반려 사유를 입력하세요");
        }
        String body = "관리자(" + adminId + ")가 상태를 " + next.dbValue() + "로 변경";
        if (next == RequestStatus.REJECTED) body += " — 사유: " + reason;
        post.setStatus(next);
        comments.save(RequestComment.create(id, RequestCommentKind.SYSTEM, adminId, true, body));
        post.touchAdmin(OffsetDateTime.now());
        return toDetail(post, adminId);
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
        return toDetail(post, adminId);
    }

    private RepoCatalogEntry registerNew(Long requestId, GitLabProjectClient.GitLabProject project,
                                         String gitUrl, String adminId) {
        String alias = project.name();
        if (catalog.findByAlias(alias).isPresent()) {
            alias = project.pathWithNamespace();
            if (catalog.findByAlias(alias).isPresent()) {
                throw TaskException.conflict("이미 존재하는 별칭 — 레포 카탈로그 화면에서 등록하세요");
            }
        }
        return repoCatalogService.create(new RepoCatalogDto.UpsertRequest(
                alias, gitUrl, project.defaultBranch(), "요청 #" + requestId + "에서 등록", true), adminId);
    }

    private static void requireOpenRepo(RequestPost post) {
        if (post.getKind() != RequestKind.REPO) {
            throw TaskException.conflict("REPO 추가 요청이 아닙니다");
        }
        if (!post.getStatus().isOpen()) {
            throw TaskException.conflict("이미 처리된 요청입니다");
        }
    }

    // ---- 조회·변환 ----

    private static TaskException notFound() {
        return new TaskException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }

    private RequestPost findOwned(Long id, String userId) {
        RequestPost post = posts.findById(id).orElseThrow(RequestBoardService::notFound);
        if (!post.isOwnedBy(userId)) throw notFound();
        return post;
    }

    private RequestPost findVisible(Long id, String viewerId, boolean admin) {
        RequestPost post = posts.findById(id).orElseThrow(RequestBoardService::notFound);
        if (!admin && !post.isOwnedBy(viewerId)) throw notFound();
        return post;
    }

    private RequestBoardDto.ListItem toListItem(RequestPost p, boolean asAdmin) {
        return new RequestBoardDto.ListItem(p.getId(), p.getKind(), p.getKind().label(), p.getTitle(),
                p.getStatus(), p.getStatus().dbValue(), p.getRequesterId(), p.unreadFor(asAdmin),
                p.getCreatedAt(), p.getUpdatedAt());
    }

    private RequestBoardDto.CommentView toCommentView(RequestComment c) {
        return new RequestBoardDto.CommentView(c.getId(), c.getKind(), c.getAuthorId(), c.isAuthorAdmin(),
                c.getBody(), c.getCreatedAt());
    }

    private RequestBoardDto.Detail toDetail(RequestPost p, String viewerId) {
        String repoAlias = p.getRepoCatalogId() == null ? null
                : catalog.findById(p.getRepoCatalogId()).map(RepoCatalogEntry::getAlias).orElse(null);
        List<RequestBoardDto.CommentView> commentViews =
                comments.findByRequestIdOrderByCreatedAtAscIdAsc(p.getId()).stream()
                        .map(this::toCommentView)
                        .toList();
        return new RequestBoardDto.Detail(p.getId(), p.getKind(), p.getKind().label(), p.getTitle(), p.getContent(),
                p.getRepoHint(), p.getStatus(), p.getStatus().dbValue(), p.getRequesterId(),
                p.getRepoCatalogId(), repoAlias, p.getRepoGitUrl(),
                p.getRepoGitUrl() != null && p.getRepoCatalogId() == null,
                p.isOwnedBy(viewerId) && p.getStatus() == RequestStatus.RECEIVED,
                p.getCreatedAt(), p.getUpdatedAt(), commentViews);
    }
}
