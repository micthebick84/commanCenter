package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱 로그를 볼 수 있는 작업인지의 판정(설계 §4 허용 상태). */
class AppLogPolicyTest {

    private static final List<TaskStatus> VIEWABLE = List.of(
            TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST, TaskStatus.FIX_REVIEW, TaskStatus.CLOSE_REVIEW,
            TaskStatus.CLOSE_PENDING, TaskStatus.CLOSING, TaskStatus.CLOSE_FAILED);
    private static final List<TaskStatus> IN_FLIGHT = List.of(
            TaskStatus.DEPLOY_PENDING, TaskStatus.DEPLOYING, TaskStatus.UNDEPLOY_PENDING, TaskStatus.UNDEPLOYING);

    private static final Pattern STATUS = Pattern.compile("TaskStatus\\.([A-Z_]+)");

    private static Task task(TaskStatus status, String deployUrl, OffsetDateTime deployedAt, Long activeFixRequestId) {
        Task t = Task.create("owner/repo", "main", "제목", "설명", "user1", 3, List.of(), null, null);
        t.setStatus(status);
        t.setDeployUrl(deployUrl);
        t.setDeployedAt(deployedAt);
        t.setActiveFixRequestId(activeFixRequestId);
        return t;
    }

    @Test
    void 허용_상태는_배포기록이_있을때만() {
        for (TaskStatus s : VIEWABLE) {
            assertThat(AppLogPolicy.isViewable(task(s, "http://x:19000", null, null))).as("%s deployUrl", s).isTrue();
            assertThat(AppLogPolicy.isViewable(task(s, null, null, null))).as("%s 기록 없음", s).isFalse();
            assertThat(AppLogPolicy.isViewable(task(s, null, OffsetDateTime.now(), null))).as("%s deployedAt만", s).isTrue();
        }
    }

    @Test
    void 열린_회차면_구현대기_구현중_구현실패도() {
        for (TaskStatus s : List.of(TaskStatus.APPROVED, TaskStatus.IMPLEMENTING, TaskStatus.IMPLEMENTATION_FAILED)) {
            assertThat(AppLogPolicy.isViewable(task(s, "http://x:19000", null, 7L))).as("%s 회차 있음", s).isTrue();
            assertThat(AppLogPolicy.isViewable(task(s, "http://x:19000", null, null))).as("%s 회차 없음", s).isFalse();
            // 열린 회차여도 배포 기록이 없으면 볼 컨테이너가 없다
            assertThat(AppLogPolicy.isViewable(task(s, null, null, 7L))).as("%s 회차 있고 기록 없음", s).isFalse();
        }
    }

    @Test
    void 진행_상태는_열린_회차여도_아니다() {
        for (TaskStatus s : IN_FLIGHT) {
            assertThat(AppLogPolicy.isViewable(task(s, "http://x:19000", null, 7L))).as("%s", s).isFalse();
            assertThat(AppLogPolicy.isViewable(task(s, "http://x:19000", OffsetDateTime.now(), null))).as("%s 회차 없음", s).isFalse();
        }
    }

    @Test
    void 배포실패_PR생성_작업종료는_아니다() {
        for (TaskStatus s : List.of(TaskStatus.DEPLOY_FAILED, TaskStatus.PR_CREATED, TaskStatus.CLOSED)) {
            assertThat(AppLogPolicy.isViewable(task(s, "http://x:19000", OffsetDateTime.now(), null))).as("%s", s).isFalse();
        }
    }

    @Test
    void 배포실패에서_열린_수정_회차는_규칙대로_허용() {
        // 수정 요청은 배포실패에서도 들어가고 배포실패는 deployUrl을 비우지 않는다 — 화면에는 no-container·container-stopped가 나온다
        assertThat(AppLogPolicy.isViewable(task(TaskStatus.IMPLEMENTING, "http://x:19000", null, 7L))).isTrue();
        // 상태가 DEPLOY_FAILED인 작업 자체는 열린 회차가 없다고 보고 닫는다
        assertThat(AppLogPolicy.isViewable(task(TaskStatus.DEPLOY_FAILED, "http://x:19000", null, null))).isFalse();
    }

    @Test
    void 배포기록은_deployUrl_또는_deployedAt() {
        assertThat(AppLogPolicy.hasDeployRecord(task(TaskStatus.DEPLOYED, null, null, null))).isFalse();
        assertThat(AppLogPolicy.hasDeployRecord(task(TaskStatus.DEPLOYED, "http://x", null, null))).isTrue();
        assertThat(AppLogPolicy.hasDeployRecord(task(TaskStatus.DEPLOYED, null, OffsetDateTime.now(), null))).isTrue();
    }

    @Test
    void 상수는_계약_그대로() {
        assertThat(AppLogPolicy.VIEWABLE_STATUSES).containsExactlyInAnyOrderElementsOf(VIEWABLE);
        assertThat(AppLogPolicy.IN_FLIGHT_EXCLUDED).containsExactlyInAnyOrderElementsOf(IN_FLIGHT);
    }

    @Test
    void 허용과_제외를_합치면_GC_보호_목록과_같다() throws Exception {
        // 컨테이너를 살려 두는 상태(GC 보호 목록)와 앱 로그 규칙이 어긋나면 보호받는 컨테이너의 로그를 못 보거나,
        // 지워질 수 있는 상태에서 로그를 연다. findDeployReconcilable의 JPQL에 적힌 상태를 읽어 대조한다.
        String jpql = TaskRepository.class.getMethod("findDeployReconcilable").getAnnotation(Query.class).value();
        List<TaskStatus> named = new ArrayList<>();
        Matcher m = STATUS.matcher(jpql);
        while (m.find()) named.add(TaskStatus.valueOf(m.group(1)));
        assertThat(named).isNotEmpty().doesNotHaveDuplicates();

        Set<TaskStatus> union = EnumSet.copyOf(AppLogPolicy.VIEWABLE_STATUSES);
        union.addAll(AppLogPolicy.IN_FLIGHT_EXCLUDED);
        assertThat(union).containsExactlyInAnyOrderElementsOf(named);

        Set<TaskStatus> intersection = EnumSet.copyOf(AppLogPolicy.VIEWABLE_STATUSES);
        intersection.retainAll(AppLogPolicy.IN_FLIGHT_EXCLUDED);
        assertThat(intersection).isEmpty();
    }

    @Test
    void 컨테이너_이름() {
        assertThat(AppLogPolicy.containerName(42)).isEqualTo("netis-task-42").isEqualTo(DockerGcPlanner.OWN_PREFIX + 42);
    }
}
