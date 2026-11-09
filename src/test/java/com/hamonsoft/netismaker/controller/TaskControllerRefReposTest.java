package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.TaskResponse;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.DeployLogStreamService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.TaskFixService;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 작업 상세·목록의 참조 repo (스펙 2026-10-24 §4.9) — 인터뷰 단계 상세는 진행 중 세션의 참조(고정·실패 상태 포함),
 * 그 밖과 목록은 작업의 참조만. 컨트롤러를 직접 만들어 확인한다(TaskControllerSecretRedactionTest와 같은 구성).
 */
class TaskControllerRefReposTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final RefRepo REF11 = new RefRepo(11L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5",
            null, null, null);

    private TaskService taskService;
    private InterviewService interviewService;
    private TaskController controller;
    private Task task;
    private final JwtAuthenticationToken user = auth("user1", "ROLE_USER");

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        interviewService = mock(InterviewService.class);
        controller = new TaskController(taskService, mock(DeployLogStreamService.class), interviewService,
                mock(TaskFixService.class));
        task = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setRefRepos(new ArrayList<>(List.of(REF11)));
        task.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_PASSWORD", "pw", true))));
        when(taskService.getForView(eq(5L), anyString(), anyBoolean())).thenReturn(task);
        when(taskService.list(anyString(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(task)));
    }

    private static JwtAuthenticationToken auth(String userId, String role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("username", userId).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"INTERVIEWING", "INTERVIEW_INPUT", "INTERVIEW_REVIEW"})
    void detail_in_an_interview_stage_shows_the_running_session_refs(TaskStatus st) {
        task.setStatus(st);
        when(interviewService.latestSessionForTask(5L)).thenReturn(Optional.of(
                new InterviewService.LatestSession(42L, List.of(REF11.pinned(SHA, "2026-10-24T10:00+09:00")))));

        TaskResponse r = controller.get(5L, user);

        assertThat(r.refRepos()).extracting(InterviewResponse.RefRepoView::sha).containsExactly(SHA);
        assertThat(r.interviewSessionId()).isEqualTo(42L);
        assertThat(r.envVars()).extracting(EnvVar::value).containsExactly("");   // 비관리자 가림은 그대로
        // 최신 세션은 한 번만 읽는다 — id와 참조를 같은 조회에서
        verify(interviewService, times(1)).latestSessionForTask(5L);
        verify(interviewService, never()).latestSessionIdForTask(any());
    }

    @Test
    void detail_in_an_interview_stage_without_a_session_shows_task_refs() {
        task.setStatus(TaskStatus.INTERVIEWING);
        when(interviewService.latestSessionForTask(5L)).thenReturn(Optional.empty());

        TaskResponse r = controller.get(5L, user);

        assertThat(r.refRepos()).extracting(InterviewResponse.RefRepoView::catalogId).containsExactly(11L);
        assertThat(r.interviewSessionId()).isNull();
    }

    @Test
    void detail_outside_the_interview_stage_shows_task_refs_but_still_names_the_latest_session() {
        task.setStatus(TaskStatus.APPROVED);
        when(interviewService.latestSessionForTask(5L)).thenReturn(Optional.of(
                new InterviewService.LatestSession(42L, List.of(REF11.pinned(SHA, "2026-10-24T10:00+09:00")))));

        TaskResponse r = controller.get(5L, user);

        assertThat(r.refRepos()).hasSize(1);
        assertThat(r.refRepos().get(0).sha()).isNull();   // 인터뷰 단계 밖에서는 세션 참조를 쓰지 않는다
        assertThat(r.interviewSessionId()).isEqualTo(42L);
        verify(interviewService, times(1)).latestSessionForTask(5L);
    }

    @Test
    void list_shows_task_refs_only() {
        task.setStatus(TaskStatus.INTERVIEWING);

        TaskResponse r = controller.list(null, null, false, PageRequest.of(0, 20), user).getContent().get(0);

        assertThat(r.refRepos()).extracting(InterviewResponse.RefRepoView::catalogId).containsExactly(11L);
        assertThat(r.refRepos().get(0).sha()).isNull();
        verify(interviewService, never()).latestSessionForTask(any());
    }

    // ── 지금 인터뷰 세션의 superpowers 켬/끔 (설계 2026-11-03-interview-superpowers-toggle §4) ─────────────────

    @Test
    void 상세는_최신_세션값_세션없으면_null() {
        // 인터뷰 단계가 아니어도(승인대기 등) 최신 세션이 있으면 그 값을 싣는다
        task.setStatus(TaskStatus.AWAITING_APPROVAL);
        when(interviewService.latestSessionForTask(5L)).thenReturn(Optional.of(
                new InterviewService.LatestSession(9L, List.of(), false)));

        TaskResponse withSession = controller.get(5L, user);

        assertThat(withSession.interviewSuperpowersEnabled()).isFalse();
        assertThat(withSession.interviewSessionId()).isEqualTo(9L);
        // 세션 조회는 한 번이다 — id·참조·값을 같은 조회에서
        verify(interviewService, times(1)).latestSessionForTask(5L);
        verify(interviewService, never()).latestSessionIdForTask(any());

        when(interviewService.latestSessionForTask(5L)).thenReturn(Optional.empty());
        assertThat(controller.get(5L, user).interviewSuperpowersEnabled()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"INTERVIEWING", "INTERVIEW_INPUT", "INTERVIEW_REVIEW", "APPROVED", "AWAITING_APPROVAL"})
    void 켬_세션은_상태와_무관하게_켬으로_싣는다(TaskStatus st) {
        task.setStatus(st);
        when(interviewService.latestSessionForTask(5L)).thenReturn(Optional.of(
                new InterviewService.LatestSession(9L, List.of(), true)));

        assertThat(controller.get(5L, user).interviewSuperpowersEnabled()).isTrue();
    }

    @Test
    void 인터뷰_단계에서_참조를_세션것으로_바꿔도_끔_값이_남는다() {
        task.setStatus(TaskStatus.INTERVIEW_REVIEW);
        when(interviewService.latestSessionForTask(5L)).thenReturn(Optional.of(
                new InterviewService.LatestSession(42L, List.of(REF11.pinned(SHA, "2026-10-24T10:00+09:00")), false)));

        TaskResponse r = controller.get(5L, user);

        assertThat(r.refRepos()).extracting(InterviewResponse.RefRepoView::sha).containsExactly(SHA);
        assertThat(r.interviewSuperpowersEnabled()).isFalse();
    }

    @Test
    void 두_인자_LatestSession은_켬으로_읽는다() {
        assertThat(new InterviewService.LatestSession(42L, List.of()).superpowersEnabled()).isTrue();
        assertThat(new InterviewService.LatestSession(42L, List.of(), false).superpowersEnabled()).isFalse();
    }

    @Test
    void 비관리자_상세도_값이_남는다() {
        // 비관리자 응답은 redactSecretValues 사본을 거친다 — 그 사본이 끔 값을 잃으면 칩이 사라지고 설명이 틀린다
        task.setStatus(TaskStatus.INTERVIEW_REVIEW);
        when(interviewService.latestSessionForTask(5L)).thenReturn(Optional.of(
                new InterviewService.LatestSession(9L, List.of(), false)));

        TaskResponse viaGet = controller.get(5L, user);
        assertThat(viaGet.envVars()).extracting(EnvVar::value).containsExactly("");   // 사본을 실제로 거쳤다
        assertThat(viaGet.interviewSuperpowersEnabled()).isFalse();

        TaskResponse base = TaskResponse.of(task, null).withInterviewSuperpowersEnabled(false);
        assertThat(TaskController.forViewer(base, false).interviewSuperpowersEnabled()).isFalse();
        assertThat(TaskController.forViewer(base, true).interviewSuperpowersEnabled()).isFalse();
    }

    @Test
    void 목록은_값이_없다_세션을_읽지_않는다() {
        task.setStatus(TaskStatus.INTERVIEW_REVIEW);

        TaskResponse r = controller.list(null, null, false, PageRequest.of(0, 20), user).getContent().get(0);

        assertThat(r.interviewSuperpowersEnabled()).isNull();
        verify(interviewService, never()).latestSessionForTask(any());
    }
}
