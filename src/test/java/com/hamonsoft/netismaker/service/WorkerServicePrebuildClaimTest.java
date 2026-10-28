package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 배포 claim이 카탈로그의 미리 빌드 설정을 싣는다 (스펙 2026-10-28 §3). */
class WorkerServicePrebuildClaimTest {

    private TaskRepository taskRepo;
    private RepoCatalogRepository repoCatalogRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        repoCatalogRepo = mock(RepoCatalogRepository.class);
        var historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                repoCatalogRepo, historyRepo,
                mock(com.hamonsoft.netismaker.repository.TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(com.hamonsoft.netismaker.repository.InterviewSessionRepository.class),
                mock(com.hamonsoft.netismaker.repository.InterviewPlanRepository.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task deployPending(Long catalogId) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.DEPLOY_PENDING);
        t.setHeadBranch("netismaker/task-7");
        t.setHeadSha("abcdef1234567890");
        t.setRepoCatalogId(catalogId);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class))).thenReturn(List.of(t));
        return t;
    }

    private RepoCatalogEntry catalog(boolean enabled, boolean prebuild) {
        RepoCatalogEntry e = RepoCatalogEntry.create("앱", "https://github.com/owner/repo.git", "github",
                "owner/repo", "main", null, "admin");
        e.setEnabled(enabled);
        e.setPrebuildEnabled(prebuild);
        e.setPrebuildCommand("mvn -o package");
        e.setPrebuildArtifact("t/*.war");
        e.setPrebuildToolchain("jdk8-maven");
        return e;
    }

    private WorkerTaskResponse claim() {
        Optional<WorkerTaskResponse> claimed = service.claimNextTask("w1");
        assertThat(claimed).isPresent();
        assertThat(claimed.get().kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        return claimed.get();
    }

    @Test
    void 켜진_카탈로그면_spec을_싣는다() {
        deployPending(5L);
        when(repoCatalogRepo.findById(5L)).thenReturn(Optional.of(catalog(true, true)));

        var spec = claim().prebuild();

        assertThat(spec).isNotNull();
        assertThat(spec.command()).isEqualTo("mvn -o package");
        assertThat(spec.artifact()).isEqualTo("t/*.war");
        assertThat(spec.toolchain()).isEqualTo("jdk8-maven");
    }

    @Test
    void 꺼진_카탈로그면_null() {
        deployPending(5L);
        when(repoCatalogRepo.findById(5L)).thenReturn(Optional.of(catalog(true, false)));

        assertThat(claim().prebuild()).isNull();
    }

    @Test
    void 비활성_카탈로그도_싣는다() {
        deployPending(5L);
        when(repoCatalogRepo.findById(5L)).thenReturn(Optional.of(catalog(false, true)));

        assertThat(claim().prebuild()).isNotNull();
    }

    @Test
    void 카탈로그_id없음_또는_삭제면_null() {
        deployPending(null);   // 레거시: id·gitUrl 둘 다 없음
        assertThat(claim().prebuild()).isNull();

        deployPending(9L);
        when(repoCatalogRepo.findById(9L)).thenReturn(Optional.empty());
        assertThat(claim().prebuild()).isNull();

        // 삭제 뒤(id null) gitUrl로도 못 찾으면 끈다
        Task t = deployPending(null);
        t.setGitUrl("https://github.com/owner/gone.git");
        when(repoCatalogRepo.findByGitUrl("https://github.com/owner/gone.git")).thenReturn(Optional.empty());
        assertThat(claim().prebuild()).isNull();
    }

    @Test
    void 카탈로그_id가_비면_gitUrl로_다시_등록된_항목을_찾는다() {
        // repo_catalog_id FK는 ON DELETE SET NULL — 삭제 후 같은 URL로 다시 등록하면 git_url로 찾힌다
        Task t = deployPending(null);
        t.setGitUrl("https://github.com/owner/repo.git");
        when(repoCatalogRepo.findByGitUrl("https://github.com/owner/repo.git")).thenReturn(Optional.of(catalog(true, true)));

        var spec = claim().prebuild();

        assertThat(spec).isNotNull();
        assertThat(spec.command()).isEqualTo("mvn -o package");
        assertThat(spec.artifact()).isEqualTo("t/*.war");
        assertThat(spec.toolchain()).isEqualTo("jdk8-maven");
    }
}
