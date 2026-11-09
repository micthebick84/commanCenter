package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dbmcp.DbWriteProperties;
import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskDbObjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 연결 단위 쓰기 허용 (스펙 2026-11-03 §6.1 조건 4). 저장된 행은 접속 대상 host=db.local:5432/app, 계정 writer.
 * Review Focus 1: 이름 변경·같은 값 재전송·공백 차이는 쓰기 허용과 소유 기록을 지키고, 접속 대상이 바뀔 때만 끈다.
 */
class DbConnectionServiceAllowWriteTest {

    private static final String SALT = "0123456789abcdef";

    private DbConnectionRepository repo;
    private RepoCatalogRepository repoCatalogRepo;
    private TaskDbObjectRepository dbObjects;
    private DbSecretCipher cipher;

    @BeforeEach
    void setUp() {
        repo = mock(DbConnectionRepository.class);
        repoCatalogRepo = mock(RepoCatalogRepository.class);
        dbObjects = mock(TaskDbObjectRepository.class);
        cipher = new DbSecretCipher("test-key", SALT);
        when(repo.save(any())).thenAnswer(i -> {
            DbConnection c = i.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", 42L);
            return c;
        });
        when(repoCatalogRepo.existsById(1L)).thenReturn(true);
    }

    private DbConnectionService service(boolean writeSwitch) {
        return new DbConnectionService(repo, cipher, mock(DbConnectionTester.class), repoCatalogRepo, dbObjects,
                new DbWriteProperties(writeSwitch, 1000, 10000));
    }

    private DbConnection row(long id, DbConnectionScope scope, boolean allowWrite) {
        DbConnection c = DbConnection.create(scope, 1L, scope == DbConnectionScope.USER ? "user1" : null, "개발DB",
                DbType.POSTGRESQL, "db.local", 5432, "app", "writer", cipher.encrypt("pw" + id), "admin1");
        ReflectionTestUtils.setField(c, "id", id);
        c.setAllowWrite(allowWrite);
        when(repo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private static DbConnectionDto.UpsertRequest upsert(String name, DbType type, String host, int port, String db,
                                                        String user, Boolean enabled) {
        return new DbConnectionDto.UpsertRequest(DbConnectionScope.REPO, 1L, name, type, host, port, db, user, "", enabled);
    }

    private static DbConnectionDto.UpsertRequest same() {
        return upsert("개발DB", DbType.POSTGRESQL, "db.local", 5432, "app", "writer", null);
    }

    private static void assertStatus(Runnable r, HttpStatus status) {
        assertThatThrownBy(r::run).isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(status));
    }

    // ── setAllowWrite ─────────────────────────────────────────────────────────

    @Test
    void 관리자는_REPO_연결의_쓰기허용을_켠다() {
        DbConnection c = row(7, DbConnectionScope.REPO, false);
        DbConnectionService s = service(true);

        assertThat(s.setAllowWrite(7L, true)).isSameAs(c);
        assertThat(c.isAllowWrite()).isTrue();
        assertThat(s.setAllowWrite(7L, false).isAllowWrite()).isFalse();
    }

    @Test
    void USER_연결은_400_없는_연결은_404_기능이_꺼져_있으면_503() {
        DbConnection mine = row(8, DbConnectionScope.USER, false);
        assertThatThrownBy(() -> service(true).setAllowWrite(8L, true))
                .isInstanceOf(TaskException.class)
                .hasMessage("개인 연결에는 쓰기 허용을 켤 수 없습니다")
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(mine.isAllowWrite()).isFalse();

        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertStatus(() -> service(true).setAllowWrite(99L, true), HttpStatus.NOT_FOUND);

        row(7, DbConnectionScope.REPO, false);
        cipher = new DbSecretCipher("", "");
        assertStatus(() -> service(true).setAllowWrite(7L, true), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void 전역_스위치가_꺼져_있어도_연결_표시는_바꿀_수_있다() {
        // 스위치는 쓰기가 열리는 조건 1이지 표시를 바꾸는 권한이 아니다 — 배포 전에 미리 켜 둘 수 있어야 한다(§9 5단계)
        DbConnection c = row(7, DbConnectionScope.REPO, false);
        assertThat(service(false).setAllowWrite(7L, true).isAllowWrite()).isTrue();
        assertThat(c.isAllowWrite()).isTrue();
    }

    // ── update: 접속 대상 ────────────────────────────────────────────────────

    static Stream<DbConnectionDto.UpsertRequest> 접속대상이_다른_요청() {
        return Stream.of(
                upsert("개발DB", DbType.MYSQL, "db.local", 5432, "app", "writer", null),
                upsert("개발DB", DbType.POSTGRESQL, "prod.local", 5432, "app", "writer", null),
                upsert("개발DB", DbType.POSTGRESQL, "db.local", 5433, "app", "writer", null),
                upsert("개발DB", DbType.POSTGRESQL, "db.local", 5432, "prod", "writer", null),
                upsert("개발DB", DbType.POSTGRESQL, "db.local", 5432, "app", "admin", null));
    }

    @ParameterizedTest
    @MethodSource("접속대상이_다른_요청")
    void 접속대상이_바뀌면_쓰기허용을_끄고_소유기록을_지운다(DbConnectionDto.UpsertRequest req) {
        DbConnection c = row(7, DbConnectionScope.REPO, true);

        service(true).update(7L, req, "admin1", true);

        assertThat(c.isAllowWrite()).isFalse();
        verify(dbObjects).deleteByConnectionId(7L);
    }

    @Test
    void 접속대상이_바뀌면_표시가_이미_꺼져_있어도_소유기록은_지운다() {
        // 꺼 둔 사이에 대상을 바꾸고 다시 켜면 옛 DB의 소유 기록이 새 DB의 같은 이름 테이블에 적용된다
        DbConnection c = row(7, DbConnectionScope.REPO, false);

        service(true).update(7L, upsert("개발DB", DbType.POSTGRESQL, "prod.local", 5432, "app", "writer", null),
                "admin1", true);

        assertThat(c.isAllowWrite()).isFalse();
        verify(dbObjects).deleteByConnectionId(7L);
    }

    @Test
    void 이름만_바꾸거나_같은값_재전송이나_공백만_다르면_유지() {
        DbConnection c = row(7, DbConnectionScope.REPO, true);
        DbConnectionService s = service(true);

        s.update(7L, upsert("새 이름", DbType.POSTGRESQL, "db.local", 5432, "app", "writer", null), "admin1", true);
        assertThat(c.getName()).isEqualTo("새 이름");

        s.update(7L, same(), "admin1", true);                                   // 같은 값 재전송
        s.update(7L, upsert("개발DB", DbType.POSTGRESQL, "db.local", 5432, "app", "writer", false), "admin1", true);   // 활성 토글
        s.update(7L, upsert("개발DB", DbType.POSTGRESQL, "  db.local ", 5432, " app", "writer\t", true), "admin1", true); // 공백만 다름
        s.update(7L, new DbConnectionDto.UpsertRequest(DbConnectionScope.REPO, 1L, "개발DB", DbType.POSTGRESQL,
                "db.local", 5432, "app", "writer", "new-secret", null), "admin1", true);                   // 비밀번호만 변경(같은 DB)

        assertThat(c.isAllowWrite()).isTrue();
        verify(dbObjects, never()).deleteByConnectionId(any());
    }

    @Test
    void 일반_수정으로는_쓰기허용을_켜지_않는다() {
        DbConnection c = row(7, DbConnectionScope.REPO, false);
        service(true).update(7L, same(), "admin1", true);
        assertThat(c.isAllowWrite()).isFalse();
    }

    @Test
    void sameTarget은_strip한_다섯_값만_비교한다() {
        DbConnection c = row(7, DbConnectionScope.REPO, true);
        assertThat(DbConnectionService.sameTarget(c, same())).isTrue();
        assertThat(DbConnectionService.sameTarget(c, upsert("다른 이름", DbType.POSTGRESQL, " db.local ", 5432, "app ", " writer", false))).isTrue();
        assertThat(DbConnectionService.sameTarget(c, upsert("개발DB", DbType.POSTGRESQL, "DB.LOCAL", 5432, "app", "writer", null)))
                .as("대소문자 차이도 다른 대상으로 본다(끄는 쪽이 안전)").isFalse();
        assertThat(DbConnectionService.sameTarget(c, upsert("개발DB", DbType.MARIADB, "db.local", 5432, "app", "writer", null))).isFalse();
    }

    // ── 일반 등록 바디 ────────────────────────────────────────────────────────

    @Test
    void 일반_등록_바디로는_켤_수_없다() throws Exception {
        // 요청 레코드에 칸이 없고, 바디에 allowWrite를 실어 보내도(알 수 없는 속성은 무시) 켜지지 않는다.
        assertThat(Arrays.stream(DbConnectionDto.UpsertRequest.class.getRecordComponents()).map(rc -> rc.getName()))
                .doesNotContain("allowWrite");
        ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();
        DbConnectionDto.UpsertRequest req = json.readValue("{\"scope\":\"REPO\",\"repoCatalogId\":1,\"name\":\"x\","
                + "\"dbType\":\"POSTGRESQL\",\"host\":\"db.local\",\"port\":5432,\"databaseName\":\"app\","
                + "\"username\":\"writer\",\"password\":\"pw\",\"allowWrite\":true}", DbConnectionDto.UpsertRequest.class);

        DbConnection created = service(true).create(req, "admin1", true);
        assertThat(created.isAllowWrite()).isFalse();

        DbConnection existing = row(7, DbConnectionScope.REPO, false);
        service(true).update(7L, req, "admin1", true);
        assertThat(existing.isAllowWrite()).isFalse();
    }

    // ── 목록 ──────────────────────────────────────────────────────────────────

    private void catalog(boolean migrationManaged) {
        RepoCatalogEntry e = RepoCatalogEntry.create("레포", "https://github.com/acme/r.git", "github", "acme/r", "main", null, "admin1");
        e.setDbMigrationManaged(migrationManaged);
        when(repoCatalogRepo.findById(1L)).thenReturn(Optional.of(e));
    }

    @Test
    void 목록_writeEnabled는_스위치와_자동마이그레이션() {
        DbConnection w = row(7, DbConnectionScope.REPO, true);
        DbConnection r = row(8, DbConnectionScope.REPO, false);
        when(repo.findByRepoCatalogIdOrderByScopeAscNameAsc(1L)).thenReturn(List.of(w, r));

        catalog(false);
        DbConnectionDto.ListResponse on = service(true).list(1L, "user1", false);
        assertThat(on.enabled()).isTrue();
        assertThat(on.writeEnabled()).isTrue();
        assertThat(on.items()).extracting(DbConnectionDto.View::allowWrite).containsExactly(true, false);

        assertThat(service(false).list(1L, "user1", false).writeEnabled()).as("스위치 꺼짐").isFalse();

        catalog(true);
        assertThat(service(true).list(1L, "user1", false).writeEnabled()).as("자동 마이그레이션 레포").isFalse();

        when(repoCatalogRepo.findById(1L)).thenReturn(Optional.empty());
        assertThat(service(true).list(1L, "user1", false).writeEnabled()).as("카탈로그 항목 없음").isFalse();

        cipher = new DbSecretCipher("", "");
        assertThat(service(true).list(1L, "user1", false)).isEqualTo(new DbConnectionDto.ListResponse(false, List.of(), false));
    }

    @Test
    void ListResponse_2인자_호환은_writeEnabled_false() {
        assertThat(new DbConnectionDto.ListResponse(true, List.of()).writeEnabled()).isFalse();
    }

    // ── 주장(claim)용 이름 ───────────────────────────────────────────────────

    @Test
    void claimLabel은_이름_과_DB종류() {
        assertThat(DbConnectionService.claimLabel(row(7, DbConnectionScope.REPO, false))).isEqualTo("개발DB (PostgreSQL)");
    }
}
