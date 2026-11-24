package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployConfig.Target;
import com.hamonsoft.netismaker.entity.DeployConfig.TargetKind;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 카탈로그 배포 설정(deploy_config) 저장 규칙 (스펙 2026-11-24 §9). */
class RepoCatalogServiceDeployConfigTest {

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

    private static DeployConfig config(String slot) {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://{host}:{port}/{database}");
        return new DeployConfig(
                List.of(new DbSlot(slot, "주 DB", List.of(DbType.POSTGRESQL), new Target(TargetKind.ENV, null, entries))),
                List.of());
    }

    private static RepoCatalogDto.UpsertRequest req(DeployConfig c) {
        return new RepoCatalogDto.UpsertRequest("별칭", GIT_URL, null, null, true, null, null,
                null, null, null, null, null, null, null, null, null, c);
    }

    private RepoCatalogEntry existing(DeployConfig c) {
        RepoCatalogEntry e = RepoCatalogEntry.create("별칭", GIT_URL, "github", "owner/repo", null, null, "admin");
        ReflectionTestUtils.setField(e, "id", 7L);
        e.setDeployConfig(c);
        when(repo.findById(7L)).thenReturn(Optional.of(e));
        when(repo.findByGitUrl(any())).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void 수정에서_null이면_유지() {
        DeployConfig stored = config("main");
        RepoCatalogEntry e = existing(stored);
        service.update(7L, req(null));
        assertThat(e.getDeployConfig()).isEqualTo(stored);
    }

    @Test
    void 수정에서_설정을_보내면_정규화해_저장() {
        RepoCatalogEntry e = existing(null);
        service.update(7L, req(config(" main ")));
        assertThat(e.getDeployConfig().databases().get(0).slot()).isEqualTo("main");
    }

    @Test
    void 빈_설정을_보내면_비운다() {
        RepoCatalogEntry e = existing(config("main"));
        service.update(7L, req(new DeployConfig(List.of(), List.of())));
        assertThat(e.getDeployConfig()).isNull();
    }

    @Test
    void 등록에서_null이면_설정_없음() {
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());
        RepoCatalogEntry e = service.create(req(null), "admin");
        assertThat(e.getDeployConfig()).isNull();
    }

    @Test
    void 잘못된_설정은_400() {
        existing(null);
        DeployConfig bad = config("Bad Slot");
        assertThatThrownBy(() -> service.update(7L, req(bad)))
                .isInstanceOf(TaskException.class)
                .satisfies(ex -> assertThat(((TaskException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 일반_사용자_목록에는_배포설정이_없다() {
        RepoCatalogEntry e = existing(config("main"));
        RepoCatalogDto.View view = RepoCatalogDto.View.of(e);
        assertThat(view.deployConfig()).isNotNull();
        assertThat(view.withoutDeployConfig().deployConfig()).isNull();
        assertThat(view.withoutRouteHint().deployConfig()).isNotNull();
    }
}
