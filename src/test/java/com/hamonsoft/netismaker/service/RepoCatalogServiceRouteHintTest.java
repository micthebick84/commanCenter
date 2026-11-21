package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 카탈로그 추천 키워드(route_hint) 저장 규칙과 등록 가능 목록 (스펙 2026-11-20 §5.3·§6.4). */
class RepoCatalogServiceRouteHintTest {

    private static final String GIT_URL = "https://github.com/owner/repo.git";
    private static final String GITLAB = "https://gitlab.hamon.vip";

    private RepoCatalogRepository repo;
    private RepoCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(RepoCatalogRepository.class);
        service = new RepoCatalogService(repo, mock(GitRefService.class), GITLAB);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static RepoCatalogDto.UpsertRequest req(String routeHint) {
        return new RepoCatalogDto.UpsertRequest("별칭", GIT_URL, null, null, true, null, null,
                null, null, null, null, null, null, null, null, routeHint);
    }

    private RepoCatalogEntry existing(String routeHint) {
        RepoCatalogEntry e = RepoCatalogEntry.create("별칭", GIT_URL, "github", "owner/repo", null, null, "admin");
        ReflectionTestUtils.setField(e, "id", 7L);
        e.setRouteHint(routeHint);
        when(repo.findById(7L)).thenReturn(Optional.of(e));
        when(repo.findByGitUrl(any())).thenReturn(Optional.of(e));
        return e;
    }

    private static RepoCatalogEntry row(String alias, String url, boolean enabled) {
        RepoCatalogEntry e = RepoCatalogEntry.create(alias, url, "github", "o/" + alias, null, null, "admin");
        e.setEnabled(enabled);
        return e;
    }

    @Test
    void 수정에서_routeHint가_null이면_유지한다() {
        RepoCatalogEntry e = existing("키워드");
        service.update(7L, req(null));
        assertThat(e.getRouteHint()).isEqualTo("키워드");
    }

    @Test
    void 수정에서_빈_문자열이면_비운다() {
        RepoCatalogEntry e = existing("키워드");
        service.update(7L, req("  "));
        assertThat(e.getRouteHint()).isNull();
    }

    @Test
    void 수정에서_값이_있으면_앞뒤_공백을_뺀_값으로_저장한다() {
        RepoCatalogEntry e = existing("키워드");
        service.update(7L, req("  병원 청구  "));
        assertThat(e.getRouteHint()).isEqualTo("병원 청구");
    }

    @Test
    void 등록에서_공백뿐이면_null로_저장한다() {
        assertThat(service.create(req("   "), "admin").getRouteHint()).isNull();
    }

    @Test
    void 등록에서_값을_저장한다() {
        assertThat(service.create(req(" 청구 "), "admin").getRouteHint()).isEqualTo("청구");
    }

    @Test
    void listRoutable은_꺼진_행과_지원하지_않는_호스트를_뺀다() {
        RepoCatalogEntry github = row("가", "https://github.com/o/a.git", true);
        RepoCatalogEntry gitlab = row("나", GITLAB + "/g/b.git", true);
        RepoCatalogEntry other = row("다", "https://bitbucket.org/o/c.git", true);
        RepoCatalogEntry off = row("라", "https://github.com/o/d.git", false);
        // 서비스는 enabled 행만 조회한다 — 꺼진 행은 조회 결과에 없지만 방어적으로 한 번 더 거른다.
        when(repo.findByEnabledTrueOrderByAlias()).thenReturn(List.of(github, gitlab, other, off));

        assertThat(service.listRoutable()).containsExactly(github, gitlab);
    }

    @Test
    void 일반_목록_View는_routeHint가_null이다() {
        RepoCatalogEntry e = row("가", GIT_URL, true);
        e.setRouteHint("키워드");
        assertThat(RepoCatalogDto.View.of(e).routeHint()).isEqualTo("키워드");
        assertThat(RepoCatalogDto.View.of(e).withoutRouteHint().routeHint()).isNull();
        assertThat(service.toView(e).routeHint()).isEqualTo("키워드");
    }
}
