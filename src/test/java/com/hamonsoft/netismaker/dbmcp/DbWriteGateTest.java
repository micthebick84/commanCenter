package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 구현 단계 DB 쓰기 판정 (스펙 2026-11-03 §6.1). 조건 1~6을 이 순서로 보고 걸린 조건의 사유 문구를 그대로 돌려준다.
 * 작업 42는 구현중이고 연결 7(쓰기 허용 REPO)·8을 골랐다. 레포 카탈로그 1은 자동 마이그레이션이 아니다.
 */
class DbWriteGateTest {

    private final DbConnectionRepository connections = mock(DbConnectionRepository.class);
    private final RepoCatalogRepository catalogs = mock(RepoCatalogRepository.class);
    private Task task;
    private RepoCatalogEntry catalog;
    private DbConnection dev;

    @BeforeEach
    void setUp() {
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 42L);
        task.setStatus(TaskStatus.IMPLEMENTING);
        task.setDbConnectionIds(new ArrayList<>(List.of(7L, 8L)));
        task.setRepoCatalogId(1L);
        catalog = RepoCatalogEntry.create("앱", "https://github.com/a/b", "github", "a/b", "main", null, "admin");
        ReflectionTestUtils.setField(catalog, "id", 1L);
        when(catalogs.findById(1L)).thenReturn(Optional.of(catalog));
        dev = connection(7L, DbConnectionScope.REPO, true);
    }

    private DbConnection connection(long id, DbConnectionScope scope, boolean allowWrite) {
        DbConnection c = DbConnection.create(scope, 1L, scope == DbConnectionScope.USER ? "user1" : null, "개발DB",
                DbType.POSTGRESQL, "db.local", 5432, "app", "writer", "enc", "admin1");
        ReflectionTestUtils.setField(c, "id", id);
        c.setAllowWrite(allowWrite);
        when(connections.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private DbWriteGate gate(boolean writeSwitch) {
        return new DbWriteGate(new DbWriteProperties(writeSwitch, 1000, 10000), connections, catalogs);
    }

    private static DbWriteGate.Check denied(String reason) {
        return new DbWriteGate.Check(false, reason);
    }

    @Test
    void 모두_참이면_허용() {
        assertThat(gate(true).check(task, true, dev)).isEqualTo(new DbWriteGate.Check(true, null));
        assertThat(gate(true).check(task, true, 7L).allowed()).isTrue();
    }

    @Test
    void 조건마다_거부사유() {
        // 1. 전역 스위치
        assertThat(gate(false).check(task, true, dev)).isEqualTo(denied("개발 DB 쓰기 스위치가 꺼져 있습니다"));
        // 2. 구현 단계만 — 디자인 토큰은 읽기만
        task.setStatus(TaskStatus.DESIGNING);
        assertThat(gate(true).check(task, true, dev)).isEqualTo(denied("구현 단계에서만 쓸 수 있습니다"));
        task.setStatus(TaskStatus.IMPLEMENTING);
        // 3. 그 작업이 고른 연결
        DbConnection other = connection(9L, DbConnectionScope.REPO, true);
        assertThat(gate(true).check(task, true, other)).isEqualTo(denied("이 작업이 고른 연결이 아닙니다"));
        assertThat(gate(true).check(task, true, 9L)).isEqualTo(denied("이 작업이 고른 연결이 아닙니다"));
        // 4. 쓰기 허용 연결 — allow_write · REPO · enabled
        DbConnection readOnly = connection(8L, DbConnectionScope.REPO, false);
        assertThat(gate(true).check(task, true, readOnly)).isEqualTo(denied("쓰기 허용 연결이 아닙니다"));
        DbConnection personal = connection(8L, DbConnectionScope.USER, false);
        ReflectionTestUtils.setField(personal, "allowWrite", true);   // DB CHECK가 막는 값이지만 판정도 REPO를 다시 본다
        assertThat(gate(true).check(task, true, personal)).isEqualTo(denied("쓰기 허용 연결이 아닙니다"));
        dev.setEnabled(false);
        assertThat(gate(true).check(task, true, dev)).isEqualTo(denied("쓰기 허용 연결이 아닙니다"));
        dev.setEnabled(true);
        // 5. 자동 마이그레이션 레포
        catalog.setDbMigrationManaged(true);
        assertThat(gate(true).check(task, true, dev))
                .isEqualTo(denied("자동 마이그레이션 레포입니다 — 마이그레이션 파일로 만드세요"));
        catalog.setDbMigrationManaged(false);
        // 6. 워커 지원
        assertThat(gate(true).check(task, false, dev)).isEqualTo(denied("이 워커는 DB 쓰기를 지원하지 않습니다"));
    }

    @Test
    void 앞_조건이_먼저_걸린다() {
        catalog.setDbMigrationManaged(true);
        dev.setAllowWrite(false);
        task.setStatus(TaskStatus.DESIGNING);
        // 스위치·단계·연결·레포·워커가 모두 거짓이어도 사유는 맨 앞 조건 하나
        assertThat(gate(false).check(task, false, dev).reason()).isEqualTo(DbWriteGate.SWITCH_OFF);
        assertThat(gate(true).check(task, false, dev).reason()).isEqualTo(DbWriteGate.IMPLEMENTING_ONLY);
        task.setStatus(TaskStatus.IMPLEMENTING);
        assertThat(gate(true).check(task, false, dev).reason()).isEqualTo(DbWriteGate.NOT_WRITABLE_CONNECTION);
        dev.setAllowWrite(true);
        assertThat(gate(true).check(task, false, dev).reason()).isEqualTo(DbWriteGate.MIGRATION_MANAGED);
    }

    @Test
    void 연결_행이_없으면_조건4로_거부() {
        assertThat(gate(true).check(task, true, (DbConnection) null)).isEqualTo(denied("쓰기 허용 연결이 아닙니다"));
        when(connections.findById(8L)).thenReturn(Optional.empty());
        assertThat(gate(true).check(task, true, 8L)).isEqualTo(denied("쓰기 허용 연결이 아닙니다"));
    }

    @Test
    void id판은_앞_조건에서_걸리면_연결을_읽지_않는다() {
        gate(false).check(task, true, 7L);
        task.setStatus(TaskStatus.DESIGNING);
        gate(true).check(task, true, 7L);
        task.setStatus(TaskStatus.IMPLEMENTING);
        gate(true).check(task, true, 9L);
        verify(connections, never()).findById(anyLong());
    }

    @Test
    void 넘겨받은_행만_보고_다시_읽지_않는다() {
        // 판정과 접속이 같은 행을 보게(Task 11) — 저장소의 행이 바뀌었어도 넘겨받은 행으로 판정한다
        DbConnection snapshot = connection(7L, DbConnectionScope.REPO, true);
        connection(7L, DbConnectionScope.REPO, false);
        assertThat(gate(true).check(task, true, snapshot).allowed()).isTrue();
        verify(connections, never()).findById(anyLong());
    }

    @Test
    void 연결판정은_스위치와_쓰기허용만_본다() {
        // 관리자 실행(Task 12)은 구현 단계 밖이라 조건 1·4만 다시 본다 — 같은 문구
        task.setStatus(TaskStatus.PR_CREATED);
        assertThat(gate(true).connectionCheck(dev)).isEqualTo(new DbWriteGate.Check(true, null));
        assertThat(gate(false).connectionCheck(dev)).isEqualTo(denied("개발 DB 쓰기 스위치가 꺼져 있습니다"));
        dev.setAllowWrite(false);
        assertThat(gate(true).connectionCheck(dev)).isEqualTo(denied("쓰기 허용 연결이 아닙니다"));
        assertThat(gate(true).connectionCheck(null)).isEqualTo(denied("쓰기 허용 연결이 아닙니다"));
        verifyNoInteractions(catalogs);
    }

    @Test
    void 인터뷰용_label은_단계와_워커를_보지_않는다() {
        task.setStatus(TaskStatus.INTERVIEWING);
        connection(8L, DbConnectionScope.REPO, false);
        when(connections.findAllById(any())).thenAnswer(inv -> {
            List<DbConnection> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) connections.findById((Long) id).ifPresent(out::add);
            return out;
        });

        assertThat(gate(true).writableLabels(task, List.of(7L, 8L))).containsExactly("개발DB (PostgreSQL)");
    }

    @Test
    void label은_스위치_선택_쓰기허용_자동마이그레이션을_본다() {
        when(connections.findAllById(any())).thenReturn(List.of(dev));
        assertThat(gate(false).writableLabels(task, List.of(7L))).isEmpty();
        assertThat(gate(true).writableLabels(task, List.of(9L))).isEmpty();   // 작업이 고르지 않은 연결
        assertThat(gate(true).writableLabels(task, null)).isEmpty();
        assertThat(gate(true).writableLabels(task, Arrays.asList(7L, null, 7L))).containsExactly("개발DB (PostgreSQL)");
        catalog.setDbMigrationManaged(true);
        assertThat(gate(true).writableLabels(task, List.of(7L))).isEmpty();
        catalog.setDbMigrationManaged(false);
        dev.setAllowWrite(false);
        assertThat(gate(true).writableLabels(task, List.of(7L))).isEmpty();
    }

    @Test
    void 자동마이그레이션은_작업의_카탈로그로_본다() {
        assertThat(gate(true).migrationManaged(task)).isFalse();
        catalog.setDbMigrationManaged(true);
        assertThat(gate(true).migrationManaged(task)).isTrue();
        // repo_catalog_id가 없으면 git_url로 찾는다(RepoCatalogRepository.findForTask)
        task.setRepoCatalogId(null);
        task.setGitUrl("https://github.com/a/b");
        when(catalogs.findByGitUrl("https://github.com/a/b")).thenReturn(Optional.of(catalog));
        assertThat(gate(true).migrationManaged(task)).isTrue();
        // 카탈로그 항목이 없으면 표시도 없다 — 그 레포의 연결은 카탈로그와 함께 지워져(ON DELETE CASCADE) 조건 4가 막는다
        task.setGitUrl(null);
        assertThat(gate(true).migrationManaged(task)).isFalse();
    }
}
