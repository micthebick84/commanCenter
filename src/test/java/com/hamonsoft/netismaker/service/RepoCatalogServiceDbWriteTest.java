package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 카탈로그 DB 스크립트 폴더·자동 마이그레이션 표시 저장 — null=유지·빈 문자열=비움·잘못된 값=400 (스펙 2026-11-03 §6.6·§6.8). */
class RepoCatalogServiceDbWriteTest {

    private static final String GIT_URL = "https://github.com/owner/repo.git";
    private static final String BAD_DIR_MESSAGE = "DB 스크립트 폴더는 레포 안 상대 경로여야 합니다";

    private RepoCatalogRepository repo;
    private RepoCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(RepoCatalogRepository.class);
        service = new RepoCatalogService(repo, mock(GitRefService.class), "https://gitlab.hamon.vip");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static RepoCatalogDto.UpsertRequest req(Boolean managed, String dir) {
        return new RepoCatalogDto.UpsertRequest("별칭", GIT_URL, null, null, true, null, null,
                null, null, null, null, null, managed, dir);
    }

    private RepoCatalogEntry existing(boolean managed, String dir) {
        RepoCatalogEntry e = RepoCatalogEntry.create("별칭", GIT_URL, "github", "owner/repo", null, null, "admin");
        ReflectionTestUtils.setField(e, "id", 7L);
        e.setDbMigrationManaged(managed);
        e.setDbScriptDir(dir);
        when(repo.findById(7L)).thenReturn(Optional.of(e));
        when(repo.findByGitUrl(any())).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void 등록_때_두_칸을_저장하고_폴더는_정규화한다() {
        RepoCatalogEntry e = service.create(req(true, " db\\sql/ "), "admin");
        assertThat(e.isDbMigrationManaged()).isTrue();
        assertThat(e.getDbScriptDir()).isEqualTo("db/sql");
    }

    @Test
    void 등록_때_두_칸을_안_보내면_기본값() {
        RepoCatalogEntry e = service.create(req(null, null), "admin");
        assertThat(e.isDbMigrationManaged()).isFalse();
        assertThat(e.getDbScriptDir()).isNull();
    }

    @Test
    void 등록_때_빈_폴더는_null로_둔다() {
        assertThat(service.create(req(false, ""), "admin").getDbScriptDir()).isNull();
        assertThat(service.create(req(false, "   "), "admin").getDbScriptDir()).isNull();
    }

    @Test
    void 활성_토글_5칸_바디는_두_칸을_유지() {
        // repo-catalog.vue toggleEnabled 모양: alias·gitUrl·defaultBranch·description·enabled 다섯 칸만 보낸다.
        RepoCatalogEntry e = existing(true, "db/sql");
        service.update(7L, new RepoCatalogDto.UpsertRequest("별칭", GIT_URL, null, null, false));
        assertThat(e.isEnabled()).isFalse();
        assertThat(e.isDbMigrationManaged()).isTrue();
        assertThat(e.getDbScriptDir()).isEqualTo("db/sql");
    }

    @Test
    void 노트_폴더_도입_전_12인자_바디도_두_칸을_유지() {
        RepoCatalogEntry e = existing(true, "db/sql");
        service.update(7L, new RepoCatalogDto.UpsertRequest("별칭", GIT_URL, null, null, true, null, null,
                null, null, null, null, null));
        assertThat(e.isDbMigrationManaged()).isTrue();
        assertThat(e.getDbScriptDir()).isEqualTo("db/sql");
    }

    @Test
    void 두_칸을_안_보낸_저장은_그대로_둔다() {
        RepoCatalogEntry e = existing(true, "db/sql");
        service.update(7L, req(null, null));
        assertThat(e.isDbMigrationManaged()).isTrue();
        assertThat(e.getDbScriptDir()).isEqualTo("db/sql");
    }

    @Test
    void 표시만_바꾸면_폴더는_그대로() {
        RepoCatalogEntry e = existing(false, "db/sql");
        service.update(7L, req(true, null));
        assertThat(e.isDbMigrationManaged()).isTrue();
        assertThat(e.getDbScriptDir()).isEqualTo("db/sql");
        service.update(7L, req(false, null));
        assertThat(e.isDbMigrationManaged()).isFalse();
        assertThat(e.getDbScriptDir()).isEqualTo("db/sql");
    }

    @Test
    void 폴더만_바꾸면_표시는_그대로() {
        RepoCatalogEntry e = existing(true, null);
        service.update(7L, req(null, "scripts\\symphony\\"));
        assertThat(e.isDbMigrationManaged()).isTrue();
        assertThat(e.getDbScriptDir()).isEqualTo("scripts/symphony");
    }

    @Test
    void 빈문자열은_비우고_잘못된_값은_400() {
        RepoCatalogEntry e = existing(true, "db/sql");

        service.update(7L, req(null, ""));
        assertThat(e.getDbScriptDir()).isNull();
        assertThat(e.isDbMigrationManaged()).isTrue();

        service.update(7L, req(null, "db/sql"));
        service.update(7L, req(null, "   "));
        assertThat(e.getDbScriptDir()).isNull();

        service.update(7L, req(null, "db/sql"));
        for (String bad : new String[]{"/abs", "C:/x", "../up", "a/../b", "a".repeat(256)}) {
            assertThatThrownBy(() -> service.update(7L, req(false, bad)))
                    .isInstanceOfSatisfying(TaskException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getMessage()).isEqualTo(BAD_DIR_MESSAGE);
                    });
        }
    }

    @Test
    void 잘못된_폴더면_표시도_바꾸지_않는다() {
        RepoCatalogEntry e = existing(true, "db/sql");
        assertThatThrownBy(() -> service.update(7L, req(false, "../x"))).isInstanceOf(TaskException.class);
        assertThat(e.isDbMigrationManaged()).isTrue();
        assertThat(e.getDbScriptDir()).isEqualTo("db/sql");
    }

    @Test
    void 등록_때_잘못된_폴더는_400이고_저장하지_않는다() {
        assertThatThrownBy(() -> service.create(req(true, "/abs"), "admin"))
                .isInstanceOfSatisfying(TaskException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo(BAD_DIR_MESSAGE);
                });
        verify(repo, never()).save(any());
    }

    @Test
    void 조회_응답에_두_칸이_실린다() {
        RepoCatalogEntry e = existing(true, "db/sql");
        RepoCatalogDto.View v = RepoCatalogDto.View.of(e);
        assertThat(v.dbMigrationManaged()).isTrue();
        assertThat(v.dbScriptDir()).isEqualTo("db/sql");
        RepoCatalogDto.View viaService = service.toView(e);
        assertThat(viaService.dbMigrationManaged()).isTrue();
        assertThat(viaService.dbScriptDir()).isEqualTo("db/sql");
        RepoCatalogDto.View withHost = RepoCatalogDto.View.of(e, "github", "owner/repo");
        assertThat(withHost.dbMigrationManaged()).isTrue();
        assertThat(withHost.dbScriptDir()).isEqualTo("db/sql");
    }

    @Test
    void 조회_응답의_기본값은_꺼짐과_null() {
        RepoCatalogEntry e = RepoCatalogEntry.create("별칭", GIT_URL, "github", "owner/repo", null, null, "admin");
        RepoCatalogDto.View v = RepoCatalogDto.View.of(e);
        assertThat(v.dbMigrationManaged()).isFalse();
        assertThat(v.dbScriptDir()).isNull();
    }
}
