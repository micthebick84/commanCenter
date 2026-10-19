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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestBoardServiceTest {

    private static final String URL = "https://gitlab.hamon.vip/a/b";
    private static final String CANONICAL = "https://gitlab.hamon.vip/a/b.git";

    private RequestPostRepository posts;
    private RequestCommentRepository comments;
    private RepoCatalogRepository catalog;
    private RepoCatalogService catalogService;
    private RequestBoardService service;

    @BeforeEach
    void setUp() {
        posts = mock(RequestPostRepository.class);
        comments = mock(RequestCommentRepository.class);
        catalog = mock(RepoCatalogRepository.class);
        catalogService = mock(RepoCatalogService.class);
        service = new RequestBoardService(posts, comments, catalog, catalogService, 20, "https://gitlab.hamon.vip");
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
                new RequestBoardDto.CreateRequest(RequestKind.FEATURE, "t", "c", null), "user1"))
                .isInstanceOf(TaskException.class)
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .hasMessage("처리 중인 요청이 너무 많습니다(최대 20건)");
        verify(posts, never()).save(any());
    }

    @Test
    void create_saves_without_system_comment() {
        when(posts.countByRequesterIdAndStatusIn(eq("user1"), any())).thenReturn(19L);

        RequestBoardDto.ListItem item = service.create(
                new RequestBoardDto.CreateRequest(RequestKind.FEATURE, "t", "c", "무시됨"), "user1");

        assertThat(item.status()).isEqualTo(RequestStatus.RECEIVED);
        assertThat(item.statusLabel()).isEqualTo("접수");
        assertThat(item.kindLabel()).isEqualTo("기능 추가");
        assertThat(item.unread()).isFalse();
        verify(comments, never()).save(any());
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
}
