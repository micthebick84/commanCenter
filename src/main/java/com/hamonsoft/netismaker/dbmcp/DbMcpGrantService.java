package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 구현·디자인 claim 보강 (스펙 2026-10-14 §6.2·§6.4). 쓸 수 있는 연결만 서버 목록에 넣고 그때만 토큰을 발급한다.
 * 비밀번호는 여기서 복호화되지만 응답(DbMcpGrant)에는 담지 않는다.
 * 구현 단계 쓰기(스펙 2026-11-03 §6.1): 쓰기를 아는 워커의 claim이면 연결마다 {@link DbWriteGate}로 writable을 판정하고,
 * 하나라도 열리면 쓰기 허가 토큰을 발급한다.
 */
@Service
@Profile("api")
public class DbMcpGrantService {

    private final TaskRepository taskRepo;
    private final DbConnectionService dbConnectionService;
    private final DbMcpTokenService tokenService;
    private final TaskStatusHistoryRepository historyRepo;
    private final DbWriteGate writeGate;

    public DbMcpGrantService(TaskRepository taskRepo, DbConnectionService dbConnectionService,
                             DbMcpTokenService tokenService, TaskStatusHistoryRepository historyRepo,
                             DbWriteGate writeGate) {
        this.taskRepo = taskRepo;
        this.dbConnectionService = dbConnectionService;
        this.tokenService = tokenService;
        this.historyRepo = historyRepo;
        this.writeGate = writeGate;
    }

    /** 쓰기를 모르는 워커의 claim — {@link #grantFor(Long, boolean)}의 false. */
    @Transactional
    public DbMcpGrant grantFor(Long taskId) {
        return grantFor(taskId, false);
    }

    /**
     * workerSupportsWrite: claim한 워커가 supports=dbWrite를 알렸는지(§6.1 조건 6). 거짓이면 판정 없이 모든 연결이 읽기 전용이다.
     * 각 연결의 writable = workerSupportsWrite && 판정(조건 1~5) 허용.
     */
    @Transactional
    public DbMcpGrant grantFor(Long taskId, boolean workerSupportsWrite) {
        Task t = taskRepo.findById(taskId).orElse(null);
        if (t == null || t.getDbConnectionIds() == null || t.getDbConnectionIds().isEmpty()) return null;
        if (t.getStatus() != TaskStatus.IMPLEMENTING && t.getStatus() != TaskStatus.DESIGNING) return null;

        DbConnectionService.ClaimDb db = dbConnectionService.resolveForClaim(t.getDbConnectionIds());
        List<DbMcpGrant.Server> servers = db.refs().stream()
                .map(r -> {
                    String id = r.serverName().substring(DbConnectionService.SERVER_PREFIX.length());
                    boolean writable = workerSupportsWrite && writeGate.check(t, true, Long.parseLong(id)).allowed();
                    return new DbMcpGrant.Server(r.serverName(), "/mcp/db/" + id, r.label(), r.dbType(), writable);
                })
                .toList();
        String token;
        if (servers.isEmpty()) {
            // 새 토큰을 안 주면 같은 단계의 이전 토큰(최신·만료 전)이 그대로 유효하다 — 재claim에서 되살아나지 않게 지운다.
            tokenService.revokeAll(t.getId());
            token = null;
        } else {
            token = tokenService.issue(t, servers.stream().anyMatch(DbMcpGrant.Server::writable));
        }
        if (!db.notices().isEmpty()) {
            String reason = "DB 연결 일부 사용 불가: " + String.join(" / ", db.notices());
            historyRepo.save(TaskStatusHistory.log(t.getId(), t.getStatus(), t.getStatus(), "system", null,
                    reason.length() > 500 ? reason.substring(0, 500) : reason));
        }
        return new DbMcpGrant(token, servers, db.notices());
    }
}
