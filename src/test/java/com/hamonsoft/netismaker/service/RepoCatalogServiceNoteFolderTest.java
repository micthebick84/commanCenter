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
import static org.mockito.Mockito.when;

/** 카탈로그 노트 폴더 저장 — 수정 때 null=유지·공백=비움 (스펙 2026-10-28 close-note-folder §3-1). */
class RepoCatalogServiceNoteFolderTest {

    private static final String GIT_URL = "https://github.com/owner/repo.git";

    private RepoCatalogRepository repo;
    private RepoCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(RepoCatalogRepository.class);
        service = new RepoCatalogService(repo, mock(GitRefService.class), "https://gitlab.hamon.vip");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static RepoCatalogDto.UpsertRequest req(String noteFolder) {
        return new RepoCatalogDto.UpsertRequest("별칭", GIT_URL, null, null, true, null, null,
                null, null, null, null, noteFolder);
    }

    private RepoCatalogEntry existing(String noteFolder) {
        RepoCatalogEntry e = RepoCatalogEntry.create("별칭", GIT_URL, "github", "owner/repo", null, null, "admin");
        ReflectionTestUtils.setField(e, "id", 7L);
        e.setNoteFolder(noteFolder);
        when(repo.findById(7L)).thenReturn(Optional.of(e));
        when(repo.findByGitUrl(any())).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void 등록_때_노트_폴더를_정규화해_저장한다() {
        RepoCatalogEntry e = service.create(req(" HamonSoft\\동부병원/ "), "admin");
        assertThat(e.getNoteFolder()).isEqualTo("HamonSoft/동부병원");
    }

    @Test
    void 등록_때_공백은_비움() {
        assertThat(service.create(req("  "), "admin").getNoteFolder()).isNull();
    }

    @Test
    void 수정_때_null이면_그대로_둔다() {
        RepoCatalogEntry e = existing("HamonSoft/동부병원");
        service.update(7L, req(null));
        assertThat(e.getNoteFolder()).isEqualTo("HamonSoft/동부병원");
    }

    @Test
    void 수정_때_공백이면_비운다() {
        RepoCatalogEntry e = existing("HamonSoft/동부병원");
        service.update(7L, req("  "));
        assertThat(e.getNoteFolder()).isNull();
    }

    @Test
    void 수정_때_새_값은_검증해_저장한다() {
        RepoCatalogEntry e = existing(null);
        service.update(7L, req("HamonSoft/Netis-플랫폼공통/netisweb_v6.8"));
        assertThat(e.getNoteFolder()).isEqualTo("HamonSoft/Netis-플랫폼공통/netisweb_v6.8");
    }

    @Test
    void 볼트_밖_경로는_400() {
        existing(null);
        assertThatThrownBy(() -> service.update(7L, req("../x")))
                .isInstanceOfSatisfying(TaskException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("노트 폴더는 볼트 안 상대 경로여야 합니다");
                });
    }

    @Test
    void 조회_응답에_노트_폴더가_실린다() {
        RepoCatalogEntry e = existing("HamonSoft/동부병원");
        assertThat(RepoCatalogDto.View.of(e).noteFolder()).isEqualTo("HamonSoft/동부병원");
    }
}
