package com.hamonsoft.netismaker.service.deployconfig;

import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployDbChoice;
import com.hamonsoft.netismaker.entity.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 배포 요청의 DB 선택 검증 (스펙 2026-11-24-deploy-db-connection-select).
 * 위반은 IllegalArgumentException — 호출부가 400으로 바꾼다. 통과하면 입력 순서 그대로(고르지 않음 포함) 돌려준다.
 */
public final class DeployDbSelectionValidator {

    private DeployDbSelectionValidator() {
    }

    /**
     * @param cfg    작업 레포 카탈로그의 배포 설정(없으면 null)
     * @param task   저장된 선택(이미 저장된 다른 관리자의 개인 연결 허용 판단)을 읽는다
     * @param catalogId 호출부가 {@code RepoCatalogRepository.findForTask}(id, 없으면 git URL)로 찾은 카탈로그 행의 id
     * @param lookup 연결 id → 연결
     */
    public static List<DeployDbChoice> validate(DeployConfig cfg, Task task, Long catalogId, String adminId,
                                                List<DeployDbChoice> in,
                                                Function<Long, Optional<DbConnection>> lookup) {
        List<DeployDbChoice> out = new ArrayList<>();
        if (in == null || in.isEmpty()) {
            return out;
        }
        Map<String, DbSlot> slots = new HashMap<>();
        if (cfg != null) {
            for (DbSlot s : cfg.databases()) {
                slots.put(s.slot(), s);
            }
        }
        boolean anyConnection = in.stream().anyMatch(c -> c != null && c.connectionId() != null);
        if (cfg == null && anyConnection) {
            throw bad("이 레포에는 배포 설정이 없어 DB를 고를 수 없습니다");
        }
        List<DeployDbChoice> saved = task.getDeployDbSelection() == null ? List.of() : task.getDeployDbSelection();
        Set<String> seen = new HashSet<>();
        for (DeployDbChoice c : in) {
            if (c == null || c.slot() == null) {
                throw bad("DB 자리 이름이 비어 있습니다");
            }
            DbSlot slot = slots.get(c.slot());
            if (slot == null) {
                throw bad("배포 설정에 없는 DB 자리입니다: " + c.slot());
            }
            if (!seen.add(c.slot())) {
                throw bad("같은 DB 자리를 두 번 골랐습니다: " + c.slot());
            }
            Long id = c.connectionId();
            if (id != null) {
                DbConnection conn = lookup.apply(id).orElseThrow(
                        () -> bad("DB 연결을 찾을 수 없습니다: " + id));
                if (catalogId == null || !catalogId.equals(conn.getRepoCatalogId())) {
                    throw bad("이 작업의 레포에 속하지 않는 DB 연결입니다: " + id);
                }
                if (!conn.isEnabled()) {
                    throw bad("사용 중지된 DB 연결입니다: " + id);
                }
                if (!slot.dbTypes().contains(conn.getDbType())) {
                    throw bad("DB 자리 '" + slot.slot() + "'에 맞지 않는 DB 종류입니다: " + conn.getDbType());
                }
                if (conn.getScope() != DbConnectionScope.REPO && !conn.isOwnedBy(adminId)
                        && !alreadySaved(saved, c.slot(), id)) {
                    throw bad("다른 사용자의 개인 DB 연결은 고를 수 없습니다: " + id);
                }
            }
            out.add(new DeployDbChoice(c.slot(), id));
        }
        return out;
    }

    private static boolean alreadySaved(List<DeployDbChoice> saved, String slot, Long id) {
        return saved.stream().anyMatch(s -> s != null && slot.equals(s.slot()) && id.equals(s.connectionId()));
    }

    private static IllegalArgumentException bad(String msg) {
        return new IllegalArgumentException(msg);
    }
}
