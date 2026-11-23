package com.hamonsoft.netismaker.service.suggest;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.RepoCatalogService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 질문 세션 대화 중 제안 후보를 계산하는 단 한 곳 — claim 페이로드, 러너 보고 검증, 화면 선택지가 모두 이걸 쓴다.
 * 접속정보·카탈로그 description은 만지지도 싣지도 않는다(DB는 id·name·dbType, 레포는 id·alias·hint·defaultBranch만).
 */
@Service
@Profile("api")
public class QuestionSuggestCandidates {

    /** hint는 관리자가 적은 route_hint 그대로(비면 null) — description은 일부러 없다. */
    public record RefCandidate(long catalogId, String alias, String hint, String defaultBranch) {}

    public record Candidates(List<DbConnectionDto.Chip> db, List<RefCandidate> refs, int dbSlotsLeft, int refSlotsLeft) {
        public boolean isEmpty() {
            return db.isEmpty() && refs.isEmpty();
        }
    }

    private final DbConnectionService dbConnections;
    private final RepoCatalogService catalog;

    public QuestionSuggestCandidates(DbConnectionService dbConnections, RepoCatalogService catalog) {
        this.dbConnections = dbConnections;
        this.catalog = catalog;
    }

    /** 세션에 더 붙일 수 있는 DB 연결 수(상한 − 붙은 수, 0 이상). 후보 계산 없이 화면 뷰도 이 값을 쓴다. */
    public static int dbSlotsLeft(InterviewSession s) {
        return Math.max(0, DbConnectionService.MAX_SELECTION - idSet(s.getDbConnectionIds()).size());
    }

    /** 세션에 더 붙일 수 있는 참조 repo 수(상한 − 붙은 수, 0 이상). */
    public static int refSlotsLeft(InterviewSession s) {
        return Math.max(0, RepoCatalogService.MAX_REF_REPOS - (s.getRefRepos() == null ? 0 : s.getRefRepos().size()));
    }

    public Candidates forSession(InterviewSession s) {
        Set<Long> attachedDb = idSet(s.getDbConnectionIds());
        List<RefRepo> attachedRefs = s.getRefRepos() == null ? List.of() : s.getRefRepos();
        int dbSlotsLeft = dbSlotsLeft(s);
        int refSlotsLeft = refSlotsLeft(s);

        List<DbConnectionDto.Chip> db = List.of();
        // 연결 선택지는 요청자 기준 — 관리자가 남의 질문을 봐도 남의 개인 연결이 나오지 않는다
        if (dbSlotsLeft > 0 && dbConnections.isEnabled()) {
            db = dbConnections.selectableChips(s.getRepoCatalogId(), s.getRequesterId()).stream()
                    .filter(c -> !attachedDb.contains(c.id()))
                    .toList();
        }

        List<RefCandidate> refs = List.of();
        if (refSlotsLeft > 0) {
            Set<Long> attachedRefIds = new HashSet<>();
            for (RefRepo r : attachedRefs) attachedRefIds.add(r.catalogId());
            refs = catalog.listEnabled().stream()
                    .filter(e -> !Objects.equals(e.getId(), s.getRepoCatalogId()))
                    .filter(e -> !attachedRefIds.contains(e.getId()))
                    .filter(catalog::isReferenceable)
                    .map(QuestionSuggestCandidates::toCandidate)
                    .toList();
        }
        return new Candidates(db, refs, dbSlotsLeft, refSlotsLeft);
    }

    private static RefCandidate toCandidate(RepoCatalogEntry e) {
        String hint = e.getRouteHint();
        return new RefCandidate(e.getId(), e.getAlias(), hint == null || hint.isBlank() ? null : hint,
                e.getDefaultBranch());
    }

    /** JSONB 왕복으로 Integer·Long이 섞일 수 있어 long 값으로 비교한다. */
    private static Set<Long> idSet(List<?> ids) {
        Set<Long> out = new HashSet<>();
        if (ids == null) return out;
        for (Object o : ids) {
            if (o instanceof Number n) out.add(n.longValue());
        }
        return out;
    }
}
