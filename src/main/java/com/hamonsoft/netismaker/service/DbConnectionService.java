package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * DB 접속정보 (스펙 2026-10-02 §5). 보이는 범위: REPO = 그 레포를 고르는 모든 사용자(비활성은 관리자만),
 * USER = 본인만(관리자도 남의 USER는 못 본다 — 존재 노출 방지로 404). 편집: REPO = 관리자, USER = 본인.
 * 저장된 비밀번호를 쓰는 접속 테스트(id 지정)는 편집 권한자만 — 다른 host로 저장 비밀번호를 보내는 경로 차단.
 */
@Service
@Profile("api")
public class DbConnectionService {

    private static final Logger log = LoggerFactory.getLogger(DbConnectionService.class);
    public static final int MAX_SELECTION = 3;
    public static final String SERVER_PREFIX = "db-";

    private final DbConnectionRepository repo;
    private final DbSecretCipher cipher;
    private final DbConnectionTester tester;
    private final RepoCatalogRepository repoCatalogRepo;

    public DbConnectionService(DbConnectionRepository repo, DbSecretCipher cipher, DbConnectionTester tester,
                               RepoCatalogRepository repoCatalogRepo) {
        this.repo = repo;
        this.cipher = cipher;
        this.tester = tester;
        this.repoCatalogRepo = repoCatalogRepo;
    }

    /** claim 보강 결과 — refs는 복호화된 접속정보(내부 API 전용), notices는 프롬프트에 붙일 안내. */
    public record ClaimDb(List<InterviewClaimResponse.DbConnectionRef> refs, List<String> notices) {}

    public boolean isEnabled() {
        return cipher.isEnabled();
    }

    @Transactional(readOnly = true)
    public DbConnectionDto.ListResponse list(Long repoCatalogId, String viewerId, boolean isAdmin) {
        if (!cipher.isEnabled()) return new DbConnectionDto.ListResponse(false, List.of());
        List<DbConnectionDto.View> items = repo.findByRepoCatalogIdOrderByScopeAscNameAsc(repoCatalogId).stream()
                .filter(c -> canView(c, viewerId, isAdmin))
                .map(c -> DbConnectionDto.View.of(c, viewerId))
                .toList();
        return new DbConnectionDto.ListResponse(true, items);
    }

    @Transactional
    public DbConnection create(DbConnectionDto.UpsertRequest req, String actorId, boolean isAdmin) {
        requireEnabled();
        if (req.scope() == DbConnectionScope.REPO && !isAdmin) throw TaskException.forbidden();
        if (!repoCatalogRepo.existsById(req.repoCatalogId())) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "존재하지 않는 레포 카탈로그 id: " + req.repoCatalogId());
        }
        if (isBlank(req.password())) throw new TaskException(HttpStatus.BAD_REQUEST, "비밀번호를 입력하세요");
        DbConnection c = DbConnection.create(req.scope(), req.repoCatalogId(),
                req.scope() == DbConnectionScope.USER ? actorId : null,
                req.name().strip(), req.dbType(), req.host().strip(), req.port(), req.databaseName().strip(),
                req.username().strip(), cipher.encrypt(req.password()), actorId);
        if (req.enabled() != null) c.setEnabled(req.enabled());
        return repo.save(c);
    }

    @Transactional
    public DbConnection update(Long id, DbConnectionDto.UpsertRequest req, String actorId, boolean isAdmin) {
        requireEnabled();
        DbConnection c = requireEditable(id, actorId, isAdmin);
        c.setName(req.name().strip());
        c.setDbType(req.dbType());
        c.setHost(req.host().strip());
        c.setPort(req.port());
        c.setDatabaseName(req.databaseName().strip());
        c.setUsername(req.username().strip());
        if (!isBlank(req.password())) c.setPasswordEnc(cipher.encrypt(req.password()));
        if (req.enabled() != null) c.setEnabled(req.enabled());
        c.setUpdatedAt(OffsetDateTime.now());
        return c;
    }

    @Transactional
    public void delete(Long id, String actorId, boolean isAdmin) {
        requireEnabled();
        repo.delete(requireEditable(id, actorId, isAdmin));
    }

    /**
     * 의도적으로 @Transactional 없음 — 외부 JDBC 접속 시도(최대 ~20초) 동안 Hikari 커넥션을 붙들면 동시 테스트 몇 건에
     * 풀(10)이 고갈된다. 저장값 조회는 repo.findById의 자체 짧은 트랜잭션이고, 엔티티에 지연 연관이 없어 밖에서 써도 안전하다.
     */
    public DbConnectionDto.TestResult test(DbConnectionDto.TestRequest req, String actorId, boolean isAdmin) {
        requireEnabled();
        DbConnectionTester.Target target;
        if (req.id() != null) {
            DbConnection c = requireEditable(req.id(), actorId, isAdmin);
            String pw = isBlank(req.password())
                    ? cipher.tryDecrypt(c.getPasswordEnc()).orElseThrow(() -> new TaskException(HttpStatus.BAD_REQUEST,
                            "저장된 비밀번호를 복호화할 수 없습니다 — 비밀번호를 다시 입력하세요"))
                    : req.password();
            target = new DbConnectionTester.Target(
                    req.dbType() != null ? req.dbType() : c.getDbType(),
                    isBlank(req.host()) ? c.getHost() : req.host().strip(),
                    req.port() != null ? req.port() : c.getPort(),
                    isBlank(req.databaseName()) ? c.getDatabaseName() : req.databaseName().strip(),
                    isBlank(req.username()) ? c.getUsername() : req.username().strip(),
                    pw);
        } else {
            if (req.dbType() == null || isBlank(req.host()) || req.port() == null || isBlank(req.databaseName())
                    || isBlank(req.username()) || isBlank(req.password())) {
                throw new TaskException(HttpStatus.BAD_REQUEST, "접속 테스트에 필요한 값이 비어 있습니다");
            }
            target = new DbConnectionTester.Target(req.dbType(), req.host().strip(), req.port(),
                    req.databaseName().strip(), req.username().strip(), req.password());
        }
        log.info("DB 접속 테스트 요청자={} 대상={}", actorId, target);   // Target.toString은 비밀번호 제외
        DbConnectionTester.Result r = tester.test(target);
        return new DbConnectionDto.TestResult(r.ok(), r.message());
    }

    /**
     * 질문 등록/추가 질문의 선택 검증 — 기준은 세션 소유자(ownerId). 중복 제거, 순서 유지. 위반 시 400.
     * 기능 꺼짐도 선택 검증 기준 위반이라 400이다(스펙 §5.3) — CRUD/test 엔드포인트의 503과 다르다.
     */
    @Transactional(readOnly = true)
    public List<Long> validateSelection(Long repoCatalogId, String ownerId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        if (!cipher.isEnabled()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "DB 접속정보 기능이 꺼져 있어 DB 연결을 선택할 수 없습니다");
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).map(Number::longValue).distinct().toList();
        if (distinct.size() > MAX_SELECTION) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "DB 연결은 최대 " + MAX_SELECTION + "개까지 선택할 수 있습니다");
        }
        Map<Long, DbConnection> found = byId(distinct);
        for (Long id : distinct) {
            DbConnection c = found.get(id);
            boolean ok = c != null && Objects.equals(c.getRepoCatalogId(), repoCatalogId) && c.isEnabled()
                    && (c.getScope() == DbConnectionScope.REPO || c.isOwnedBy(ownerId));
            if (!ok) throw new TaskException(HttpStatus.BAD_REQUEST, "선택할 수 없는 DB 연결입니다: " + id);
        }
        return new ArrayList<>(distinct);
    }

    @Transactional(readOnly = true)
    public List<DbConnectionDto.Chip> chipsFor(List<? extends Number> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Long> longs = ids.stream().filter(Objects::nonNull).map(Number::longValue).toList();
        Map<Long, DbConnection> found = byId(longs);
        List<DbConnectionDto.Chip> out = new ArrayList<>();
        for (Long id : longs) {
            DbConnection c = found.get(id);
            if (c != null) out.add(new DbConnectionDto.Chip(id, c.getName(), c.getDbType().name()));
        }
        return out;
    }

    /** claim 보강 (스펙 §5.4). 사용할 수 없는 항목은 빼고 안내 문구만 남긴다 — 예외를 던지지 않는다. */
    @Transactional(readOnly = true)
    public ClaimDb resolveForClaim(List<? extends Number> ids) {
        if (ids == null || ids.isEmpty()) return new ClaimDb(List.of(), List.of());
        if (!cipher.isEnabled()) {
            return new ClaimDb(List.of(), List.of("DB 접속정보 기능이 꺼져 있어 DB 도구를 붙이지 않았습니다"));
        }
        List<InterviewClaimResponse.DbConnectionRef> refs = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        for (Number n : ids) {
            if (n == null) continue;
            long id = n.longValue();
            Optional<DbConnection> found = repo.findById(id);
            if (found.isEmpty() || !found.get().isEnabled()) {
                notices.add("DB 연결 #" + id + "를 사용할 수 없습니다 — 삭제되었거나 비활성화됨");
                continue;
            }
            DbConnection c = found.get();
            Optional<String> pw = cipher.tryDecrypt(c.getPasswordEnc());
            if (pw.isEmpty()) {
                log.warn("DB 접속정보 복호화 실패 id={}", id);
                notices.add("DB 연결 '" + c.getName() + "'를 복호화할 수 없습니다 — 접속정보를 다시 저장해야 합니다");
                continue;
            }
            refs.add(new InterviewClaimResponse.DbConnectionRef(SERVER_PREFIX + id,
                    c.getName() + " (" + c.getDbType().getLabel() + ")", c.getDbType().name(),
                    c.getHost(), c.getPort(), c.getDatabaseName(), c.getUsername(), pw.get()));
        }
        return new ClaimDb(refs, notices);
    }

    static boolean canView(DbConnection c, String viewerId, boolean isAdmin) {
        if (c.getScope() == DbConnectionScope.REPO) return c.isEnabled() || isAdmin;
        return c.isOwnedBy(viewerId);
    }

    static boolean canEdit(DbConnection c, String viewerId, boolean isAdmin) {
        return c.getScope() == DbConnectionScope.REPO ? isAdmin : c.isOwnedBy(viewerId);
    }

    private DbConnection requireEditable(Long id, String actorId, boolean isAdmin) {
        DbConnection c = repo.findById(id).filter(x -> canView(x, actorId, isAdmin))
                .orElseThrow(TaskException::notFound);
        if (!canEdit(c, actorId, isAdmin)) throw TaskException.forbidden();
        return c;
    }

    private Map<Long, DbConnection> byId(List<Long> ids) {
        Map<Long, DbConnection> m = new HashMap<>();
        for (DbConnection c : repo.findAllById(ids)) m.put(c.getId(), c);
        return m;
    }

    private void requireEnabled() {
        if (!cipher.isEnabled()) {
            throw new TaskException(HttpStatus.SERVICE_UNAVAILABLE, "DB 접속정보 기능이 꺼져 있습니다(NETISMAKER_DB_SECRET_KEY)");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
