package com.hamonsoft.netismaker.service.deployconfig;

import com.hamonsoft.netismaker.dto.DeployOverrides;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployDbChoice;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.DbSecretCipher;
import com.hamonsoft.netismaker.service.deployconfig.DeployOverrideAssembler.ResolvedDb;
import com.hamonsoft.netismaker.service.deployconfig.DeployValueTemplates.DbValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 배포대기 claim이 커밋된 뒤 고른 DB 연결을 복호화해 {@link DeployOverrides}를 조립한다 (스펙 2026-11-24 §7.3).
 * 비밀번호는 로그·예외 메시지에 내보내지 않는다.
 */
@Service
@Profile("api")
public class DeployOverrideService {

    private static final Logger log = LoggerFactory.getLogger(DeployOverrideService.class);

    private final TaskRepository taskRepo;
    private final RepoCatalogRepository repoCatalogRepo;
    private final DbConnectionRepository dbConnectionRepo;
    private final DbSecretCipher cipher;
    private final TransactionTemplate tx;

    public DeployOverrideService(TaskRepository taskRepo, RepoCatalogRepository repoCatalogRepo,
                                 DbConnectionRepository dbConnectionRepo, DbSecretCipher cipher,
                                 PlatformTransactionManager txManager) {
        this.taskRepo = taskRepo;
        this.repoCatalogRepo = repoCatalogRepo;
        this.dbConnectionRepo = dbConnectionRepo;
        this.cipher = cipher;
        // 자체 읽기 전용 트랜잭션 — catch는 이 경계 밖에 둬야 시작·커밋 실패와 rollback-only 표시까지 잡는다
        this.tx = new TransactionTemplate(txManager);
        this.tx.setReadOnly(true);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * claim 응답에 실을 덮어쓰기. 배포 설정도 고른 연결도 없으면 null(지금 동작). 예외를 던지지 않는다 —
     * claim이 이미 커밋돼 던지면 작업이 배포중에 갇힌다.
     */
    public DeployOverrides forClaim(long taskId) {
        try {
            return tx.execute(s -> doForClaim(taskId));
        } catch (Exception e) {
            log.warn("배포 덮어쓰기 준비 실패 task={}: {}", taskId, e.getClass().getSimpleName());
            return DeployOverrides.failed("배포 덮어쓰기 준비 실패: " + e.getClass().getSimpleName());
        }
    }

    private DeployOverrides doForClaim(long taskId) {
        {
            Task t = taskRepo.findById(taskId).orElse(null);
            if (t == null) {
                return null;
            }
            DeployConfig cfg = RepoCatalogRepository.findForTask(repoCatalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                    .map(e -> e.getDeployConfig()).orElse(null);
            List<DeployDbChoice> selection = t.getDeployDbSelection();
            boolean chosen = hasChosen(selection);
            if (cfg == null) {
                return chosen ? DeployOverrides.failed("레포 카탈로그에 배포 설정이 없어 저장된 배포 DB를 적용할 수 없습니다"
                        + " — 배포 창에서 다시 고르세요") : null;
            }
            if (chosen && !cipher.isEnabled()) {
                return DeployOverrides.failed("DB 접속정보 기능이 꺼져 있어 배포 DB를 적용할 수 없습니다");
            }
            return DeployOverrideAssembler.assemble(cfg, t.getEnvVars(), selection, this::resolve);
        }
    }

    /** 고른 연결의 비밀번호를 로그 마스킹용 비밀 env(키 {@code deploy-db:<slot>})로. 실패는 빈 목록. */
    public List<EnvVar> secretsForMasking(Task t) {
        try {
            List<EnvVar> r = tx.execute(s -> doSecrets(t));
            return r == null ? new ArrayList<>() : r;
        } catch (Exception e) {
            log.warn("배포 DB 비밀 마스킹 목록 준비 실패: {}", e.getClass().getSimpleName());
            return new ArrayList<>();
        }
    }

    private List<EnvVar> doSecrets(Task t) {
        List<EnvVar> out = new ArrayList<>();
        {
            if (t == null || t.getDeployDbSelection() == null) {
                return out;
            }
            for (DeployDbChoice c : t.getDeployDbSelection()) {
                if (c == null || c.slot() == null || c.connectionId() == null) {
                    continue;
                }
                resolve(c.connectionId()).map(r -> r.values().password())
                        .filter(pw -> pw != null && !pw.isEmpty())
                        .ifPresent(pw -> {
                            out.add(new EnvVar("deploy-db:" + c.slot(), pw, true));
                            // {password:url} 템플릿이 만드는 퍼센트 인코딩 형태도 로그에서 가린다
                            String enc = DeployValueTemplates.urlEncode(pw);
                            if (!enc.equals(pw)) {
                                out.add(new EnvVar("deploy-db:" + c.slot() + ":url", enc, true));
                            }
                        });
            }
        }
        return out;
    }

    private static boolean hasChosen(List<DeployDbChoice> selection) {
        return selection != null && selection.stream().anyMatch(c -> c != null && c.connectionId() != null);
    }

    private Optional<ResolvedDb> resolve(Long id) {
        Optional<DbConnection> found = dbConnectionRepo.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DbConnection c = found.get();
        return cipher.tryDecrypt(c.getPasswordEnc()).map(pw -> new ResolvedDb(c.getId(), c.getName(), c.getDbType(),
                c.isEnabled(), new DbValues(c.getHost(), c.getPort(), c.getDatabaseName(), c.getUsername(), pw)));
    }
}
