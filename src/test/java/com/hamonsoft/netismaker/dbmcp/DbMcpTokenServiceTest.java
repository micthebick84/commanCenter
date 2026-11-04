package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbMcpToken;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskDbMcpTokenRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DbMcpTokenServiceTest {

    private final TaskDbMcpTokenRepository repo = mock(TaskDbMcpTokenRepository.class);
    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC);
    private final DbMcpTokenService svc = new DbMcpTokenService(repo, taskRepo, Duration.ofHours(2), clock);
    private Task task;

    @BeforeEach
    void setUp() {
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 42L);
        task.setStatus(TaskStatus.IMPLEMENTING);
        when(taskRepo.findById(42L)).thenReturn(Optional.of(task));
        when(repo.save(any())).thenAnswer(inv -> {
            TaskDbMcpToken t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 7L);
            return t;
        });
    }

    private TaskDbMcpToken issued(String token) {
        ArgumentCaptor<TaskDbMcpToken> cap = ArgumentCaptor.forClass(TaskDbMcpToken.class);
        verify(repo).save(cap.capture());
        TaskDbMcpToken row = cap.getValue();
        when(repo.findByTokenHash(DbMcpTokenService.sha256Hex(token))).thenReturn(Optional.of(row));
        when(repo.findFirstByTaskIdOrderByIdDesc(42L)).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void issue_stores_only_hash_with_stage_and_expiry() {
        String token = svc.issue(task);
        TaskDbMcpToken row = issued(token);
        assertThat(token).hasSizeGreaterThanOrEqualTo(43).doesNotContain("=");
        assertThat(row.getTokenHash()).isEqualTo(DbMcpTokenService.sha256Hex(token)).isNotEqualTo(token);
        assertThat(row.getStage()).isEqualTo("IMPLEMENTING");
        assertThat(row.getExpiresAt()).isEqualTo(OffsetDateTime.now(clock).plusHours(2));
    }

    @Test
    void authenticate_accepts_latest_token_in_same_stage() {
        String token = svc.issue(task);
        issued(token);
        assertThat(svc.authenticate(token)).contains(task);
    }

    @Test
    void authenticate_rejects_when_task_left_stage() {
        String token = svc.issue(task);
        issued(token);
        task.setStatus(TaskStatus.PR_CREATED);
        assertThat(svc.authenticate(token)).isEmpty();
    }

    @Test
    void older_token_is_rejected_after_reissue() {
        String token = svc.issue(task);
        TaskDbMcpToken row = issued(token);
        TaskDbMcpToken newer = TaskDbMcpToken.issue(42L, "other", "IMPLEMENTING",
                OffsetDateTime.now(clock), OffsetDateTime.now(clock).plusHours(2));
        ReflectionTestUtils.setField(newer, "id", row.getId() + 1);
        when(repo.findFirstByTaskIdOrderByIdDesc(42L)).thenReturn(Optional.of(newer));
        assertThat(svc.authenticate(token)).isEmpty();
    }

    @Test
    void expired_or_unknown_or_blank_token_is_rejected() {
        String token = svc.issue(task);
        TaskDbMcpToken row = issued(token);
        ReflectionTestUtils.setField(row, "expiresAt", OffsetDateTime.now(clock));
        assertThat(svc.authenticate(token)).isEmpty();
        assertThat(svc.authenticate("nope")).isEmpty();
        assertThat(svc.authenticate("")).isEmpty();
        assertThat(svc.authenticate(null)).isEmpty();
    }

    @Test
    void deleted_task_is_rejected() {
        String token = svc.issue(task);
        issued(token);
        task.setDeletedAt(OffsetDateTime.now(clock));
        assertThat(svc.authenticate(token)).isEmpty();
    }

    @Test
    void SDD_구현토큰은_상한더하기_30분() {
        // 구현 상한(SDD 240분)이 기본 TTL 2h보다 길면 토큰이 중간에 만료돼 401이 난다 (스펙 2026-11-03 §6.4)
        task.setImplementationTimeoutMinutes(240);
        String token = svc.issue(task);
        assertThat(issued(token).getExpiresAt()).isEqualTo(OffsetDateTime.now(clock).plusMinutes(270));
    }

    @Test
    void 구현상한더하기_여유가_기본TTL보다_짧으면_기본TTL() {
        task.setImplementationTimeoutMinutes(45);   // 직접 방식 45분 + 30분 < 2h
        String token = svc.issue(task);
        assertThat(issued(token).getExpiresAt()).isEqualTo(OffsetDateTime.now(clock).plusHours(2));
        assertThat(DbMcpTokenService.IMPLEMENTATION_TTL_MARGIN).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void 디자인토큰은_구현상한과_무관하게_기본TTL() {
        task.setStatus(TaskStatus.DESIGNING);
        task.setImplementationTimeoutMinutes(240);   // 값이 남아 있어도 디자인 토큰은 늘리지 않는다
        String token = svc.issue(task);
        TaskDbMcpToken row = issued(token);
        assertThat(row.getStage()).isEqualTo("DESIGNING");
        assertThat(row.getExpiresAt()).isEqualTo(OffsetDateTime.now(clock).plusHours(2));
    }

    @Test
    void 쓰기허가_왕복() {
        String token = svc.issue(task, true);
        TaskDbMcpToken row = issued(token);
        assertThat(row.isWriteGranted()).isTrue();
        Optional<DbMcpTokenService.TokenAuth> auth = svc.authenticateWithGrant(token);
        assertThat(auth).isPresent();
        assertThat(auth.get().task()).isSameAs(task);
        assertThat(auth.get().writeGranted()).isTrue();
        assertThat(svc.authenticate(token)).contains(task);
    }

    @Test
    void 인자_하나_발급은_쓰기허가_없음() {
        String token = svc.issue(task);
        assertThat(issued(token).isWriteGranted()).isFalse();
        assertThat(svc.authenticateWithGrant(token)).map(DbMcpTokenService.TokenAuth::writeGranted).contains(false);
    }

    @Test
    void 쓰기허가도_단계를_벗어나면_인증되지_않는다() {
        String token = svc.issue(task, true);
        issued(token);
        task.setStatus(TaskStatus.PR_CREATED);
        assertThat(svc.authenticateWithGrant(token)).isEmpty();
        assertThat(svc.authenticateWithGrant(null)).isEmpty();
        assertThat(svc.authenticateWithGrant("nope")).isEmpty();
    }

    @Test
    void revoke_all_deletes_every_token_of_the_task() {
        String token = svc.issue(task);
        issued(token);
        svc.revokeAll(42L);
        verify(repo).deleteByTaskId(42L);
        // 삭제 뒤에는 행이 없으므로 인증되지 않는다
        when(repo.findByTokenHash(DbMcpTokenService.sha256Hex(token))).thenReturn(Optional.empty());
        assertThat(svc.authenticate(token)).isEmpty();
    }
}
