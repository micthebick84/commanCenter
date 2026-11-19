package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.dto.RequestBoardDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.RequestAttachment;
import com.hamonsoft.netismaker.entity.RequestComment;
import com.hamonsoft.netismaker.entity.RequestCommentKind;
import com.hamonsoft.netismaker.entity.RequestIssueType;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestPriority;
import com.hamonsoft.netismaker.entity.RequestPost;
import com.hamonsoft.netismaker.entity.RequestStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.RequestAttachmentRepository;
import com.hamonsoft.netismaker.repository.RequestCommentRepository;
import com.hamonsoft.netismaker.repository.RequestPostRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestBoardServiceTest {

    private static final String URL = "https://gitlab.hamon.vip/a/b";
    private static final String CANONICAL = "https://gitlab.hamon.vip/a/b.git";

    private RequestPostRepository posts;
    private RequestCommentRepository comments;
    private RepoCatalogRepository catalog;
    private RepoCatalogService catalogService;
    private RequestAttachmentRepository attachments;
    private TaskRepository tasks;
    private AttachmentStorage storage;
    private RequestBoardService service;

    @BeforeEach
    void setUp() {
        posts = mock(RequestPostRepository.class);
        comments = mock(RequestCommentRepository.class);
        catalog = mock(RepoCatalogRepository.class);
        catalogService = mock(RepoCatalogService.class);
        attachments = mock(RequestAttachmentRepository.class);
        tasks = mock(TaskRepository.class);
        storage = mock(AttachmentStorage.class);
        service = new RequestBoardService(posts, comments, attachments, catalog, catalogService, tasks, storage,
                20, "https://gitlab.hamon.vip");
        when(posts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(comments.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private RequestPost post(Long id, RequestKind kind, RequestStatus status, String requester) {
        RequestPost p = RequestPost.create(kind, "제목", "내용", "힌트", requester);
        p.setStatus(status);
        ReflectionTestUtils.setField(p, "id", id);
        when(posts.findById(id)).thenReturn(Optional.of(p));
        when(posts.findByIdForUpdate(id)).thenReturn(Optional.of(p));
        return p;
    }

    private RepoCatalogEntry entry(Long id, String alias) {
        RepoCatalogEntry e = RepoCatalogEntry.create(alias, CANONICAL, "gitlab.hamon.vip", "a/b", "main", null, "admin1");
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private static GitLabProjectClient.GitLabProject project() {
        return new GitLabProjectClient.GitLabProject(42, "비 프로젝트", "a/b", "main", "설명");
    }

    private static HttpStatus statusOf(Throwable t) {
        return ((TaskException) t).getStatus();
    }

    private List<RequestComment> savedComments() {
        ArgumentCaptor<RequestComment> cap = ArgumentCaptor.forClass(RequestComment.class);
        verify(comments, atLeast(0)).save(cap.capture());
        return cap.getAllValues();
    }

    @Test
    void create_rejects_when_open_limit_reached() {
        when(posts.countByRequesterIdAndStatusIn(eq("user1"), any())).thenReturn(20L);

        assertThatThrownBy(() -> service.create(
                new RequestBoardDto.CreateRequest(RequestKind.REPO, "t", "c", null), "user1"))
                .isInstanceOf(TaskException.class)
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .hasMessage("처리 중인 요청이 너무 많습니다(최대 20건)");
        verify(posts, never()).save(any());
    }

    @Test
    void create_saves_without_system_comment() {
        when(posts.countByRequesterIdAndStatusIn(eq("user1"), any())).thenReturn(19L);

        RequestBoardDto.ListItem item = service.create(
                new RequestBoardDto.CreateRequest(RequestKind.REPO, "t", "c", "힌트"), "user1");

        assertThat(item.status()).isEqualTo(RequestStatus.RECEIVED);
        assertThat(item.statusLabel()).isEqualTo("접수");
        assertThat(item.kindLabel()).isEqualTo("사이트 추가");
        assertThat(item.unread()).isFalse();
        verify(comments, never()).save(any());
    }

    private RequestPost savedPost() {
        ArgumentCaptor<RequestPost> cap = ArgumentCaptor.forClass(RequestPost.class);
        verify(posts).save(cap.capture());
        return cap.getValue();
    }

    private static RequestBoardDto.CreateRequest dev(RequestIssueType type, RequestPriority pr,
                                                     LocalDate due, Long repoId) {
        return new RequestBoardDto.CreateRequest(RequestKind.DEV, "t", "c", "무시", type, pr, due, repoId);
    }

    @Test
    void create_FEATURE는_400() {
        assertThatThrownBy(() -> service.create(
                new RequestBoardDto.CreateRequest(RequestKind.FEATURE, "t", "c", null), "user1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("더 이상 받지 않는 요청 종류입니다");
        verify(posts, never()).save(any());
    }

    @Test
    void create_MCP는_400() {
        assertThatThrownBy(() -> service.create(
                new RequestBoardDto.CreateRequest(RequestKind.MCP, "t", "c", null), "user1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("더 이상 받지 않는 요청 종류입니다");
        verify(posts, never()).save(any());
    }

    @Test
    void create_DEV_유형없으면_400() {
        assertThatThrownBy(() -> service.create(dev(null, RequestPriority.HIGH, null, null), "user1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("유형을 선택하세요");
        verify(posts, never()).save(any());
    }

    @Test
    void create_DEV_우선순위_기본_NORMAL() {
        service.create(dev(RequestIssueType.BUG, null, null, null), "user1");

        RequestPost saved = savedPost();
        assertThat(saved.getPriority()).isEqualTo(RequestPriority.NORMAL);
        assertThat(saved.getIssueType()).isEqualTo(RequestIssueType.BUG);
        assertThat(saved.getRepoHint()).isNull();
    }

    @Test
    void create_DEV_없는_레포면_400() {
        when(catalogService.resolveForRegistration(99L)).thenThrow(
                new TaskException(HttpStatus.BAD_REQUEST, "존재하지 않는 레포 카탈로그 id: 99"));

        assertThatThrownBy(() -> service.create(dev(RequestIssueType.DEV, null, null, 99L), "user1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(posts, never()).save(any());
    }

    @Test
    void create_DEV_과거_마감일_허용() {
        LocalDate past = LocalDate.now().minusDays(30);

        service.create(dev(RequestIssueType.INQUIRY, RequestPriority.LOW, past, null), "user1");

        assertThat(savedPost().getDueDate()).isEqualTo(past);
    }

    @Test
    void create_REPO는_개발요청_칸을_버린다() {
        service.create(new RequestBoardDto.CreateRequest(RequestKind.REPO, "t", "c", "힌트",
                RequestIssueType.BUG, RequestPriority.HIGH, LocalDate.now(), 5L), "user1");

        RequestPost saved = savedPost();
        assertThat(saved.getIssueType()).isNull();
        assertThat(saved.getPriority()).isNull();
        assertThat(saved.getDueDate()).isNull();
        assertThat(saved.getTargetRepoCatalogId()).isNull();
        assertThat(saved.getRepoHint()).isEqualTo("힌트");
        verify(catalogService, never()).resolveForRegistration(any());
    }

    @Test
    void update_DEV_칸_변경() {
        RequestPost p = post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user1");
        LocalDate due = LocalDate.now().plusDays(3);

        service.update(1L, new RequestBoardDto.UpdateRequest("새", "새 내용", "힌트",
                RequestIssueType.INQUIRY, RequestPriority.HIGH, due, 7L, null), "user1");

        assertThat(p.getIssueType()).isEqualTo(RequestIssueType.INQUIRY);
        assertThat(p.getPriority()).isEqualTo(RequestPriority.HIGH);
        assertThat(p.getDueDate()).isEqualTo(due);
        assertThat(p.getTargetRepoCatalogId()).isEqualTo(7L);
        assertThat(p.getRepoHint()).isNull();
        verify(catalogService).resolveForRegistration(7L);
        assertThatThrownBy(() -> service.update(1L,
                new RequestBoardDto.UpdateRequest("t", "c", null), "user1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("유형을 선택하세요");
    }

    @Test
    void get_other_users_request_is_404() {
        post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user2");

        assertThatThrownBy(() -> service.getAndMarkRead(1L, "user1", false))
                .isInstanceOf(TaskException.class)
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage("요청을 찾을 수 없습니다");
    }

    @Test
    void admin_viewing_own_request_marks_both_read() {
        RequestPost p = post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "admin1");
        OffsetDateTime before = OffsetDateTime.now();

        service.getAndMarkRead(1L, "admin1", true);

        assertThat(p.getRequesterReadAt()).isAfterOrEqualTo(before);
        assertThat(p.getAdminReadAt()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    void update_only_in_received() {
        post(1L, RequestKind.FEATURE, RequestStatus.REVIEWING, "user1");

        assertThatThrownBy(() -> service.update(1L,
                new RequestBoardDto.UpdateRequest("새 제목", "새 내용", null), "user1"))
                .isInstanceOf(TaskException.class)
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("접수 상태에서만 수정할 수 있습니다");
    }

    @Test
    void update_drops_hint_for_non_repo_kind_and_touches_requester() {
        RequestPost p = post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");

        RequestBoardDto.Detail d = service.update(1L,
                new RequestBoardDto.UpdateRequest("새 제목", "새 내용", "힌트"), "user1");

        assertThat(p.getTitle()).isEqualTo("새 제목");
        assertThat(p.getContent()).isEqualTo("새 내용");
        assertThat(p.getRepoHint()).isNull();
        assertThat(d.editable()).isTrue();
    }

    @Test
    void update_and_cancel_take_the_row_lock() {
        post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");

        service.update(1L, new RequestBoardDto.UpdateRequest("t", "c", null), "user1");
        service.cancel(1L, "user1");

        verify(posts, times(2)).findByIdForUpdate(1L);
        verify(posts, never()).findById(anyLong());
    }

    @Test
    void update_by_other_user_is_404() {
        post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user2");

        assertThatThrownBy(() -> service.update(1L,
                new RequestBoardDto.UpdateRequest("t", "c", null), "user1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void cancel_blocked_by_admin_comment() {
        post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");
        when(comments.existsByRequestIdAndKindAndAuthorAdminTrue(1L, RequestCommentKind.COMMENT)).thenReturn(true);

        assertThatThrownBy(() -> service.cancel(1L, "user1"))
                .isInstanceOf(TaskException.class)
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("관리자 답글이 있는 요청은 취소할 수 없습니다");
        verify(posts, never()).deleteById(anyLong());

        when(comments.existsByRequestIdAndKindAndAuthorAdminTrue(1L, RequestCommentKind.COMMENT)).thenReturn(false);
        service.cancel(1L, "user1");
        verify(posts).deleteById(1L);
    }

    @Test
    void cancel_requires_received_status() {
        post(1L, RequestKind.FEATURE, RequestStatus.REVIEWING, "user1");

        assertThatThrownBy(() -> service.cancel(1L, "user1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT));
        verify(posts, never()).deleteById(anyLong());
    }

    @Test
    void change_status_same_is_noop() {
        RequestPost p = post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");

        RequestBoardDto.Detail d = service.changeStatus(1L,
                new RequestBoardDto.StatusChangeRequest(RequestStatus.RECEIVED, null), "admin1");

        assertThat(d.status()).isEqualTo(RequestStatus.RECEIVED);
        assertThat(p.getLastAdminActivityAt()).isNull();
        verify(comments, never()).save(any());
    }

    @Test
    void change_status_illegal_conflicts() {
        post(1L, RequestKind.FEATURE, RequestStatus.DONE, "user1");

        assertThatThrownBy(() -> service.changeStatus(1L,
                new RequestBoardDto.StatusChangeRequest(RequestStatus.RECEIVED, null), "admin1"))
                .isInstanceOf(TaskException.class)
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("바꿀 수 없는 상태입니다: 처리완료 → 접수");
    }

    @Test
    void reject_requires_reason() {
        post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");

        assertThatThrownBy(() -> service.changeStatus(1L,
                new RequestBoardDto.StatusChangeRequest(RequestStatus.REJECTED, " "), "admin1"))
                .isInstanceOf(TaskException.class)
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("반려 사유를 입력하세요");
        verify(comments, never()).save(any());
    }

    @Test
    void reject_writes_system_comment_with_reason() {
        RequestPost p = post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");

        service.changeStatus(1L,
                new RequestBoardDto.StatusChangeRequest(RequestStatus.REJECTED, "중복"), "admin1");

        List<RequestComment> saved = savedComments();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getKind()).isEqualTo(RequestCommentKind.SYSTEM);
        assertThat(saved.get(0).getBody()).isEqualTo("관리자(admin1)가 상태를 반려로 변경 — 사유: 중복");
        assertThat(p.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(p.getLastAdminActivityAt()).isNotNull();
    }

    @Test
    void non_reject_system_comment_has_no_reason_suffix() {
        post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");

        service.changeStatus(1L,
                new RequestBoardDto.StatusChangeRequest(RequestStatus.REVIEWING, "무시"), "admin1");

        assertThat(savedComments().get(0).getBody()).isEqualTo("관리자(admin1)가 상태를 검토중으로 변경");
    }

    @Test
    void system_comment_uses_matching_particle_for_each_label() {
        post(1L, RequestKind.FEATURE, RequestStatus.REVIEWING, "user1");
        service.changeStatus(1L, new RequestBoardDto.StatusChangeRequest(RequestStatus.DONE, null), "admin1");
        service.changeStatus(1L, new RequestBoardDto.StatusChangeRequest(RequestStatus.REVIEWING, null), "admin1");
        service.changeStatus(1L, new RequestBoardDto.StatusChangeRequest(RequestStatus.RECEIVED, null), "admin1");

        assertThat(savedComments()).extracting(RequestComment::getBody).containsExactly(
                "관리자(admin1)가 상태를 처리완료로 변경",
                "관리자(admin1)가 상태를 검토중으로 변경",
                "관리자(admin1)가 상태를 접수로 변경");
    }

    @Test
    void admin_comment_makes_requester_unread() {
        RequestPost p = post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");
        p.setRequesterReadAt(OffsetDateTime.now().minusMinutes(1));
        assertThat(p.unreadFor(false)).isFalse();

        RequestBoardDto.CommentView v = service.addComment(1L,
                new RequestBoardDto.CommentRequest("확인했습니다"), "admin1", true);

        assertThat(p.unreadFor(false)).isTrue();
        assertThat(v.authorAdmin()).isTrue();
        assertThat(v.kind()).isEqualTo(RequestCommentKind.COMMENT);
    }

    @Test
    void comment_by_non_owner_non_admin_is_404() {
        post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user2");

        assertThatThrownBy(() -> service.addComment(1L,
                new RequestBoardDto.CommentRequest("x"), "user1", false))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
        verify(comments, never()).save(any());
    }

    @Test
    void requester_comment_makes_admin_unread() {
        RequestPost p = post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");
        p.setLastRequesterActivityAt(OffsetDateTime.now().minusMinutes(2));
        p.setAdminReadAt(OffsetDateTime.now().minusMinutes(1));
        assertThat(p.unreadFor(true)).isFalse();

        service.addComment(1L, new RequestBoardDto.CommentRequest("추가 설명"), "user1", false);

        assertThat(p.unreadFor(true)).isTrue();
    }

    @Test
    void detail_marks_catalog_deleted() {
        RequestPost p = post(1L, RequestKind.REPO, RequestStatus.DONE, "user1");
        p.setRepoGitUrl(CANONICAL);
        p.setRepoCatalogId(null);

        RequestBoardDto.Detail d = service.getAndMarkRead(1L, "user1", false);

        assertThat(d.repoCatalogDeleted()).isTrue();
        assertThat(d.repoAlias()).isNull();
        assertThat(d.editable()).isFalse();
    }

    @Test
    void detail_resolves_repo_alias() {
        RequestPost p = post(1L, RequestKind.REPO, RequestStatus.DONE, "user1");
        p.setRepoGitUrl(CANONICAL);
        p.setRepoCatalogId(5L);
        when(catalog.findById(5L)).thenReturn(Optional.of(entry(5L, "별칭")));

        RequestBoardDto.Detail d = service.getAndMarkRead(1L, "user1", false);

        assertThat(d.repoAlias()).isEqualTo("별칭");
        assertThat(d.repoCatalogDeleted()).isFalse();
    }

    @Test
    void list_for_admin_defaults_empty_statuses_to_open_ones() {
        when(posts.findForAdmin(any(), any(), anyBoolean(), any())).thenReturn(List.of());

        service.listForAdmin(null, Set.of(), true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<RequestStatus>> cap = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(posts).findForAdmin(eq(null), cap.capture(), eq(true), page.capture());
        assertThat(cap.getValue()).containsExactlyInAnyOrder(RequestStatus.RECEIVED, RequestStatus.REVIEWING);
        assertThat(page.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void summary_reads_counts() {
        when(posts.countByStatus(RequestStatus.RECEIVED)).thenReturn(3L);
        when(posts.countAttention()).thenReturn(5L);

        assertThat(service.summary()).isEqualTo(new RequestBoardDto.Summary(3, 5));
    }

    // ---- 레포 등록 완료 ----

    @Test
    void complete_repo_creates_catalog_and_closes() {
        RequestPost p = post(7L, RequestKind.REPO, RequestStatus.RECEIVED, "user1");
        when(catalog.findByGitUrl(any())).thenReturn(Optional.empty());
        when(catalog.findByAlias("비 프로젝트")).thenReturn(Optional.empty());
        when(catalogService.create(any(), eq("admin1"))).thenReturn(entry(11L, "비 프로젝트"));

        service.completeRepoRequest(7L, project(), URL, "admin1");

        ArgumentCaptor<RepoCatalogDto.UpsertRequest> cap = ArgumentCaptor.forClass(RepoCatalogDto.UpsertRequest.class);
        verify(catalogService).create(cap.capture(), eq("admin1"));
        assertThat(cap.getValue().alias()).isEqualTo("비 프로젝트");
        assertThat(cap.getValue().gitUrl()).isEqualTo(URL);
        assertThat(cap.getValue().defaultBranch()).isEqualTo("main");
        assertThat(cap.getValue().description()).isEqualTo("요청 #7에서 등록");
        assertThat(cap.getValue().enabled()).isTrue();
        assertThat(p.getStatus()).isEqualTo(RequestStatus.DONE);
        assertThat(p.getRepoCatalogId()).isEqualTo(11L);
        assertThat(p.getRepoGitUrl()).isEqualTo(CANONICAL);
        assertThat(p.getLastAdminActivityAt()).isNotNull();
        List<RequestComment> saved = savedComments();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getKind()).isEqualTo(RequestCommentKind.SYSTEM);
        assertThat(saved.get(0).getBody())
                .isEqualTo("관리자(admin1)가 레포 카탈로그에 비 프로젝트 (a/b) 등록 — 처리완료");
    }

    @Test
    void complete_repo_alias_falls_back_to_path() {
        post(7L, RequestKind.REPO, RequestStatus.RECEIVED, "user1");
        when(catalog.findByGitUrl(any())).thenReturn(Optional.empty());
        when(catalog.findByAlias("비 프로젝트")).thenReturn(Optional.of(entry(1L, "비 프로젝트")));
        when(catalog.findByAlias("a/b")).thenReturn(Optional.empty());
        when(catalogService.create(any(), any())).thenReturn(entry(11L, "a/b"));

        service.completeRepoRequest(7L, project(), URL, "admin1");

        ArgumentCaptor<RepoCatalogDto.UpsertRequest> cap = ArgumentCaptor.forClass(RepoCatalogDto.UpsertRequest.class);
        verify(catalogService).create(cap.capture(), any());
        assertThat(cap.getValue().alias()).isEqualTo("a/b");
    }

    @Test
    void complete_repo_alias_conflict_everywhere_is_409() {
        post(7L, RequestKind.REPO, RequestStatus.RECEIVED, "user1");
        when(catalog.findByGitUrl(any())).thenReturn(Optional.empty());
        when(catalog.findByAlias(any())).thenReturn(Optional.of(entry(1L, "x")));

        assertThatThrownBy(() -> service.completeRepoRequest(7L, project(), URL, "admin1"))
                .isInstanceOf(TaskException.class)
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("이미 존재하는 별칭 — 레포 카탈로그 화면에서 등록하세요");
        verify(catalogService, never()).create(any(), any());
    }

    @Test
    void complete_repo_skips_alias_candidates_over_100_chars() {
        post(7L, RequestKind.REPO, RequestStatus.RECEIVED, "user1");
        when(catalog.findByGitUrl(any())).thenReturn(Optional.empty());
        when(catalog.findByAlias("a/b")).thenReturn(Optional.empty());
        when(catalogService.create(any(), any())).thenReturn(entry(11L, "a/b"));
        var longName = new GitLabProjectClient.GitLabProject(42, "가".repeat(101), "a/b", "main", null);

        service.completeRepoRequest(7L, longName, URL, "admin1");

        ArgumentCaptor<RepoCatalogDto.UpsertRequest> cap = ArgumentCaptor.forClass(RepoCatalogDto.UpsertRequest.class);
        verify(catalogService).create(cap.capture(), any());
        assertThat(cap.getValue().alias()).isEqualTo("a/b");
        verify(catalog, never()).findByAlias("가".repeat(101));
    }

    @Test
    void complete_repo_without_usable_alias_is_409_with_own_message() {
        post(7L, RequestKind.REPO, RequestStatus.RECEIVED, "user1");
        when(catalog.findByGitUrl(any())).thenReturn(Optional.empty());
        var tooLong = new GitLabProjectClient.GitLabProject(42, "가".repeat(101), "나".repeat(101), "main", null);

        assertThatThrownBy(() -> service.completeRepoRequest(7L, tooLong, URL, "admin1"))
                .isInstanceOf(TaskException.class)
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("별칭으로 쓸 수 있는 이름이 없습니다 — 레포 카탈로그 화면에서 등록하세요");
        verify(catalogService, never()).create(any(), any());
    }

    @Test
    void complete_repo_links_existing_url() {
        RequestPost p = post(7L, RequestKind.REPO, RequestStatus.RECEIVED, "user1");
        when(catalog.findByGitUrl(CANONICAL)).thenReturn(Optional.of(entry(9L, "기존")));

        service.completeRepoRequest(7L, project(), URL, "admin1");

        verify(catalogService, never()).create(any(), any());
        assertThat(p.getRepoCatalogId()).isEqualTo(9L);
        assertThat(p.getStatus()).isEqualTo(RequestStatus.DONE);
        assertThat(savedComments().get(0).getBody())
                .isEqualTo("관리자(admin1)가 레포 카탈로그에 기존 (a/b) 연결 — 처리완료");
    }

    @Test
    void complete_repo_on_non_repo_conflicts() {
        post(7L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user1");

        assertThatThrownBy(() -> service.completeRepoRequest(7L, project(), URL, "admin1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("REPO 추가 요청이 아닙니다");
        verify(catalogService, never()).create(any(), any());
    }

    @Test
    void complete_repo_on_done_conflicts() {
        post(7L, RequestKind.REPO, RequestStatus.DONE, "user1");

        assertThatThrownBy(() -> service.completeRepoRequest(7L, project(), URL, "admin1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("이미 처리된 요청입니다");
        verify(catalogService, never()).create(any(), any());
    }

    @Test
    void complete_repo_with_bad_url_is_400() {
        post(7L, RequestKind.REPO, RequestStatus.RECEIVED, "user1");

        assertThatThrownBy(() -> service.completeRepoRequest(7L, project(), " ", "admin1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void assert_open_repo_request_checks_without_lock() {
        post(7L, RequestKind.REPO, RequestStatus.REVIEWING, "user1");
        service.assertOpenRepoRequest(7L);
        verify(posts, never()).findByIdForUpdate(anyLong());

        assertThatThrownBy(() -> service.assertOpenRepoRequest(99L))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- 개발 요청 공개 규칙 (Task 3) ----

    @Test
    void DEV_상세는_남도_본다() {
        post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user2");

        RequestBoardDto.Detail d = service.getAndMarkRead(1L, "user1", false);

        assertThat(d.id()).isEqualTo(1L);
        assertThat(d.editable()).isFalse();
        assertThat(d.cancellable()).isFalse();
        assertThat(d.convertible()).isFalse();
    }

    @Test
    void REPO_상세는_남에게_404() {
        post(1L, RequestKind.REPO, RequestStatus.RECEIVED, "user2");
        assertThatThrownBy(() -> service.getAndMarkRead(1L, "user1", false))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void FEATURE_상세는_남에게_404() {
        post(1L, RequestKind.FEATURE, RequestStatus.RECEIVED, "user2");
        assertThatThrownBy(() -> service.getAndMarkRead(1L, "user1", false))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void listDev는_DEV만_돌려준다() {
        RequestPost dev = post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user2");
        when(posts.findDev(eq(RequestKind.DEV), any(), any(), anyBoolean(), any())).thenReturn(List.of(dev));

        List<RequestBoardDto.ListItem> items = service.listDev(null, Set.of(), "user1", false);

        assertThat(items).extracting(RequestBoardDto.ListItem::kind).containsExactly(RequestKind.DEV);
        assertThat(items.get(0).unread()).isFalse();
        verify(posts).findDev(eq(RequestKind.DEV), eq(null), any(), eq(true), any(Pageable.class));
    }

    @Test
    void listDev_issueType_필터와_상태() {
        when(posts.findDev(any(), any(), any(), anyBoolean(), any())).thenReturn(List.of());

        service.listDev(RequestIssueType.BUG, Set.of(RequestStatus.DONE), "user1", false);

        verify(posts).findDev(eq(RequestKind.DEV), eq(RequestIssueType.BUG), eq(Set.of(RequestStatus.DONE)),
                eq(false), any(Pageable.class));
    }

    @Test
    void listDev_unread는_보는_사람_기준() {
        RequestPost p = post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user2");
        p.setLastRequesterActivityAt(OffsetDateTime.now());
        p.setAdminReadAt(OffsetDateTime.now().minusMinutes(5));
        p.setLastAdminActivityAt(OffsetDateTime.now());
        p.setRequesterReadAt(OffsetDateTime.now().minusMinutes(5));
        when(posts.findDev(any(), any(), any(), anyBoolean(), any())).thenReturn(List.of(p));

        assertThat(service.listDev(null, Set.of(), "user2", false).get(0).unread()).isTrue();
        assertThat(service.listDev(null, Set.of(), "admin1", true).get(0).unread()).isTrue();
        assertThat(service.listDev(null, Set.of(), "user3", false).get(0).unread()).isFalse();
    }

    @Test
    void 목록_연결작업_상태라벨_삭제됨() {
        RequestPost p = post(1L, RequestKind.DEV, RequestStatus.DONE, "user2");
        p.setTaskId(7L);
        Task gone = mock(Task.class);
        when(gone.getId()).thenReturn(7L);
        when(gone.getDeletedAt()).thenReturn(OffsetDateTime.now());
        when(tasks.findAllById(any())).thenReturn(List.of(gone));
        when(posts.findDev(any(), any(), any(), anyBoolean(), any())).thenReturn(List.of(p));

        RequestBoardDto.ListItem item = service.listDev(null, Set.of(), "user1", false).get(0);

        assertThat(item.taskId()).isEqualTo(7L);
        assertThat(item.taskStatusLabel()).isEqualTo("삭제됨");
    }

    @Test
    void DEV_답글은_남도_단다() {
        post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user2");

        RequestBoardDto.CommentView v = service.addComment(1L, new RequestBoardDto.CommentRequest("저도요"),
                "user1", false);

        assertThat(v.authorId()).isEqualTo("user1");
        assertThat(v.authorAdmin()).isFalse();
    }

    @Test
    void REPO_답글은_남에게_404() {
        post(1L, RequestKind.REPO, RequestStatus.RECEIVED, "user2");
        assertThatThrownBy(() -> service.addComment(1L, new RequestBoardDto.CommentRequest("x"), "user1", false))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
        verify(comments, never()).save(any());
    }

    @Test
    void 제3자_답글은_작성자와_관리자_모두_unread() {
        RequestPost p = post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user2");
        OffsetDateTime past = OffsetDateTime.now().minusMinutes(5);
        p.setRequesterReadAt(past);
        p.setAdminReadAt(past);
        p.setLastRequesterActivityAt(past.minusMinutes(1));
        assertThat(p.unreadFor(false)).isFalse();
        assertThat(p.unreadFor(true)).isFalse();

        service.addComment(1L, new RequestBoardDto.CommentRequest("저도요"), "user1", false);

        assertThat(p.unreadFor(false)).isTrue();
        assertThat(p.unreadFor(true)).isTrue();
    }

    @Test
    void 제3자_열람은_읽음을_바꾸지_않는다() {
        RequestPost p = post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user2");
        OffsetDateTime past = OffsetDateTime.now().minusMinutes(5);
        p.setRequesterReadAt(past);
        p.setAdminReadAt(past);

        service.getAndMarkRead(1L, "user1", false);

        assertThat(p.getRequesterReadAt()).isEqualTo(past);
        assertThat(p.getAdminReadAt()).isEqualTo(past);
    }

    @Test
    void DEV_남의_답글이_있으면_취소_409() {
        post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user1");
        when(comments.existsByRequestIdAndKindAndAuthorIdNot(1L, RequestCommentKind.COMMENT, "user1"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.cancel(1L, "user1"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("다른 사람의 답글이 있는 요청은 취소할 수 없습니다");
        verify(posts, never()).deleteById(anyLong());
    }

    @Test
    void DEV_자기_답글만_있으면_취소_가능() {
        post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user1");
        when(comments.existsByRequestIdAndKindAndAuthorIdNot(1L, RequestCommentKind.COMMENT, "user1"))
                .thenReturn(false);

        service.cancel(1L, "user1");

        verify(posts).deleteById(1L);
    }

    @Test
    void convertible은_관리자에게만_true() {
        post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user2");

        assertThat(service.getAndMarkRead(1L, "admin1", true).convertible()).isTrue();
        assertThat(service.getAndMarkRead(1L, "user1", false).convertible()).isFalse();
        assertThat(service.getAndMarkRead(1L, "user2", false).convertible()).isFalse();
    }

    @Test
    void convertible은_작업이_있거나_닫히면_false() {
        RequestPost p = post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user2");
        p.setTaskId(5L);
        assertThat(service.getAndMarkRead(1L, "admin1", true).convertible()).isFalse();
        p.setTaskId(null);
        p.setStatus(RequestStatus.DONE);
        assertThat(service.getAndMarkRead(1L, "admin1", true).convertible()).isFalse();
    }

    @Test
    void 상세_첨부는_파일이_없으면_expired() {
        post(1L, RequestKind.DEV, RequestStatus.RECEIVED, "user2");
        RequestAttachment a = RequestAttachment.create(1L, 1, "a.png", "request-1/1-a.png", "image/png", 10);
        ReflectionTestUtils.setField(a, "id", 9L);
        when(attachments.findByRequestIdOrderByOrdinalAsc(1L)).thenReturn(List.of(a));
        when(storage.exists("request-1/1-a.png")).thenReturn(false);

        RequestBoardDto.AttachmentView v = service.getAndMarkRead(1L, "user1", false).attachments().get(0);

        assertThat(v.id()).isEqualTo(9L);
        assertThat(v.expired()).isTrue();
    }
}
