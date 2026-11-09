package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dbmcp.DbWriteGate;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.DbWriteSpec;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.WriteClassification;
import com.hamonsoft.netismaker.repository.InterviewPlanRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDbWriteLogRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 구현 claim의 개발 DB 쓰기 재료 DbWriteSpec (스펙 2026-11-03 §6.6·§6.8) — 스크립트 경로(카탈로그 폴더 또는 기본 폴더)·
 * 자동 마이그레이션 표시(DbWriteGate)·이전 쓰기 기록(EXECUTED·DEFERRED) 유무.
 * 판정기·기록 저장소는 setter 주입이라 안 부른 단위 시험에서는 둘 다 false로 읽는다.
 */
class WorkerServiceDbWriteSpecTest {

    private TaskRepository taskRepo;
    private RepoCatalogRepository catalogs;
    private TaskDbWriteLogRepository logs;
    private DbWriteGate gate;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        catalogs = mock(RepoCatalogRepository.class);
        logs = mock(TaskDbWriteLogRepository.class);
        gate = mock(DbWriteGate.class);
        service = newService();
        service.setDbWriteLogRepository(logs);
        service.setDbWriteGate(gate);
    }

    private WorkerService newService() {
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        return new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                catalogs, historyRepo, mock(TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
    }

    private Task task(Long catalogId) {
        Task t = Task.create("a/b", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(TaskStatus.APPROVED);
        t.setRepoCatalogId(catalogId);
        return t;
    }

    private RepoCatalogEntry catalog(String scriptDir) {
        RepoCatalogEntry e = RepoCatalogEntry.create("앱", "https://github.com/a/b", "github", "a/b", "main", null, "admin");
        ReflectionTestUtils.setField(e, "id", 1L);
        e.setDbScriptDir(scriptDir);
        when(catalogs.findById(1L)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void 구현claim은_스크립트경로_자동마이그레이션_이전쓰기() {
        catalog("sql/dev");
        Task t = task(1L);
        when(gate.migrationManaged(t)).thenReturn(true);
        when(logs.existsByTaskIdAndClassificationIn(eq(42L), anyCollection())).thenReturn(true);

        assertThat(service.dbWriteSpecFor(t)).isEqualTo(new DbWriteSpec("sql/dev/task-42.sql", true, true));
    }

    @Test
    void 카탈로그_폴더가_없으면_기본_폴더() {
        // 카탈로그 항목이 없는 작업(레거시)·폴더 칸이 빈 항목 모두
        assertThat(service.dbWriteSpecFor(task(null))).isEqualTo(new DbWriteSpec("db/symphony/task-42.sql", false, false));
        catalog(null);
        assertThat(service.dbWriteSpecFor(task(1L))).isEqualTo(new DbWriteSpec("db/symphony/task-42.sql", false, false));
    }

    @Test
    void 이전_쓰기는_실행됐거나_승인대기인_기록만_센다() {
        Task t = task(null);
        when(logs.existsByTaskIdAndClassificationIn(anyLong(), anyCollection())).thenReturn(false);

        service.dbWriteSpecFor(t);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<WriteClassification>> kinds = ArgumentCaptor.forClass(Collection.class);
        verify(logs).existsByTaskIdAndClassificationIn(eq(42L), kinds.capture());
        // REJECTED·FAILED·DISMISSED는 반영된 변경이 아니다(스크립트 파일에도 실리지 않는다)
        assertThat(kinds.getValue()).containsExactlyInAnyOrder(WriteClassification.EXECUTED, WriteClassification.DEFERRED);
    }

    @Test
    void 기록이_있으면_priorWrites_자동마이그레이션이_아니면_false() {
        Task t = task(null);
        when(logs.existsByTaskIdAndClassificationIn(anyLong(), anyCollection())).thenReturn(true);
        when(gate.migrationManaged(t)).thenReturn(false);

        assertThat(service.dbWriteSpecFor(t)).isEqualTo(new DbWriteSpec("db/symphony/task-42.sql", false, true));
    }

    @Test
    void 판정기와_기록_저장소가_없으면_둘_다_false() {
        // setter를 안 부른 단위 시험 — claim이 깨지지 않고 경로만 계산한다
        WorkerService bare = newService();
        catalog("sql/dev");

        assertThat(bare.dbWriteSpecFor(task(1L))).isEqualTo(new DbWriteSpec("sql/dev/task-42.sql", false, false));
        verifyNoInteractions(logs, gate);
    }

    @Test
    void 구현_claim_응답의_구현_블록에_db가_실린다() {
        catalog("sql/dev");
        Task t = task(1L);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class))).thenReturn(List.of(t));
        when(gate.migrationManaged(t)).thenReturn(true);
        when(logs.existsByTaskIdAndClassificationIn(anyLong(), anyCollection())).thenReturn(true);

        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(r.implementation().db()).isEqualTo(new DbWriteSpec("sql/dev/task-42.sql", true, true));
    }

    @Test
    void 구현이_아닌_claim은_쓰기_재료를_읽지_않는다() {
        Task t = task(null);
        t.setStatus(TaskStatus.DEPLOY_PENDING);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class))).thenReturn(List.of(t));

        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        verifyNoInteractions(logs, gate);
    }

    @Test
    void 지금_구현의_프롬프트_재료는_그대로_둔다() {
        // 쓰기 재료가 늘어도 mode·attempt·timeout·taskType은 지금과 같다
        Task t = task(null);
        t.setImplementationAttempt(2);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class))).thenReturn(List.of(t));

        WorkerTaskResponse.ImplementationSpec spec = service.claimNextTask("w1").orElseThrow().implementation();

        assertThat(spec.mode()).isEqualTo("DIRECT");
        assertThat(spec.attempt()).isEqualTo(2);
        assertThat(spec.taskType()).isNull();
        assertThat(spec.fix()).isNull();
        assertThat(spec.db()).isEqualTo(new DbWriteSpec("db/symphony/task-42.sql", false, false));
    }
}
