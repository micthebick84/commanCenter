package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DbConnectionServiceTest {

    private static final String SALT = "0123456789abcdef";
    private DbConnectionRepository repo;
    private RepoCatalogRepository repoCatalogRepo;
    private DbConnectionTester tester;
    private DbSecretCipher cipher;
    private DbConnectionService service;

    @BeforeEach
    void setUp() {
        repo = mock(DbConnectionRepository.class);
        repoCatalogRepo = mock(RepoCatalogRepository.class);
        tester = mock(DbConnectionTester.class);
        cipher = new DbSecretCipher("test-key", SALT);
        service = new DbConnectionService(repo, cipher, tester, repoCatalogRepo);
        when(repo.save(any())).thenAnswer(i -> {
            DbConnection c = i.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", 42L);
            return c;
        });
        when(repoCatalogRepo.existsById(1L)).thenReturn(true);
        when(tester.test(any())).thenReturn(new DbConnectionTester.Result(true, "접속 성공"));
    }

    private DbConnection row(long id, DbConnectionScope scope, String owner, long repoId, boolean enabled) {
        DbConnection c = DbConnection.create(scope, repoId, owner, "conn" + id, DbType.POSTGRESQL, "db.local", 5432,
                "app", "reader", cipher.encrypt("pw" + id), "x");
        ReflectionTestUtils.setField(c, "id", id);
        c.setEnabled(enabled);
        when(repo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private static DbConnectionDto.UpsertRequest upsert(DbConnectionScope scope, String password) {
        return new DbConnectionDto.UpsertRequest(scope, 1L, "운영 DB", DbType.MYSQL, "db.local", 3306, "app",
                "reader", password, null);
    }

    private static void assertStatus(Runnable r, HttpStatus status) {
        assertThatThrownBy(r::run).isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(status));
    }

    @Test
    void list_shows_repo_rows_and_only_my_user_rows() {
        DbConnection pub = row(1, DbConnectionScope.REPO, null, 1, true);
        DbConnection off = row(2, DbConnectionScope.REPO, null, 1, false);
        DbConnection mine = row(3, DbConnectionScope.USER, "user1", 1, true);
        DbConnection other = row(4, DbConnectionScope.USER, "user2", 1, true);
        when(repo.findByRepoCatalogIdOrderByScopeAscNameAsc(1L)).thenReturn(List.of(pub, off, mine, other));

        DbConnectionDto.ListResponse user = service.list(1L, "user1", false);
        assertThat(user.enabled()).isTrue();
        assertThat(user.items()).extracting(DbConnectionDto.View::id).containsExactly(1L, 3L);
        assertThat(user.items().get(1).mine()).isTrue();

        // 관리자는 비활성 REPO도 보지만 남의 USER는 못 본다
        assertThat(service.list(1L, "admin1", true).items()).extracting(DbConnectionDto.View::id).containsExactly(1L, 2L);
    }

    @Test
    void list_when_disabled_returns_enabled_false() {
        service = new DbConnectionService(repo, new DbSecretCipher("", ""), tester, repoCatalogRepo);
        assertThat(service.list(1L, "user1", false)).isEqualTo(new DbConnectionDto.ListResponse(false, List.of()));
        assertStatus(() -> service.create(upsert(DbConnectionScope.USER, "pw"), "user1", false), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void create_user_row_sets_owner_and_stores_only_ciphertext() {
        DbConnection c = service.create(upsert(DbConnectionScope.USER, "pl@in"), "user1", false);
        assertThat(c.getOwnerUserId()).isEqualTo("user1");
        assertThat(c.getPasswordEnc()).isNotEqualTo("pl@in");
        assertThat(cipher.tryDecrypt(c.getPasswordEnc())).contains("pl@in");
    }

    @Test
    void create_repo_row_requires_admin_and_has_no_owner() {
        assertStatus(() -> service.create(upsert(DbConnectionScope.REPO, "pw"), "user1", false), HttpStatus.FORBIDDEN);
        assertThat(service.create(upsert(DbConnectionScope.REPO, "pw"), "admin1", true).getOwnerUserId()).isNull();
    }

    @Test
    void create_rejects_blank_password_and_unknown_repo() {
        assertStatus(() -> service.create(upsert(DbConnectionScope.USER, " "), "user1", false), HttpStatus.BAD_REQUEST);
        DbConnectionDto.UpsertRequest otherRepo = new DbConnectionDto.UpsertRequest(DbConnectionScope.USER, 99L, "x",
                DbType.MYSQL, "h", 3306, "app", "u", "pw", null);
        assertStatus(() -> service.create(otherRepo, "user1", false), HttpStatus.BAD_REQUEST);
    }

    @Test
    void update_blank_password_keeps_ciphertext_and_other_users_row_is_404() {
        DbConnection mine = row(3, DbConnectionScope.USER, "user1", 1, true);
        String before = mine.getPasswordEnc();
        service.update(3L, upsert(DbConnectionScope.USER, ""), "user1", false);
        assertThat(mine.getPasswordEnc()).isEqualTo(before);
        assertThat(mine.getDbType()).isEqualTo(DbType.MYSQL);

        row(4, DbConnectionScope.USER, "user2", 1, true);
        assertStatus(() -> service.update(4L, upsert(DbConnectionScope.USER, ""), "user1", false), HttpStatus.NOT_FOUND);
        row(1, DbConnectionScope.REPO, null, 1, true);
        assertStatus(() -> service.update(1L, upsert(DbConnectionScope.REPO, ""), "user1", false), HttpStatus.FORBIDDEN);
    }

    @Test
    void test_by_id_requires_edit_permission() {
        // Review Focus 1: 남의 공용 접속정보 id + 자기 host로 저장 비밀번호를 빼내는 경로
        row(1, DbConnectionScope.REPO, null, 1, true);
        DbConnectionDto.TestRequest req = new DbConnectionDto.TestRequest(1L, null, "attacker.example", null, null, null, null);
        assertStatus(() -> service.test(req, "user1", false), HttpStatus.FORBIDDEN);
        verify(tester, never()).test(any());
    }

    @Test
    void test_by_id_merges_form_values_with_stored_password() {
        row(3, DbConnectionScope.USER, "user1", 1, true);
        service.test(new DbConnectionDto.TestRequest(3L, null, "new-host", 6543, null, null, ""), "user1", false);
        ArgumentCaptor<DbConnectionTester.Target> cap = ArgumentCaptor.forClass(DbConnectionTester.Target.class);
        verify(tester).test(cap.capture());
        assertThat(cap.getValue()).isEqualTo(new DbConnectionTester.Target(DbType.POSTGRESQL, "new-host", 6543,
                "app", "reader", "pw3"));
    }

    @Test
    void test_unsaved_requires_all_fields() {
        assertStatus(() -> service.test(new DbConnectionDto.TestRequest(null, DbType.MYSQL, "h", 3306, "app", "u", null),
                "user1", false), HttpStatus.BAD_REQUEST);
        assertThat(service.test(new DbConnectionDto.TestRequest(null, DbType.MYSQL, "h", 3306, "app", "u", "pw"),
                "user1", false).ok()).isTrue();
    }

    @Test
    void validate_selection_rules() {
        row(1, DbConnectionScope.REPO, null, 1, true);
        row(3, DbConnectionScope.USER, "user1", 1, true);
        row(4, DbConnectionScope.USER, "user2", 1, true);
        row(5, DbConnectionScope.REPO, null, 2, true);
        row(6, DbConnectionScope.REPO, null, 1, false);
        row(7, DbConnectionScope.USER, "user1", 1, true);
        row(8, DbConnectionScope.USER, "user1", 1, true);
        when(repo.findAllById(any())).thenAnswer(i -> {
            List<DbConnection> out = new java.util.ArrayList<>();
            for (Long id : (Iterable<Long>) i.getArgument(0)) repo.findById(id).ifPresent(out::add);
            return out;
        });

        assertThat(service.validateSelection(1L, "user1", null)).isEmpty();
        assertThat(service.validateSelection(1L, "user1", List.of(3L, 1L, 3L))).containsExactly(3L, 1L);
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(4L)), HttpStatus.BAD_REQUEST); // 남의 USER
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(5L)), HttpStatus.BAD_REQUEST); // 다른 레포
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(6L)), HttpStatus.BAD_REQUEST); // 비활성
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(99L)), HttpStatus.BAD_REQUEST); // 없음
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(1L, 3L, 7L, 8L)), HttpStatus.BAD_REQUEST); // 상한 3
    }

    @Test
    void validate_selection_when_disabled_is_400_not_503() {
        // 선택 검증의 "기능 켜짐" 위반은 400(스펙 §5.3) — CRUD/test 엔드포인트의 503과 구분
        service = new DbConnectionService(repo, new DbSecretCipher("", ""), tester, repoCatalogRepo);
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(1L)), HttpStatus.BAD_REQUEST);
        assertThat(service.validateSelection(1L, "user1", null)).isEmpty();
        assertThat(service.validateSelection(1L, "user1", List.of())).isEmpty();
    }

    @Test
    void chips_keep_request_order_and_skip_missing() {
        row(1, DbConnectionScope.REPO, null, 1, true);
        row(3, DbConnectionScope.USER, "user1", 1, true);
        when(repo.findAllById(any())).thenAnswer(i -> List.of(repo.findById(1L).orElseThrow(), repo.findById(3L).orElseThrow()));
        assertThat(service.chipsFor(List.of(3, 99, 1)))
                .containsExactly(new DbConnectionDto.Chip(3, "conn3", "POSTGRESQL"), new DbConnectionDto.Chip(1, "conn1", "POSTGRESQL"));
    }

    @Test
    void resolve_for_claim_decrypts_and_reports_unusable_rows() {
        row(1, DbConnectionScope.REPO, null, 1, true);
        DbConnection broken = row(2, DbConnectionScope.REPO, null, 1, true);
        broken.setPasswordEnc(new DbSecretCipher("other-key", SALT).encrypt("x"));
        row(6, DbConnectionScope.REPO, null, 1, false);

        DbConnectionService.ClaimDb db = service.resolveForClaim(List.of(1L, 2L, 6L, 99L));
        assertThat(db.refs()).hasSize(1);
        var ref = db.refs().get(0);
        assertThat(ref.serverName()).isEqualTo("db-1");
        assertThat(ref.label()).isEqualTo("conn1 (PostgreSQL)");
        assertThat(ref.password()).isEqualTo("pw1");
        assertThat(db.notices()).hasSize(3);
        assertThat(db.notices().get(0)).contains("conn2").contains("다시 저장");
        assertThat(String.join("\n", db.notices())).doesNotContain("pw");
    }

    @Test
    void resolve_for_claim_when_disabled_gives_single_notice() {
        service = new DbConnectionService(repo, new DbSecretCipher("", ""), tester, repoCatalogRepo);
        DbConnectionService.ClaimDb db = service.resolveForClaim(List.of(1L));
        assertThat(db.refs()).isEmpty();
        assertThat(db.notices()).containsExactly("DB 접속정보 기능이 꺼져 있어 DB 도구를 붙이지 않았습니다");
        assertThat(service.resolveForClaim(List.of()).notices()).isEmpty();
    }
}
