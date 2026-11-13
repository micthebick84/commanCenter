package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dbmcp.DbWriteGate;
import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.dto.CloseArtifactSpec;
import com.hamonsoft.netismaker.dto.CloseContext;
import com.hamonsoft.netismaker.dto.ClosePatchSpec;
import com.hamonsoft.netismaker.dto.CloseProgressRequest;
import com.hamonsoft.netismaker.dto.DeployedTaskSummary;
import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerRuntimeStatusRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.repository.InterviewPlanRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDbWriteLogRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import com.hamonsoft.netismaker.util.DbScriptDir;
import com.hamonsoft.netismaker.util.NoteFolderPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 *  워커 API 비즈니스 로직.
 *
 *   heartbeat       ─► worker_heartbeat upsert
 *   claimNextTask   ─► PENDING/APPROVED 작업 1건 atomic claim (SKIP LOCKED)
 *                       PENDING         → IN_PROGRESS  (kind=ANALYSIS)
 *                       APPROVED        → IMPLEMENTING  (kind=IMPLEMENTATION, analysis 동봉)
 *                       DESIGN_PENDING  → DESIGNING     (kind=DESIGN, 이전 디자인/피드백 동봉)
 *                       DEPLOY_PENDING  → DEPLOYING     (kind=DEPLOY)
 *                       UNDEPLOY_PENDING → UNDEPLOYING  (kind=UNDEPLOY)
 *                       CLOSE_PENDING   → CLOSING       (kind=CLOSE, 종료 노트 재료·진행 단계 동봉)
 *   recordResult    ─► 워커가 분석/구현/배포/디자인 완료/실패 보고. worker_id 일치 필수.
 *                       COMPLETED           : task_analysis upsert + status=COMPLETED
 *                       FAILED              : failure_reason + status=FAILED
 *                       PR_CREATED          : pr_url/pr_number/head_* + status=PR_CREATED
 *                                             (수정 회차면 head_sha만 새 끝으로 → 자동 재배포 DEPLOY_PENDING, 2026-11-03 §7)
 *                       IMPLEMENTATION_FAILED: failure_reason + (있으면)log + status=IMPLEMENTATION_FAILED
 *                       DEPLOYED/DEPLOY_FAILED: 배포 결과 — 수정 회차가 열려 있으면 회차를 닫는다(FixRounds.closeActive),
 *                                             DEPLOYED가 빌드한 커밋(headSha)을 실으면 head_sha로 기록
 *                       DESIGN_REVIEW       : task_design upsert(반려 이력 보존) + status=DESIGN_REVIEW
 *                       DESIGN_FAILED       : failure_reason + status=DESIGN_FAILED
 *                       CLOSED              : 이 작업에 필요한 종료 단계(CloseStep.required)가 모두 보고된 뒤에만 (status=CLOSED)
 *                       CLOSE_FAILED        : failure_reason + status=CLOSE_FAILED
 *   recordCloseProgress ─► 종료 단계 완료 보고를 task.close_progress에 누적 (멱등)
 */
@Service
@Profile("api")
public class WorkerService {

    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

    /** 종료 노트 재료로 싣는 상태 이력 상한(최신 N건). 본문 길이 상한은 CloseContext가 가진다. */
    private static final int CLOSE_HISTORY_LIMIT = 200;
    /** task.merge_commit_sha / close_note_path 컬럼 길이 — 넘으면 500 대신 400으로 거절. */
    private static final int MAX_COMMIT_SHA_LENGTH = 40;
    private static final int MAX_NOTE_PATH_LENGTH = 500;
    /** 참조 repo 변경 경고 이력 행의 글자 수 상한 (스펙 2026-10-24 §4.8 — DbMcpGrantService의 같은 상태 행과 같은 500자). */
    private static final int REF_WARNING_ROW_MAX_LENGTH = 500;

    /** 메일·노트 링크 기준 주소 (스펙 2026-10-14 §4). 단위 테스트에서는 null. */
    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    private final TaskRepository taskRepo;
    private final TaskAnalysisRepository analysisRepo;
    private final TaskDesignRepository designRepo;
    private final RepoCatalogRepository repoCatalogRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final TaskStageUsageRepository stageUsageRepo;
    private final WorkerHeartbeatRepository heartbeatRepo;
    private final DeployLogStreamService deployLogStream;
    private final InterviewSessionRepository sessionRepo;
    private final InterviewPlanRepository planRepo;

    /** 작업종료 → 커밋 뒤 메일(CloseMailListener). 생성자를 바꾸지 않으려고 setter 주입 — 단위 테스트에서는 null. */
    private ApplicationEventPublisher events;

    @Autowired
    void setEvents(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** 방식별 구현 상한 (스펙 2026-10-15 §5.4). 생성자를 바꾸지 않으려고 setter 주입 — 단위 테스트에서는 기본값. */
    private ImplementationModePolicy implementationModePolicy = ImplementationModePolicy.defaults();

    @Autowired
    void setImplementationModePolicy(ImplementationModePolicy policy) {
        this.implementationModePolicy = policy;
    }

    /** 수정 회차 claim의 요청 기록 조회 (스펙 2026-11-03 §7). 생성자를 바꾸지 않으려고 setter 주입 — 회차 없는 단위 테스트에서는 null. */
    private TaskFixRequestRepository fixRequestRepo;

    @Autowired
    void setFixRequestRepository(TaskFixRequestRepository fixRequestRepo) {
        this.fixRequestRepo = fixRequestRepo;
    }

    /**
     * 구현 claim의 개발 DB 쓰기 재료 — 이전 쓰기 기록 유무(스펙 2026-11-03 §6.6). 생성자를 바꾸지 않으려고 setter 주입 —
     * 안 부른 단위 시험에서는 null이고 이전 기록 없음으로 읽는다.
     */
    private TaskDbWriteLogRepository dbWriteLogRepo;

    @Autowired
    void setDbWriteLogRepository(TaskDbWriteLogRepository dbWriteLogRepo) {
        this.dbWriteLogRepo = dbWriteLogRepo;
    }

    /** 구현 claim의 개발 DB 쓰기 재료 — 자동 마이그레이션 레포 표시(§6.8). setter 주입 — 안 부른 단위 시험에서는 null이고 false로 읽는다. */
    private DbWriteGate dbWriteGate;

    @Autowired
    void setDbWriteGate(DbWriteGate dbWriteGate) {
        this.dbWriteGate = dbWriteGate;
    }

    /**
     * 구현·디자인 claim의 인터뷰 목업 파일 (스펙 2026-11-03 §7). 생성자를 바꾸지 않으려고 setter 주입 — 안 부른 단위 시험에서는 null이고
     * 목업 없이 응답한다.
     */
    private InterviewMockupService interviewMockupService;

    @Autowired(required = false)
    void setInterviewMockupService(InterviewMockupService interviewMockupService) {
        this.interviewMockupService = interviewMockupService;
    }

    public WorkerService(TaskRepository taskRepo,
                         TaskAnalysisRepository analysisRepo,
                         TaskDesignRepository designRepo,
                         RepoCatalogRepository repoCatalogRepo,
                         TaskStatusHistoryRepository historyRepo,
                         TaskStageUsageRepository stageUsageRepo,
                         WorkerHeartbeatRepository heartbeatRepo,
                         DeployLogStreamService deployLogStream,
                         InterviewSessionRepository sessionRepo,
                         InterviewPlanRepository planRepo) {
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.designRepo = designRepo;
        this.repoCatalogRepo = repoCatalogRepo;
        this.historyRepo = historyRepo;
        this.stageUsageRepo = stageUsageRepo;
        this.heartbeatRepo = heartbeatRepo;
        this.deployLogStream = deployLogStream;
        this.sessionRepo = sessionRepo;
        this.planRepo = planRepo;
    }

    @Transactional
    public void heartbeat(WorkerHeartbeatRequest req) {
        WorkerHeartbeat h = heartbeatRepo.findById(req.workerId()).orElseGet(() -> {
            WorkerHeartbeat fresh = new WorkerHeartbeat();
            fresh.setWorkerId(req.workerId());
            return fresh;
        });
        h.setHostname(req.hostname());
        h.setVersion(req.version());
        h.setClaudeSessionOk(req.claudeSessionOk());
        h.setClaudeAuthMethod(req.claudeAuthMethod());
        h.setClaudeAuthExpiresAt(req.claudeAuthExpiresAt());
        h.setVpnStatus(req.vpnStatus());
        h.setMcps(req.mcps() == null ? List.of() : req.mcps());
        h.setLostReportCount(req.lostReportCount() == null ? 0 : req.lostReportCount());
        h.setDeadReportCount(req.deadReportCount() == null ? 0 : req.deadReportCount());
        h.setLastSeenAt(OffsetDateTime.now());
        heartbeatRepo.save(h);
    }

    /**
     * PENDING(분석) 또는 APPROVED(구현) 1건을 atomic claim. 없으면 Optional.empty.
     * SELECT FOR UPDATE SKIP LOCKED. 같은 워커가 분석/구현 큐를 공유 (V1 직렬).
     *
     * 반환된 WorkerTaskResponse.kind를 보고 워커가 분기:
     *  - ANALYSIS       → 기존 분석 흐름 (analysis 필드 null)
     *  - IMPLEMENTATION → worktree 구현 흐름 (analysis 마크다운 + subtasks 동봉)
     *
     * 1인자 = 모든 기능을 아는 워커(거르는 조건 없음).
     */
    @Transactional
    public Optional<WorkerTaskResponse> claimNextTask(String workerId) {
        return claimNextTask(workerId, WorkerCapabilities.ALL, null);
    }

    /**
     * supportsRefRepos=false(구버전 워커 — next-task에 supports=refRepos 없음)면 참조 repo가 있는 구현대기를 건너뛴다
     * (스펙 2026-10-24 §4.7) — 모르는 필드를 버리고 참조 없이 구현해 버리지 않게. 그 작업은 대기로 남는다.
     * 이 오버로드는 fixRounds를 모르는 워커로 본다(수정 회차 구현대기도 건너뜀) — 둘 다 알리는 워커는
     * {@link #claimNextTask(String, WorkerCapabilities, Set)}를 쓴다.
     */
    @Transactional
    public Optional<WorkerTaskResponse> claimNextTask(String workerId, boolean supportsRefRepos) {
        return claimNextTask(workerId, new WorkerCapabilities(supportsRefRepos, false), null);
    }

    /**
     * 받을 작업 종류를 고른 워커(next-task kinds)용 claim — 그 종류의 대기 상태만 후보로 같은 FIFO 순서로 잡는다.
     * 다른 PC의 워커가 이 PC에만 있는 볼트(종료 노트)·미리 빌드 도구(배포)가 필요한 일을 잡지 않게 한다.
     * fixRounds를 모르는 워커로 본다 — {@link #claimNextTask(String, boolean)} 참고.
     */
    @Transactional
    public Optional<WorkerTaskResponse> claimNextTask(String workerId, boolean supportsRefRepos,
                                                      Set<WorkerTaskResponse.Kind> kinds) {
        return claimNextTask(workerId, new WorkerCapabilities(supportsRefRepos, false), kinds);
    }

    /**
     * 워커가 알린 기능(caps)에 맞춰 claim — 모르는 기능이 필요한 구현대기는 건너뛴다(참조 repo가 있는 것 §4.7, 수정 회차가 열린 것
     * 2026-11-03 §7). 구버전 jar는 그런 작업의 모르는 필드를 버리고 엉뚱하게 구현하기 때문이다. 어느 경로(종류를 골랐든 아니든)에서도
     * 같은 조건이 걸린다. kinds가 null이면 전부, 아니면 그 종류의 대기 상태만 후보로 같은 FIFO 순서로 잡는다.
     * 두 기능을 다 알고 종류도 안 골랐으면 거를 것이 없어 기존 전체 큐(JPQL)를 그대로 쓴다.
     */
    @Transactional
    public Optional<WorkerTaskResponse> claimNextTask(String workerId, WorkerCapabilities caps,
                                                      Set<WorkerTaskResponse.Kind> kinds) {
        List<Task> candidates;
        if (kinds == null) {
            candidates = caps.needsFilteredQueue()
                    ? taskRepo.findClaimableAmongForUpdateSkipLocked(TaskRepository.CLAIMABLE_STATUSES,
                            caps.refRepos(), caps.fixRounds(), caps.mockups())
                    : taskRepo.findClaimableForUpdateSkipLocked(PageRequest.of(0, 1));
        } else {
            List<TaskStatus> statuses = kinds.stream().map(WorkerService::claimableStatusFor).toList();
            candidates = taskRepo.findClaimableAmongForUpdateSkipLocked(statuses, caps.refRepos(), caps.fixRounds(),
                    caps.mockups());
        }
        // claim이 null이면(수정 회차 기록이 깨진 작업을 구현실패로 치웠다) 이번 poll은 빈 응답 — 큐 머리가 치워졌으니 다음 poll이 뒤를 잡는다
        return candidates.isEmpty() ? Optional.empty() : Optional.ofNullable(claim(candidates.get(0), workerId));
    }

    /** 작업 종류 → 워커가 그 종류를 claim하는 대기 상태 ({@link TaskRepository#CLAIMABLE_STATUSES}의 한 값). */
    static TaskStatus claimableStatusFor(WorkerTaskResponse.Kind kind) {
        return switch (kind) {
            case ANALYSIS -> TaskStatus.PENDING;
            case IMPLEMENTATION -> TaskStatus.APPROVED;
            case DESIGN -> TaskStatus.DESIGN_PENDING;
            case DEPLOY -> TaskStatus.DEPLOY_PENDING;
            case UNDEPLOY -> TaskStatus.UNDEPLOY_PENDING;
            case CLOSE -> TaskStatus.CLOSE_PENDING;
        };
    }

    /**
     * 후보 1건을 claim해 응답을 만든다. 수정 회차 기록이 깨진 구현대기는 claim하지 않고 구현실패로 치운 뒤 null을 돌려준다.
     */
    private WorkerTaskResponse claim(Task t, String workerId) {
        TaskStatus from = t.getStatus();

        // 수정 회차가 열린 구현대기 — 회차의 방식·본문·브랜치를 싣는다 (스펙 2026-11-03 §7). 다른 상태의 회차 표시(자동 재배포 등)는 보지 않는다.
        OpenFixRound round = null;
        if (from == TaskStatus.APPROVED && t.getActiveFixRequestId() != null) {
            round = openFixRoundOf(t);
            if (round == null) {
                failBrokenFixRound(t);
                return null;
            }
        }

        if (from == TaskStatus.PENDING) {
            t.setStatus(TaskStatus.IN_PROGRESS);
        } else if (from == TaskStatus.APPROVED) {
            t.setStatus(TaskStatus.IMPLEMENTING);
            // 이번 시도의 상한을 기록 — claim 응답과 작업 화면이 같은 값을 쓴다 (스펙 2026-10-15 §5.3).
            // 수정 회차는 회차의 방식으로 정한다 — 방식은 작업이 아니라 요청 기록에 있다 (스펙 2026-11-03 §6.2)
            t.setImplementationTimeoutMinutes(implementationModePolicy.timeoutMinutes(
                    round != null ? round.mode() : t.getImplementationMode()));
        } else if (from == TaskStatus.DESIGN_PENDING) {
            t.setStatus(TaskStatus.DESIGNING);
        } else if (from == TaskStatus.DEPLOY_PENDING) {
            t.setStatus(TaskStatus.DEPLOYING);
        } else if (from == TaskStatus.UNDEPLOY_PENDING) {
            t.setStatus(TaskStatus.UNDEPLOYING);
        } else if (from == TaskStatus.CLOSE_PENDING) {
            t.setStatus(TaskStatus.CLOSING);
        } else {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "claim 후보가 처리 가능한 상태가 아님: " + from);
        }
        t.setWorkerId(workerId);
        t.setClaimedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", workerId, "워커 claim"));

        if (t.getStatus() == TaskStatus.DESIGNING) {
            TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
            TaskDesign prev = designRepo.findById(t.getId()).orElse(null);
            String dsProjectId = null;
            String outProjectId = null;
            if (t.getRepoCatalogId() != null) {
                var cat = repoCatalogRepo.findById(t.getRepoCatalogId()).orElse(null);
                if (cat != null) {
                    dsProjectId = cat.getDesignSystemProjectId();
                    outProjectId = cat.getDesignOutputProjectId();
                }
            }
            return withInterviewMockups(t, WorkerTaskResponse.forDesign(t, a, prev, dsProjectId, outProjectId));
        }
        if (t.getStatus() == TaskStatus.IMPLEMENTING) {
            TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
            TaskDesign d = designRepo.findById(t.getId()).orElse(null);
            return withInterviewMockups(t, WorkerTaskResponse.forImplementation(t, a,
                    (d != null && d.isApproved()) ? d : null, registeredPlanMarkdown(t.getId()),
                    round == null ? null : round.spec(), round == null ? null : round.mode(), dbWriteSpecFor(t)));
        }
        if (from == TaskStatus.DEPLOY_PENDING) {
            return WorkerTaskResponse.forDeploy(t, prebuildFor(t));
        }
        if (from == TaskStatus.UNDEPLOY_PENDING) {
            return WorkerTaskResponse.forUndeploy(t);
        }
        if (from == TaskStatus.CLOSE_PENDING) {
            return WorkerTaskResponse.forClose(t, closeContext(t), closeArtifactSpec(t), closePatchSpec(t));
        }
        return WorkerTaskResponse.forAnalysis(t);
    }

    /**
     * 구현(수정 회차 포함)·디자인 claim에 인터뷰 목업 파일과 '전달되지 않은' 번호를 싣는다 (스펙 2026-11-03 §7). 고른 목업도 제외 번호도
     * 없으면 응답을 그대로 둔다(빈 목록이 기본). 모두 해제한 작업(ids 빔·제외 번호만)은 파일 조회 없이 제외 번호만 싣는다.
     * ids 원소는 JSONB에서 Integer로 올 수 있어(Number로 읽는다) filesFor가 longValue로 바꾼다.
     */
    private WorkerTaskResponse withInterviewMockups(Task t, WorkerTaskResponse r) {
        if (interviewMockupService == null) return r;
        List<? extends Number> ids = t.getImplementationMockupIds();
        List<Integer> excluded = t.getImplementationMockupExcluded();
        boolean noIds = ids == null || ids.isEmpty();
        boolean noExcluded = excluded == null || excluded.isEmpty();
        if (noIds && noExcluded) return r;
        List<WorkerTaskResponse.InterviewMockupFile> files = noIds ? List.of() : interviewMockupService.filesFor(ids);
        return r.withInterviewMockups(files, noExcluded ? List.of() : List.copyOf(excluded));
    }

    /** claim 때 읽은 열린 수정 회차 — 회차의 구현 방식과 워커에 보낼 지시. */
    private record OpenFixRound(ImplementationMode mode, WorkerTaskResponse.FixRoundSpec spec) {}

    /**
     * 작업이 가리키는 열린 수정 회차. 승인된 요청(회차 번호 있음)이어야 하고, 아니면 null — 데이터가 깨진 것이다.
     * 이전 본문은 승인된 앞 회차(round 미만)의 요청 본문, 오래된 회차부터. 구현 방식이 비어 있으면(있을 수 없다) 작업 방식이 아니라
     * 리뷰로 본다 — 수정 회차는 직접·리뷰만이라 작업 방식(SDD일 수 있다)으로 떨어지면 원래 플랜 전체를 다시 구현한다(§6.2).
     */
    private OpenFixRound openFixRoundOf(Task t) {
        TaskFixRequest r = FixRounds.openRequest(fixRequestRepo, t);
        if (r == null) return null;
        List<String> previous = FixRounds.isBaseSync(r) ? List.of() : fixRequestRepo
                .findByTaskIdAndStatusAndKindAndRoundLessThanOrderByRoundAsc(t.getId(), FixRequestStatus.APPROVED,
                        FixRequestKind.FIX, r.getRound())
                .stream().map(TaskFixRequest::getBody).toList();
        ImplementationMode mode = FixRounds.modeOf(r);
        return new OpenFixRound(mode, new WorkerTaskResponse.FixRoundSpec(r.getId(), r.getRound(), r.getAttempt(),
                r.getBody(), previous, t.getHeadBranch(), t.getHeadSha(), r.getKind().name()));
    }

    /**
     * 회차 표시는 있는데 승인된 요청 기록이 없는 구현대기 — 이대로 claim하면 회차 없는 구현으로 가서 base에서 -rN 브랜치를
     * 새로 따 버린다. 워커에게 내주지 않고 구현실패로 두어 사람이 보게 하고, 큐 머리를 막지 않는다.
     */
    private void failBrokenFixRound(Task t) {
        TaskStatus from = t.getStatus();
        String reason = "수정 회차 기록을 찾을 수 없어 구현을 시작하지 않았습니다 (회차 요청 #" + t.getActiveFixRequestId() + ")";
        log.error("claim 거부: 작업 {}의 수정 회차 요청 #{} 기록이 없거나 승인 상태가 아님", t.getId(), t.getActiveFixRequestId());
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setFailureReason(reason);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(), "system", null, reason));
    }

    /** 종료 노트 재료 — 확정 플랜·분석·구현 로그 끝·이력(시간순)·단계별 비용. 본문 상한은 CloseContext.of. */
    private CloseContext closeContext(Task t) {
        TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
        List<TaskStatusHistory> history = new ArrayList<>(
                historyRepo.findByTaskIdOrderByAtDescIdDesc(t.getId(), Limit.of(CLOSE_HISTORY_LIMIT)));
        Collections.reverse(history); // 시간순(오래된 것 먼저)
        RepoCatalogEntry cat = RepoCatalogRepository.findForTask(repoCatalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                .orElse(null);
        return CloseContext.of(t, registeredPlanMarkdown(t.getId()), a, history,
                stageUsageRepo.findByTaskIdOrderByStageAsc(t.getId()), CloseLinks.taskUrl(publicBaseUrl, t.getId()), cat);
    }

    /**
     * 배포 claim에 싣는 미리 빌드 지시 (스펙 2026-10-28 §3). 카탈로그 enabled(등록 허용)와 무관하게 prebuildEnabled만 본다 —
     * 이미 승인된 작업의 배포가 카탈로그 비활성으로 달라지지 않게. 카탈로그는 closeArtifactSpec과 같이 id 우선, 없으면 git_url로
     * 찾는다(FK가 ON DELETE SET NULL이라 삭제 뒤 id가 비고, 다시 등록한 항목은 git_url로 찾힌다). 못 찾으면 끄고(예전 동작) —
     * 카탈로그 id·git_url이 둘 다 없는 레거시 작업이 아니면 경고만 남긴다.
     */
    private WorkerTaskResponse.PrebuildSpec prebuildFor(Task t) {
        RepoCatalogEntry cat = RepoCatalogRepository.findForTask(repoCatalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                .orElse(null);
        if (cat == null) {
            if (t.getRepoCatalogId() != null || t.getGitUrl() != null) {
                log.warn("배포 claim: 카탈로그 항목 없음(id={}, gitUrl={}) — 미리 빌드 끔", t.getRepoCatalogId(), t.getGitUrl());
            }
            return null;
        }
        if (!cat.isPrebuildEnabled()) return null;
        return new WorkerTaskResponse.PrebuildSpec(cat.getPrebuildCommand(), cat.getPrebuildArtifact(), cat.getPrebuildToolchain());
    }

    /** 이전 시도·회차가 반영했거나 승인 대기로 남긴 SQL — 스크립트 파일에 실리는 분류와 같은 집합(§6.6). REJECTED·FAILED·DISMISSED는 변경이 아니다. */
    private static final Set<WriteClassification> PRIOR_WRITE_CLASSIFICATIONS =
            EnumSet.of(WriteClassification.EXECUTED, WriteClassification.DEFERRED);

    /**
     * 구현 claim에 싣는 개발 DB 쓰기 재료 (스펙 2026-11-03 §6.6·§6.8). 스크립트 경로는 카탈로그 폴더(없으면 기본 `db/symphony`)의
     * `task-{id}.sql`이고, 카탈로그는 claim의 다른 보강과 같이 id 우선·없으면 git_url로 찾는다(못 찾으면 기본 폴더).
     * 자동 마이그레이션 표시는 쓰기 판정과 같은 규칙(DbWriteGate.migrationManaged), priorWrites는 claim 시점에 이미 있는
     * 기록 — 이번 시도는 아직 시작 전이라 이전 시도·회차 것만 본다. 판정기·기록 저장소가 없으면(단위 시험) 각각 false.
     * 쓰기 가능 여부(연결별 writable)는 이 재료가 아니라 DB MCP grant가 정한다.
     */
    WorkerTaskResponse.DbWriteSpec dbWriteSpecFor(Task t) {
        String dir = RepoCatalogRepository.findForTask(repoCatalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                .map(RepoCatalogEntry::getDbScriptDir).orElse(null);
        boolean migrationManaged = dbWriteGate != null && dbWriteGate.migrationManaged(t);
        boolean priorWrites = dbWriteLogRepo != null
                && dbWriteLogRepo.existsByTaskIdAndClassificationIn(t.getId(), PRIOR_WRITE_CLASSIFICATIONS);
        return new WorkerTaskResponse.DbWriteSpec(DbScriptDir.resolve(dir, t.getId()), migrationManaged, priorWrites);
    }

    /** 패치 모드가 REQUIRED이고 PATCH_BUILT 전일 때만 지시를 싣는다 — 워커는 null이면 패치 단계를 건너뛴다 (스펙 2026-11-10). */
    private ClosePatchSpec closePatchSpec(Task t) {
        if (!CloseStep.PATCH_REQUIRED.equals(t.getClosePatchMode())) return null;
        if (CloseStep.parse(t.getCloseProgress()).contains(CloseStep.PATCH_BUILT)) return null;
        RepoCatalogEntry cat = RepoCatalogRepository.findForTask(repoCatalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                .orElse(null);
        if (cat == null) return new ClosePatchSpec(null, null, null, t.getPatchBaseSha());
        return new ClosePatchSpec(cat.getPrebuildCommand(), cat.getPrebuildToolchain(), cat.getPrebuildArtifact(),
                t.getPatchBaseSha());
    }

    /** 산출물 모드가 REQUIRED이고 아직 안 올렸을 때만 지시를 싣는다 — 워커는 null이면 단계를 건너뛴다. */
    private CloseArtifactSpec closeArtifactSpec(Task t) {
        if (!CloseStep.ARTIFACT_REQUIRED.equals(t.getCloseArtifactMode())) return null;
        if (CloseStep.parse(t.getCloseProgress()).contains(CloseStep.ARTIFACT_UPLOADED)) return null;
        String path = RepoCatalogRepository.findForTask(repoCatalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                .map(RepoCatalogEntry::getArtifactPath).map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        return new CloseArtifactSpec(path);
    }

    /**
     * 작업을 확정(confirm)한 인터뷰의 플랜 본문. confirm은 task_analysis에 design만 넣으므로(LOCKED CONTRACT v2)
     * 플랜 본문은 interview_plan에서 가져온다. 확정된 세션은 REGISTERED — 취소·만료된 재인터뷰 세션은 건너뛴다.
     * 인터뷰 없이 온 작업(레거시 자동분석)이면 null.
     */
    private String registeredPlanMarkdown(Long taskId) {
        return sessionRepo.findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .filter(s -> s.getStatus() == InterviewStatus.REGISTERED)
                .findFirst()
                .flatMap(s -> planRepo.findById(s.getId()))
                .map(InterviewPlan::getPlanMarkdown)
                .orElse(null);
    }

    @Transactional
    public void recordResult(Long taskId, WorkerResultRequest req) {
        // 비관적 락(FOR UPDATE) + findActive — ① 결과 보고를 admin 진입점(deploy/undeploy/
        // rejectDesign 등)·claim과 직렬화해 stale 스냅샷 기반 이중 전이/lost update를 막고
        // ② 소프트삭제된 task의 지각 보고(dead-letter replay 포함)가 삭제 row를 변이시키지
        // 않게 한다 — 404는 워커 쪽에서 4xx 영구 거부로 dead-letter에 보존된다.
        Task t = taskRepo.findActiveByIdForUpdate(taskId)
                .orElseThrow(TaskException::notFound);

        TaskStatus current = t.getStatus();

        // 재시도 뒤 이전 시도의 늦은 보고(스펙 2026-10-15 §5.6) — 상태는 그대로 두고 이력·사용량만 남긴다.
        // 지각 정합화·in-flight 가드보다 먼저 본다(이전 시도가 새 시도의 상태를 덮거나 409로 dead-letter에 쌓이지 않게).
        if (isEarlierImplementationAttempt(t, req)) {
            historyRepo.save(TaskStatusHistory.log(t.getId(), current, current, "worker", req.workerId(),
                    "이전 시도(" + req.implementationAttempt() + "회째)의 늦은 보고 무시: " + req.status().dbValue()
                            + ignoredWorkDetail(req)));
            accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            return;
        }

        // 수정 회차의 구현 결과(스펙 2026-11-03 §7) — 회차 표지(fixAttempt·fixRequestId)를 단 보고는 어느 상태에서든, 표지가 없는
        // 구현 결과는 회차 표시가 있을 때 회차 규칙으로만 처리한다. 아래 지각 정합화(→ PR생성)나 배포 단계의 PR생성(= 배포 중지 성공)으로
        // 새면 회차가 열린 채 갇히거나 배포 중지로 읽힌다. 배포중지중에 온 표지 없는 PR생성만 배포 중지 결과로 아래로 보낸다.
        if (isImplementationResult(req)
                && (isFixRoundReport(req) || (t.getActiveFixRequestId() != null && current != TaskStatus.UNDEPLOYING))) {
            recordFixRoundImplementationResult(t, req);
            return;
        }

        // 지각 보고 정합화: stale 회수로 IMPLEMENTATION_FAILED가 됐지만 워커가 실제로는
        // PR 생성까지 성공한 경우, 늦게 도착한 PR_CREATED 보고를 받아 정합화한다.
        // (PR 메타가 유효할 때만 — undeploy 복귀(prUrl 없는 PR_CREATED)와 구분됨)
        if (current == TaskStatus.IMPLEMENTATION_FAILED
                && req.status() == TaskStatus.PR_CREATED
                && req.prUrl() != null && !req.prUrl().isBlank()
                && req.headBranch() != null && !req.headBranch().isBlank()) {
            t.setStatus(TaskStatus.PR_CREATED);
            t.setPrUrl(req.prUrl());
            t.setPrNumber(req.prNumber());
            t.setHeadBranch(req.headBranch());
            t.setHeadSha(req.headSha());
            t.setImplementationLog(req.implementationLog());
            applyEnvTemplate(t, req);
            t.setFailureReason(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(),
                    TaskStatus.IMPLEMENTATION_FAILED, TaskStatus.PR_CREATED,
                    "worker", req.workerId(), "지각 보고 정합화: stale 회수 → PR_CREATED"));
            logRefWarnings(t, req);
            accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            return;
        }

        // 지각 보고 정합화(분석): stale 회수로 FAILED가 됐지만 워커가 실제로는 분석에 성공한 경우.
        // PENDING은 받지 않는다 — stale 회수가 재큐한 PENDING은 재클레임(SKIP LOCKED)과 경합하고,
        // 어차피 재분석이 같은 결과로 수렴하므로 지각 보고로 덮지 않는다.
        // markdownResult가 빈 지각 보고는 분기 불충족 → 아래 in-flight 가드에서 409 (PR_CREATED 관행과 동일).
        if (current == TaskStatus.FAILED
                && req.status() == TaskStatus.COMPLETED
                && req.markdownResult() != null && !req.markdownResult().isBlank()) {
            TaskAnalysis a = TaskAnalysis.create(t.getId(),
                    req.markdownResult(), req.subtasksJson(),
                    req.claudeLog(), req.durationMs());
            analysisRepo.save(a);
            t.setStatus(TaskStatus.COMPLETED);
            t.setFailureReason(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(),
                    TaskStatus.FAILED, TaskStatus.COMPLETED,
                    "worker", req.workerId(), "지각 보고 정합화: stale 회수 → 분석완료"));
            accumulateUsage(t.getId(), TaskStageUsage.STAGE_ANALYSIS, req);
            return;
        }

        // 지각 보고 정합화(배포): stale 회수로 DEPLOY_FAILED가 됐지만 워커가 실제로는 배포에 성공한 경우.
        // DEPLOYING/DEPLOY_PENDING은 받지 않는다(재배포가 진행 중이면 그쪽이 이겨야 함).
        // DEPLOY_LOST도 대상 아님(DEPLOYED에서만 진입하는 관측 상태).
        // 잘못된 정합화(컨테이너가 실제로 죽은 경우)는 DeployReconcileJob(300초 주기)이
        // DEPLOYED↔DEPLOY_LOST 관측 보고로 자가치유한다.
        if (current == TaskStatus.DEPLOY_FAILED
                && req.status() == TaskStatus.DEPLOYED
                && req.deployUrl() != null && !req.deployUrl().isBlank()) {
            t.setStatus(TaskStatus.DEPLOYED);
            t.setDeployUrl(req.deployUrl());
            t.setDeployContainerId(req.deployContainerId());
            t.setDeployHostPort(req.deployHostPort());
            t.setDeployImage(req.deployImage());
            t.setDeployedAt(OffsetDateTime.now());
            t.setDeployLog(req.deployLog());
            t.setFailureReason(null);
            t.setUpdatedAt(OffsetDateTime.now());
            String reconciled = "지각 보고 정합화: stale 회수 → 배포완료" + applyBuiltSha(t, req);
            // 배포실패로 가는 길은 모두 회차를 닫지만, 표시가 남아 있으면 여기서도 닫는다(GC 보호가 영원히 남지 않게)
            if (t.getActiveFixRequestId() != null) {
                reconciled += FixRounds.closedSuffix(FixRounds.closeActive(fixRequestRepo, t, FixOutcome.DEPLOYED));
            }
            historyRepo.save(TaskStatusHistory.log(t.getId(),
                    TaskStatus.DEPLOY_FAILED, TaskStatus.DEPLOYED,
                    "worker", req.workerId(), reconciled));
            // stale 회수가 이미 finish했을 수 있으나 finish는 멱등
            // (emitters.remove → null no-op, deleteByTaskId → 0건 no-op).
            deployLogStream.finish(t.getId());
            return;
        }

        // 지각 보고 정합화(디자인): stale 회수로 DESIGN_PENDING 재큐됐지만 워커가 실제로는
        // 디자인을 완성한 경우 — 늦은 DESIGN_REVIEW 보고를 받아 재생성 사이클(수 분 + claude
        // 비용)을 생략한다. 재큐분을 다른 워커가 이미 claim했으면(DESIGNING) 이 분기 불충족 →
        // 아래 in-flight 가드의 소유 워커 검사로 귀결. 산출물이 유효할 때만 수용한다.
        // (배포중지 지각 PR_CREATED는 정합화하지 않는다 — UNDEPLOY_PENDING 재큐는 docker rm -f가
        //  이미 없는 컨테이너를 무해 통과해 스스로 수렴하고, 여러 사이클 뒤의 초지각 replay를
        //  수용하면 살아있는 컨테이너를 고아로 만들 수 있다.)
        if (current == TaskStatus.DESIGN_PENDING
                && req.status() == TaskStatus.DESIGN_REVIEW
                && req.designMarkdown() != null && !req.designMarkdown().isBlank()
                && req.mockupFilesJson() != null && !req.mockupFilesJson().isBlank()) {
            recordDesignResult(t, req, "지각 보고 정합화: stale 재큐 → 디자인승인대기");
            return;
        }

        if (current != TaskStatus.IN_PROGRESS
                && current != TaskStatus.IMPLEMENTING
                && current != TaskStatus.DESIGNING
                && current != TaskStatus.DEPLOYING
                && current != TaskStatus.UNDEPLOYING
                && current != TaskStatus.CLOSING) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "현재 처리중 상태가 아닙니다 (현재: " + current.dbValue() + ")");
        }
        if (t.getWorkerId() != null && !t.getWorkerId().equals(req.workerId())) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }

        // 종료 단계는 별도 처리 (이 작업에 필요한 단계(CloseStep.required) 완료 검증).
        if (current == TaskStatus.CLOSING) {
            recordCloseResult(t, req);
            return;
        }

        // 배포/중지 단계는 별도 처리 (분석/구현 검증 로직과 분리).
        if (current == TaskStatus.DEPLOYING || current == TaskStatus.UNDEPLOYING) {
            recordDeployResult(t, req);
            return;
        }

        // 디자인 단계도 별도 처리 (분석/구현 검증 로직과 분리).
        if (current == TaskStatus.DESIGNING) {
            recordDesignResult(t, req);
            return;
        }

        // 단계별 허용 상태 검증 — 잘못된 보고를 일찍 차단
        if (current == TaskStatus.IN_PROGRESS
                && req.status() != TaskStatus.COMPLETED
                && req.status() != TaskStatus.FAILED) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "분석 단계에서 허용되지 않는 status: " + req.status());
        }
        if (current == TaskStatus.IMPLEMENTING
                && req.status() != TaskStatus.PR_CREATED
                && req.status() != TaskStatus.IMPLEMENTATION_FAILED) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "구현 단계에서 허용되지 않는 status: " + req.status());
        }

        TaskStatus from = current;

        switch (req.status()) {
            case COMPLETED -> {
                if (req.markdownResult() == null || req.markdownResult().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST,
                            "분석완료 시 markdownResult 필수");
                }
                TaskAnalysis a = TaskAnalysis.create(t.getId(),
                        req.markdownResult(), req.subtasksJson(),
                        req.claudeLog(), req.durationMs());
                analysisRepo.save(a);
                t.setStatus(TaskStatus.COMPLETED);
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_ANALYSIS, req);
            }
            case FAILED -> {
                t.setStatus(TaskStatus.FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_ANALYSIS, req);
            }
            case PR_CREATED -> {
                if (req.prUrl() == null || req.prUrl().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST,
                            "PR 생성 시 prUrl 필수");
                }
                if (req.headBranch() == null || req.headBranch().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST,
                            "PR 생성 시 headBranch 필수");
                }
                t.setStatus(TaskStatus.PR_CREATED);
                t.setPrUrl(req.prUrl());
                t.setPrNumber(req.prNumber());
                t.setHeadBranch(req.headBranch());
                t.setHeadSha(req.headSha());
                t.setImplementationLog(req.implementationLog());
                applyEnvTemplate(t, req);
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            }
            case IMPLEMENTATION_FAILED -> {
                t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                t.setImplementationLog(req.implementationLog());
                // 일부만 진행된 경우라도 (브랜치 push 했지만 PR 실패 등) 가능한 메타 저장
                if (req.headBranch() != null) t.setHeadBranch(req.headBranch());
                if (req.headSha() != null) t.setHeadSha(req.headSha());
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "허용되지 않은 status: " + req.status());
        }
        t.setUpdatedAt(OffsetDateTime.now());

        String reason = switch (t.getStatus()) {
            case FAILED, IMPLEMENTATION_FAILED -> t.getFailureReason();
            case PR_CREATED -> "PR 생성: " + t.getPrUrl();
            case COMPLETED -> "분석 완료";
            default -> "처리 완료";
        };
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
        if (t.getStatus() == TaskStatus.PR_CREATED) logRefWarnings(t, req);
    }

    /**
     * 구현이 참조 repo를 바꿨다는 워커 경고를 PR생성 이력 다음에 같은 상태의 이력 행으로 남긴다 (스펙 2026-10-24 §4.8).
     * 공백인 원소는 버리고 나머지는 앞뒤 공백을 지워 ", "로 잇는다. 워커가 이미 가리고 잘라 보내지만 API는 그 출력을 믿지 않고
     * 행 전체를 한 번 더 가린 뒤 자른다. 경고가 없으면(null·빈 목록·공백뿐) 행을 남기지 않는다.
     */
    private void logRefWarnings(Task t, WorkerResultRequest req) {
        if (req.refWarnings() == null) return;
        List<String> warnings = req.refWarnings().stream()
                .filter(w -> w != null && !w.isBlank())
                .map(String::strip)
                .toList();
        if (warnings.isEmpty()) return;
        String reason = GitRemotes.maskAndCut(
                "참조 repo 변경 감지(PR에는 포함 안 됨): " + String.join(", ", warnings), REF_WARNING_ROW_MAX_LENGTH);
        historyRepo.save(TaskStatusHistory.log(t.getId(), TaskStatus.PR_CREATED, TaskStatus.PR_CREATED,
                "worker", req.workerId(), reason));
    }

    /** 구현 결과인데 시도 번호가 작업의 현재 시도와 다르면 이전 시도의 보고다. 번호가 없으면(구버전 워커) 판단하지 않는다. */
    private static boolean isEarlierImplementationAttempt(Task t, WorkerResultRequest req) {
        if (req.implementationAttempt() == null) return false;
        if (req.status() != TaskStatus.PR_CREATED && req.status() != TaskStatus.IMPLEMENTATION_FAILED) return false;
        return req.implementationAttempt() != t.getImplementationAttempt();
    }

    private static boolean isImplementationResult(WorkerResultRequest req) {
        return req.status() == TaskStatus.PR_CREATED || req.status() == TaskStatus.IMPLEMENTATION_FAILED;
    }

    /** 수정 회차 구현 보고의 표지(회차 안 시도 번호·요청 id)를 하나라도 단 보고 — fixRounds를 아는 워커가 회차 구현에만 붙인다. */
    private static boolean isFixRoundReport(WorkerResultRequest req) {
        return req.fixAttempt() != null || req.fixRequestId() != null;
    }

    /**
     * 수정 회차의 구현 결과 (스펙 2026-11-03 §7). 늦은 보고를 먼저 가려낸 뒤({@link #ignoredFixReport}), 지금 시도의 성공은 같은 브랜치의
     * 새 끝(head_sha)을 저장하고 저장된 env 그대로 자동 재배포(배포대기)로, 실패는 회차를 연 채 구현실패로 둔다.
     */
    private void recordFixRoundImplementationResult(Task t, WorkerResultRequest req) {
        TaskStatus current = t.getStatus();
        TaskFixRequest round = FixRounds.openRequest(fixRequestRepo, t);
        IgnoredFixReport ignored = ignoredFixReport(t, round, req);
        if (ignored != null) {
            historyRepo.save(TaskStatusHistory.log(t.getId(), current, current, "worker", req.workerId(),
                    ignored.reason() + ": " + req.status().dbValue() + ignoredWorkDetail(req)));
            if (ignored.countUsage()) accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            return;
        }

        if (current == TaskStatus.IMPLEMENTATION_FAILED) {
            // 지각 보고 정합화: stale 회수로 구현실패가 됐지만 워커는 같은 시도를 끝내 push했다 — 성공만 받는다(실패 재보고는 기존처럼 409)
            if (req.status() != TaskStatus.PR_CREATED) {
                throw new TaskException(HttpStatus.CONFLICT, "현재 처리중 상태가 아닙니다 (현재: " + current.dbValue() + ")");
            }
            fixRoundSucceeded(t, round, req, "지각 보고 정합화: stale 회수 → ");
            return;
        }
        if (t.getWorkerId() != null && !t.getWorkerId().equals(req.workerId())) {
            throw new TaskException(HttpStatus.CONFLICT, "다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }
        if (req.status() == TaskStatus.PR_CREATED) {
            fixRoundSucceeded(t, round, req, "");
        } else {
            fixRoundFailed(t, round, req);
        }
    }

    /** 무시할 회차 보고 — 이력 사유 앞부분과, 사용량을 쌓을지. */
    private record IgnoredFixReport(String reason, boolean countUsage) {}

    /**
     * 열린 회차의 지금 시도 결과가 아니면 무시한다(상태는 그대로, 같은 상태의 이력 한 줄). null = 지금 시도의 결과.
     *  - 표지가 없다(회차 표시만 있다): 회차는 fixRounds를 아는 워커만 받으므로 회차 전 구현 보고의 재전송이다 — 받으면 옛 커밋을 재배포한다.
     *  - 요청 id가 열린 회차와 다르다(또는 열린 회차가 없다): 다른·닫힌 회차의 보고. 회차 안 시도 번호는 회차마다 1부터라
     *    요청 id로만 가를 수 있다(예: 1회차를 중단하고 연 2회차의 1회째에 1회차 1회째 보고가 늦게 온 경우).
     *  - 요청 id 없이 열린 회차가 없다: 닫힌 회차의 보고(요청 id를 싣지 않는 워커).
     *  - 회차 안 시도 번호가 지금 시도와 다르다: 같은 회차의 이전 시도(재시도·재기동 재큐 전).
     *  - 같은 시도인데 작업이 구현대기다: 아직 claim 전이라 이 회차의 보고일 수 없다 — 요청 id를 싣지 않은 다른 회차의 보고.
     *  - 같은 시도인데 자동 재배포로 넘어갔다: 받은 보고의 재전송. 배포중에 받으면 배포 중지 성공으로 읽혀 회차가 갇힌다.
     *
     * 사용량은 보고의 시도가 그 요청 기록의 지금 시도보다 앞설 때만 쌓는다 — 재시도·재큐로 밀린 시도의 결과는 반영된 적이 없을 수 있다
     * (회차 밖 구현의 이전 시도 보고와 같은 규칙). 회차 전 구현·회차의 마지막 시도·어느 요청인지 모르는 보고는 이미 반영된 보고의 재전송일 수
     * 있어 쌓지 않는다 — 두 번 세느니 덜 센다.
     */
    private IgnoredFixReport ignoredFixReport(Task t, TaskFixRequest round, WorkerResultRequest req) {
        Integer fixAttempt = req.fixAttempt();
        Long requestId = req.fixRequestId();
        if (!isFixRoundReport(req)) {
            return new IgnoredFixReport("수정 회차 전 구현의 늦은 보고 무시", false);
        }
        if (requestId != null && (round == null || !requestId.equals(round.getId()))) {
            TaskFixRequest of = fixRequestRepo.findById(requestId)
                    .filter(r -> t.getId().equals(r.getTaskId())).orElse(null);
            return new IgnoredFixReport((round == null ? "닫힌" : "다른") + " 수정 회차(요청 #" + requestId + ")의 늦은 보고 무시",
                    isSuperseded(of, fixAttempt));
        }
        if (round == null) {
            return new IgnoredFixReport("닫힌 수정 회차의 늦은 보고 무시", false);
        }
        if (fixAttempt == null) {
            return new IgnoredFixReport("회차 안 시도 번호가 없는 수정 회차 보고 무시", false);
        }
        if (fixAttempt != round.getAttempt()) {
            return new IgnoredFixReport("이전 수정 시도(" + fixAttempt + "회째)의 늦은 보고 무시", isSuperseded(round, fixAttempt));
        }
        if (t.getStatus() == TaskStatus.APPROVED) {
            return new IgnoredFixReport("다른 수정 회차의 늦은 보고 무시", false);
        }
        if (t.getStatus() != TaskStatus.IMPLEMENTING && t.getStatus() != TaskStatus.IMPLEMENTATION_FAILED) {
            return new IgnoredFixReport("이미 끝난 수정 시도(" + fixAttempt + "회째)의 늦은 보고 무시", false);
        }
        return null;
    }

    /** 보고의 시도가 그 요청 기록의 지금 시도보다 앞선다 — 재시도·재큐로 밀린 시도. 요청을 모르면 false. */
    private static boolean isSuperseded(TaskFixRequest r, Integer fixAttempt) {
        return r != null && fixAttempt != null && fixAttempt < r.getAttempt();
    }

    /**
     * 회차 구현 성공 → 자동 재배포. PR/MR은 새로 만들지 않으므로 prUrl은 받지 않고, 브랜치는 회차 브랜치여야 한다(다른 브랜치의 커밋을
     * head_sha로 두면 배포(브랜치 끝)·종료 병합(head_sha 고정)이 어긋난다). 배포 전에 head_sha를 새 끝으로 바꾼다(§7).
     * 배포 env 템플릿은 보통 구현 성공처럼 받는다 — 자동 재배포는 저장된 env 값(envVars)을 그대로 쓰고, 템플릿은 다음 배포 다이얼로그의 안내다.
     * 이력은 두 줄 — 구현 완료(워커) → 자동 재배포(system). PR생성은 거쳐 가기만 한다.
     */
    private void fixRoundSucceeded(Task t, TaskFixRequest round, WorkerResultRequest req, String reasonPrefix) {
        if (!isRoundBranch(t, req)) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "수정 회차 구현 완료 보고의 브랜치가 회차 브랜치("
                    + t.getHeadBranch() + ")가 아닙니다: " + (req.headBranch() == null ? "" : req.headBranch().strip()));
        }
        if (req.headSha() == null || req.headSha().isBlank()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "수정 회차 구현 완료 시 headSha 필수");
        }
        TaskStatus from = t.getStatus();
        String label = FixRounds.label(round);
        t.setHeadSha(req.headSha().strip());
        t.setImplementationLog(req.implementationLog());
        applyEnvTemplate(t, req);   // 회차가 새 env를 남겼으면 템플릿 갱신 — 보통 구현 성공과 같다(null이면 기존 유지)
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.PR_CREATED, "worker", req.workerId(),
                reasonPrefix + label + " 구현 완료: " + FixRounds.shortSha(req.headSha())));
        logRefWarnings(t, req);
        accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);

        // 자동 재배포 — 저장된 env 그대로, TaskService.toDeployPending과 같은 칸 정리. 사람이 누른 게 아니라 actor는 system
        t.setStatus(TaskStatus.DEPLOY_PENDING);
        t.setFailureReason(null);
        t.setClaimedAt(null);
        t.setWorkerId(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), TaskStatus.PR_CREATED, TaskStatus.DEPLOY_PENDING,
                "system", null, label + " 구현 완료 → 자동 재배포"));
    }

    /**
     * 회차 구현 실패 — 회차는 열어 둔다(관리자가 구현 재시도 또는 수정 중단을 고른다). 브랜치는 회차 브랜치 그대로 두고, 보고가 회차 브랜치의
     * 끝을 실었을 때만 head_sha로 받는다 — wip 커밋을 push했을 수 있어 수정 중단 뒤 배포·종료 병합의 기준이 된다(§7.1).
     * 브랜치가 다르거나 없는 보고의 커밋은 회차 브랜치의 것인지 알 수 없어 버린다.
     */
    private void fixRoundFailed(Task t, TaskFixRequest round, WorkerResultRequest req) {
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
        t.setImplementationLog(req.implementationLog());
        if (req.headSha() != null && !req.headSha().isBlank() && isRoundBranch(t, req)) t.setHeadSha(req.headSha().strip());
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.IMPLEMENTATION_FAILED, "worker", req.workerId(),
                FixRounds.label(round) + " 구현 실패: " + t.getFailureReason()));
        accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
    }

    /** 보고의 브랜치가 회차 브랜치(task.head_branch)와 같다(앞뒤 공백 무시). 보고에 브랜치가 없으면 false. */
    private static boolean isRoundBranch(Task t, WorkerResultRequest req) {
        return req.headBranch() != null && req.headBranch().strip().equals(t.getHeadBranch());
    }

    /**
     * 배포완료 보고가 실제로 빌드한 커밋(headSha)을 실었으면 task.head_sha로 기록한다 (스펙 2026-11-03 §7) — 배포는 브랜치 끝을 빌드하므로
     * 사람이 MR에 push했거나 끊긴 시도가 남긴 커밋이 있으면 기록과 다를 수 있고, 종료 병합이 head_sha를 고정한다. 수정 회차와 무관하게
     * 모든 배포에 적용한다. 바뀌었으면 이력 사유에 붙일 말({@code  (head_sha abc1234→def5678)})을, 같거나 값이 없으면(예전 워커)
     * 빈 문자열을 돌려준다.
     */
    private static String applyBuiltSha(Task t, WorkerResultRequest req) {
        if (req.headSha() == null || req.headSha().isBlank()) return "";
        String built = req.headSha().strip();
        String before = t.getHeadSha();
        t.setHeadSha(built);
        if (built.equals(before)) return "";
        String from = before == null || before.isBlank() ? "없음" : FixRounds.shortSha(before);
        return " (head_sha " + from + "→" + FixRounds.shortSha(built) + ")";
    }

    /**
     * 무시한 이전 시도 보고가 가리키던 PR·브랜치 — 그 작업물이 어디 남아 있는지 이력에서 찾을 수 있게 뒤에 붙인다
     * (예: ` (PR: https://…/pull/9, 브랜치: netismaker/task-7-x)`). 둘 다 없으면 빈 문자열.
     */
    private static String ignoredWorkDetail(WorkerResultRequest req) {
        List<String> parts = new ArrayList<>();
        if (req.prUrl() != null && !req.prUrl().isBlank()) parts.add("PR: " + req.prUrl().strip());
        if (req.headBranch() != null && !req.headBranch().isBlank()) parts.add("브랜치: " + req.headBranch().strip());
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    /** 구현 성공 보고의 배포 env 템플릿 저장 — null(구버전 워커)이면 기존 값 유지 (스펙 2026-10-07 §5). */
    private static void applyEnvTemplate(Task t, WorkerResultRequest req) {
        if (req.envTemplate() != null) t.setEnvTemplate(EnvTemplateItem.sanitize(req.envTemplate()));
    }

    private void recordDeployResult(Task t, WorkerResultRequest req) {
        TaskStatus from = t.getStatus();
        String reason;
        switch (req.status()) {
            case DEPLOYED -> {
                if (req.deployUrl() == null || req.deployUrl().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "배포완료 시 deployUrl 필수");
                }
                t.setStatus(TaskStatus.DEPLOYED);
                t.setFailureReason(null);
                t.setDeployUrl(req.deployUrl());
                t.setDeployContainerId(req.deployContainerId());
                t.setDeployHostPort(req.deployHostPort());
                t.setDeployImage(req.deployImage());
                t.setDeployedAt(OffsetDateTime.now());
                t.setDeployLog(req.deployLog());
                reason = "배포 완료: " + req.deployUrl() + applyBuiltSha(t, req);   // 빌드한 커밋 → head_sha
            }
            case DEPLOY_FAILED -> {
                t.setStatus(TaskStatus.DEPLOY_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                t.setDeployLog(req.deployLog());
                reason = t.getFailureReason();
            }
            case PR_CREATED -> {
                t.setStatus(TaskStatus.PR_CREATED);
                t.setFailureReason(null);
                t.setDeployUrl(null);
                t.setDeployContainerId(null);
                t.setDeployHostPort(null);
                t.setDeployImage(null);
                t.setDeployedAt(null);
                t.setDeployLog(req.deployLog());
                reason = "배포 중지 → PR생성 복귀";
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "배포 단계에서 허용되지 않는 status: " + req.status());
        }
        // 수정 회차의 자동 재배포가 끝났다(성공·실패) — 회차를 닫는다 (스펙 2026-11-03 §4)
        if (t.getActiveFixRequestId() != null && t.getStatus() == TaskStatus.DEPLOYED) {
            reason += FixRounds.closedSuffix(FixRounds.closeActive(fixRequestRepo, t, FixOutcome.DEPLOYED));
        } else if (t.getActiveFixRequestId() != null && t.getStatus() == TaskStatus.DEPLOY_FAILED) {
            reason += FixRounds.closedSuffix(FixRounds.closeActive(fixRequestRepo, t, FixOutcome.DEPLOY_FAILED));
        }
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
        // 배포/중지 종료 → 스트림 구독자에 done 통지 + 청크 정리
        deployLogStream.finish(t.getId());
    }

    private void recordDesignResult(Task t, WorkerResultRequest req) {
        recordDesignResult(t, req, null);
    }

    /** reasonOverride: 지각 보고 정합화처럼 이력 사유를 구분해야 할 때만 지정 (null=기본 사유). */
    private void recordDesignResult(Task t, WorkerResultRequest req, String reasonOverride) {
        TaskStatus from = t.getStatus();
        String reason;
        switch (req.status()) {
            case DESIGN_REVIEW -> {
                if (req.designMarkdown() == null || req.designMarkdown().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "디자인 완료 시 designMarkdown 필수");
                }
                if (req.mockupFilesJson() == null || req.mockupFilesJson().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "디자인 완료 시 mockupFilesJson 필수");
                }
                TaskDesign d = designRepo.findById(t.getId()).orElse(null);
                if (d == null) {
                    d = TaskDesign.create(t.getId(), req.designMarkdown(), req.mockupFilesJson(),
                            req.designProjectId(), req.designUrl(), req.claudeLog(), req.durationMs());
                } else {
                    // 반려 재실행: feedback_history/reject_count 보존, 산출물만 갱신
                    d.setDesignMarkdown(req.designMarkdown());
                    d.setMockupFilesJson(req.mockupFilesJson());
                    if (req.designProjectId() != null) d.setDesignProjectId(req.designProjectId());
                    d.setDesignUrl(req.designUrl());
                    d.setClaudeLog(req.claudeLog());
                    d.setDurationMs(req.durationMs());
                    d.setCompletedAt(OffsetDateTime.now());
                }
                designRepo.save(d);
                // 출력 프로젝트 최초 생성 시 카탈로그에 박제 (이후 작업이 재사용)
                if (req.designProjectId() != null && t.getRepoCatalogId() != null) {
                    repoCatalogRepo.findById(t.getRepoCatalogId())
                            .filter(c -> c.getDesignOutputProjectId() == null)
                            .ifPresent(c -> {
                                c.setDesignOutputProjectId(req.designProjectId());
                                c.setUpdatedAt(OffsetDateTime.now());
                            });
                }
                t.setStatus(TaskStatus.DESIGN_REVIEW);
                reason = reasonOverride != null ? reasonOverride : "디자인 생성 완료 → 승인 대기";
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_DESIGN, req);
            }
            case DESIGN_FAILED -> {
                t.setStatus(TaskStatus.DESIGN_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                reason = t.getFailureReason();
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_DESIGN, req);
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "디자인 단계에서 허용되지 않는 status: " + req.status());
        }
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
    }

    /**
     * 종료 단계 진행 보고 — 끝난 CloseStep을 task.close_progress에 누적하고 단계별 값을 저장한다.
     * 같은 단계를 다시 보고하거나 값이 null이어도 무해(멱등). 재claim한 워커는 이 목록의 단계를 건너뛴다.
     */
    @Transactional
    public void recordCloseProgress(Long taskId, CloseProgressRequest req) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.CLOSING) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "종료중 상태가 아닙니다 (현재: " + t.getStatus().dbValue() + ")");
        }
        if (!req.workerId().equals(t.getWorkerId())) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }
        switch (req.step()) {
            case MERGED -> {
                if (req.mergeCommitSha() != null && !req.mergeCommitSha().isBlank()) {
                    if (req.mergeCommitSha().length() > MAX_COMMIT_SHA_LENGTH) {
                        throw new TaskException(HttpStatus.BAD_REQUEST, "mergeCommitSha가 너무 깁니다");
                    }
                    t.setMergeCommitSha(req.mergeCommitSha());
                }
                if (req.baseSha() != null && !req.baseSha().isBlank()) {
                    if (req.baseSha().length() > MAX_COMMIT_SHA_LENGTH) {
                        throw new TaskException(HttpStatus.BAD_REQUEST, "baseSha가 너무 깁니다");
                    }
                    t.setPatchBaseSha(req.baseSha());
                }
            }
            case UNDEPLOYED -> {
                // 컨테이너를 내렸으니 배포 런타임 정보를 비운다 (undeploy→PR_CREATED 복귀와 같은 규칙; 배포 로그는 보존)
                t.setDeployUrl(null);
                t.setDeployContainerId(null);
                t.setDeployHostPort(null);
                t.setDeployImage(null);
                t.setDeployedAt(null);
            }
            case PATCH_BUILT -> {
                // 부분 패치 (스펙 2026-11-10): 변경 없음이면 파일 없이 표시만, 아니면 zip이 먼저 올라가 있어야 한다
                if (!CloseStep.PATCH_REQUIRED.equals(t.getClosePatchMode())) {
                    throw new TaskException(HttpStatus.CONFLICT, "부분 패치 대상이 아닙니다");
                }
                if (Boolean.TRUE.equals(req.patchEmpty())) {
                    t.setPatchEmpty(true);
                    t.setPatchFileCount(0);
                } else {
                    if (t.getPatchName() == null) {
                        throw new TaskException(HttpStatus.BAD_REQUEST, "패치가 아직 저장되지 않았습니다");
                    }
                    t.setPatchEmpty(false);
                }
            }
            case ARTIFACT_UPLOADED -> {
                // 파일이 먼저 저장돼 있어야 한다 — PUT /worker/tasks/{id}/artifact (스펙 2026-10-14 §5)
                if (t.getArtifactName() == null) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "산출물이 아직 저장되지 않았습니다");
                }
            }
            case NOTE_WRITTEN -> {
                if (req.notePath() != null && !req.notePath().isBlank()) {
                    if (req.notePath().length() > MAX_NOTE_PATH_LENGTH) {
                        throw new TaskException(HttpStatus.BAD_REQUEST, "notePath가 너무 깁니다");
                    }
                    t.setCloseNotePath(req.notePath());
                }
                recordNoteFolder(t, req.noteFolder());
            }
            case BRANCH_DELETED -> {
                if (!CloseStep.parse(t.getCloseProgress()).contains(CloseStep.BRANCH_DELETED)) rebaseDependents(t);
            }
        }
        t.setCloseProgress(CloseStep.append(t.getCloseProgress(), req.step()));
        t.setUpdatedAt(OffsetDateTime.now());
    }

    /**
     * 지운 head 브랜치를 기준으로 쓰던 같은 레포의 작업을 이 작업의 기준 브랜치로 옮긴다 — 병합으로 그 내용이 이미
     * 거기 들어갔다. 워커는 브랜치를 지우기 전에 그 브랜치를 target으로 쓰는 열린 PR/MR을 같은 곳으로 옮긴다.
     * 안 옮기면 그 작업의 종료(병합)·구현 재시도가 없는 브랜치를 기준으로 삼아 실패한다(작업 25, 2026-11-03).
     */
    private void rebaseDependents(Task t) {
        String deleted = t.getHeadBranch();
        String base = t.getGithubBranch();
        if (deleted == null || deleted.isBlank() || deleted.equals(base)) return;
        String host = RepoRef.fromSnapshot(t.getGithubRepo(), t.getGitUrl()).host();
        for (Task d : taskRepo.findActiveByBaseBranch(t.getGithubRepo(), deleted, t.getId())) {
            if (!host.equals(RepoRef.fromSnapshot(d.getGithubRepo(), d.getGitUrl()).host())) continue;
            d.setGithubBranch(base);
            d.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(d.getId(), d.getStatus(), d.getStatus(), "worker", t.getWorkerId(),
                    "기준 브랜치 변경: " + deleted + " → " + base + " (작업 #" + t.getId() + " 종료로 병합·삭제)"));
            log.info("기준 브랜치 변경: task={} {} → {} (종료 task={})", d.getId(), deleted, base, t.getId());
        }
    }

    /**
     * 워커가 이번 종료에서 정한 노트 폴더를 카탈로그에 기록한다(비어 있을 때만, 스펙 2026-10-28 close-note-folder §5).
     * 노트는 이미 썼으니 값이 잘못됐거나 카탈로그가 없으면 기록만 건너뛴다 — 종료를 멈출 이유가 아니다.
     */
    private void recordNoteFolder(Task t, String raw) {
        if (raw == null || raw.isBlank()) return;
        String folder;
        try {
            folder = NoteFolderPath.normalize(raw);
        } catch (IllegalArgumentException e) {
            log.warn("종료 노트 폴더 기록 건너뜀: task={} {} — {}", t.getId(), raw, e.getMessage());
            return;
        }
        RepoCatalogEntry cat = RepoCatalogRepository.findForTask(repoCatalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                .orElse(null);
        if (cat == null) return;
        if (repoCatalogRepo.recordNoteFolderIfEmpty(cat.getId(), folder) > 0) {
            log.info("레포 카탈로그 노트 폴더 기록: catalog={} {}", cat.getId(), folder);
        }
    }

    /** 종료 결과 보고. CLOSED는 이 작업에 필요한 단계(CloseStep.required)가 모두 보고된 뒤에만 받는다 — 단계가 빠진 채 끝났다고 믿지 않는다. */
    private void recordCloseResult(Task t, WorkerResultRequest req) {
        TaskStatus from = t.getStatus();
        String reason;
        switch (req.status()) {
            case CLOSED -> {
                if (!CloseStep.allDone(t.getCloseProgress(), t.getCloseArtifactMode(), t.getClosePatchMode())) {
                    throw new TaskException(HttpStatus.BAD_REQUEST,
                            "종료 단계가 모두 끝나지 않았습니다 (끝난 단계: "
                                    + (t.getCloseProgress() == null ? "없음" : t.getCloseProgress()) + ")");
                }
                t.setStatus(TaskStatus.CLOSED);
                t.setFailureReason(null);
                String sha = t.getMergeCommitSha();
                reason = "작업 종료" + (sha == null || sha.isBlank() ? ""
                        : ": 병합 " + sha.substring(0, Math.min(7, sha.length())));
                if (events != null) events.publishEvent(new CloseCompletedEvent(t.getId()));
            }
            case CLOSE_FAILED -> {
                t.setStatus(TaskStatus.CLOSE_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                reason = t.getFailureReason();
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "종료 단계에서 허용되지 않는 status: " + req.status());
        }
        accumulateUsage(t.getId(), TaskStageUsage.STAGE_CLOSE, req);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
    }

    /** 워커가 배포 중 올리는 증분 로그 청크. 상태 검증 없이 best-effort 영속/중계. */
    @Transactional
    public void appendDeployLog(Long taskId, com.hamonsoft.netismaker.dto.DeployLogChunkRequest req) {
        deployLogStream.ingestChunk(taskId, req.seq() == null ? 0 : req.seq(), req.content());
    }

    /**
     * 배포 런타임 정합·GC 보호 대상 목록. 워커 DeployReconcileJob·DockerGcJob이 주기 조회.
     * 배포완료·배포중단됨뿐 아니라 배포 계열 진행 중·종료 단계·수정승인대기와 열린 수정 회차(active_fix_request_id)가 있는 작업도
     * 담는다({@code TaskRepository.findDeployReconcilable}) — 대사는 앞 둘만 하고 나머지는 GC 보호용이다.
     * 응답에 수정승인대기가 나가므로 이를 모르는 구 워커 jar는 역직렬화에 실패해 그 주기를 건너뛴다(컨테이너는 안전).
     */
    @Transactional(readOnly = true)
    public List<DeployedTaskSummary> listDeployReconcilable() {
        return taskRepo.findDeployReconcilable().stream()
                .map(t -> new DeployedTaskSummary(t.getId(), t.getStatus()))
                .toList();
    }

    /** worktree 정리 잡 보호 목록(구현중·디자인중·배포중·배포중지중, soft-delete 제외). 워커 WorktreeCleanupJob이 회차마다 조회. */
    @Transactional(readOnly = true)
    public List<ActiveWorktreeTaskSummary> listWorktreeActive() {
        return taskRepo.findWorktreeActive().stream()
                .map(t -> new ActiveWorktreeTaskSummary(t.getId(), t.getStatus()))
                .toList();
    }

    /**
     * 워커의 컨테이너 런타임 관측 반영.
     *
     *  배포완료   + running=false → 배포중단됨 (컨테이너 소실 감지)
     *  배포중단됨 + running=true  → 배포완료 (컨테이너 복구 감지)
     *
     * 그 외 조합은 no-op — 관측·보고 사이에 재배포/중지가 시작됐을 수 있으므로
     * (관측 시점의 스냅샷이 최신 상태를 덮어쓰지 않게) 조용히 무시한다.
     */
    @Transactional
    public void recordRuntimeStatus(Long taskId, WorkerRuntimeStatusRequest req) {
        // findActiveByIdForUpdate: ① 관측 목록 수신 후 삭제된 task의 지각 보고가 삭제 row를
        // 변이시키지 않게(findActive) ② 관측·보고 사이에 시작된 재배포/중지(FOR UPDATE 보유)의
        // 커밋을 기다렸다가 갱신된 상태로 재판정 — 무락이면 stale 스냅샷(DEPLOYED)이
        // DEPLOY_PENDING을 DEPLOY_LOST로 되덮는 lost update가 가능하다.
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        TaskStatus from = t.getStatus();
        boolean running = Boolean.TRUE.equals(req.running());

        if (from == TaskStatus.DEPLOYED && !running) {
            t.setStatus(TaskStatus.DEPLOY_LOST);
            historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DEPLOY_LOST,
                    "system", req.workerId(),
                    "컨테이너 소실 감지" + (req.detail() == null ? "" : ": " + req.detail())));
        } else if (from == TaskStatus.DEPLOY_LOST && running) {
            t.setStatus(TaskStatus.DEPLOYED);
            historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DEPLOYED,
                    "system", req.workerId(),
                    "컨테이너 복구 감지" + (req.detail() == null ? "" : ": " + req.detail())));
        } else {
            return; // 상태가 이미 진행됨(재배포/중지 등) — 관측 스냅샷 무시
        }
        t.setUpdatedAt(OffsetDateTime.now());
    }

    /** 보고 usage를 stage에 누적. usage 없음/전부 0이면 no-op (스펙 §7). */
    private void accumulateUsage(Long taskId, String stage, WorkerResultRequest req) {
        WorkerResultRequest.UsageReport u = req.usage();
        if (u == null || u.isEmpty()) return;
        stageUsageRepo.accumulate(taskId, stage,
                u.costUsd() == null ? java.math.BigDecimal.ZERO : u.costUsd(),
                nz(u.inputTokens()), nz(u.outputTokens()),
                nz(u.cacheCreationTokens()), nz(u.cacheReadTokens()));
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
}
