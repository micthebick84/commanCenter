package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.controller.WorkerHealthController;
import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import com.hamonsoft.netismaker.service.DeployLogStreamService;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 하트비트가 claude 로그인 판정을 싣는다 (스펙 2026-11-02 §4). */
class WorkerMainLoopHeartbeatTest {

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("win-worker-1", "0.1.0", null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
    }

    private static WorkerMainLoop loop(WorkerHttpClient http, ClaudeAuthProbe probe) {
        WorkerMcpSupport mcps = mock(WorkerMcpSupport.class);
        when(mcps.getServerNames()).thenReturn(List.of("obsidian"));
        return new WorkerMainLoop(props(), http, mock(GitRepoCache.class), mock(ClaudeExecAdapter.class),
                mock(PromptResultParser.class), mcps, mock(WorktreeService.class),
                mock(GitOpsService.class), mock(DeployService.class), mock(ResultReporter.class),
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(""), probe);
    }

    private static WorkerHeartbeatRequest sentHeartbeat(WorkerHttpClient http) {
        ArgumentCaptor<WorkerHeartbeatRequest> cap = ArgumentCaptor.forClass(WorkerHeartbeatRequest.class);
        verify(http).heartbeat(cap.capture());
        return cap.getValue();
    }

    @Test
    void heartbeat_carries_current_probe_status() {
        WorkerHttpClient http = mock(WorkerHttpClient.class);
        ClaudeAuthProbe probe = mock(ClaudeAuthProbe.class);
        Instant exp = Instant.parse("2026-11-04T02:00:00Z");
        when(probe.current()).thenReturn(new ClaudeAuthProbe.Status(true, ClaudeAuthProbe.Method.CREDENTIALS, exp));

        loop(http, probe).sendHeartbeat();

        WorkerHeartbeatRequest req = sentHeartbeat(http);
        assertThat(req.workerId()).isEqualTo("win-worker-1");
        assertThat(req.claudeSessionOk()).isTrue();
        assertThat(req.claudeAuthMethod()).isEqualTo("CREDENTIALS");
        assertThat(req.claudeAuthExpiresAt()).isEqualTo(exp.atOffset(ZoneOffset.UTC));
        assertThat(req.mcps()).containsExactly("obsidian");
        verify(probe, never()).refresh();   // 하트비트는 판정하지 않고 읽기만 한다
    }

    @Test
    void heartbeat_before_first_check_sends_nulls() {
        WorkerHttpClient http = mock(WorkerHttpClient.class);
        ClaudeAuthProbe probe = mock(ClaudeAuthProbe.class);
        when(probe.current()).thenReturn(ClaudeAuthProbe.Status.UNKNOWN);

        loop(http, probe).sendHeartbeat();

        WorkerHeartbeatRequest req = sentHeartbeat(http);
        assertThat(req.claudeSessionOk()).isNull();
        assertThat(req.claudeAuthMethod()).isNull();
        assertThat(req.claudeAuthExpiresAt()).isNull();
    }

    /**
     * 11-02 이 PC 상태(CLI가 갱신에 실패해 토큰을 비운 credentials 파일) → 워커 판정 → 하트비트 → API 저장 → 관리 화면 API.
     * 관리 화면은 claudeSessionOk=false를 빨간 칩으로 그린다(frontend/test/admin-workers-claude-auth.spec.ts).
     */
    @Test
    void logged_out_pc_reaches_admin_health_as_not_ok(@TempDir Path claudeDir) throws Exception {
        Files.writeString(claudeDir.resolve(".credentials.json"), """
                {"claudeAiOauth":{"accessToken":"","refreshToken":"","expiresAt":0,
                 "refreshTokenExpiresAt":1793413425460,"scopes":["user:inference"]}}
                """);
        ClaudeAuthProbe probe = new ClaudeAuthProbe(mock(ClaudeExecAdapter.class));
        probe.env = Map.of("CLAUDE_CONFIG_DIR", claudeDir.toString());
        probe.clock = Clock.fixed(Instant.parse("2026-11-02T01:20:00Z"), ZoneOffset.UTC);
        probe.refresh();

        WorkerHttpClient http = mock(WorkerHttpClient.class);
        loop(http, probe).sendHeartbeat();
        WorkerHeartbeatRequest req = sentHeartbeat(http);

        WorkerHeartbeatRepository repo = mock(WorkerHeartbeatRepository.class);
        when(repo.findById(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        new WorkerService(null, null, null, null, null, null, repo, mock(DeployLogStreamService.class), null, null)
                .heartbeat(req);
        ArgumentCaptor<WorkerHeartbeat> saved = ArgumentCaptor.forClass(WorkerHeartbeat.class);
        verify(repo).save(saved.capture());
        when(repo.findAll()).thenReturn(List.of(saved.getValue()));

        Map<String, Object> row = new WorkerHealthController(repo).health().get(0);
        assertThat(row.get("workerId")).isEqualTo("win-worker-1");
        assertThat(row.get("alive")).isEqualTo(true);
        assertThat(row.get("claudeSessionOk")).isEqualTo(false);
        assertThat(row.get("claudeAuthMethod")).isEqualTo("CREDENTIALS");
        assertThat(row.get("claudeAuthExpiresAt"))
                .isEqualTo(Instant.ofEpochMilli(1793413425460L).atOffset(ZoneOffset.UTC));
    }
}
