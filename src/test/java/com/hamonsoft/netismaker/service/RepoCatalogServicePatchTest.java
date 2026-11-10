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

/** 카탈로그 부분 패치 켜기 검증 (종료 부분 패치 스펙). */
class RepoCatalogServicePatchTest {

    private static final String GIT_URL = "https://github.com/owner/repo.git";
    private static final String MESSAGE = "부분 패치는 미리 빌드 명령이 있어야 켤 수 있습니다";

    private RepoCatalogRepository repo;
    private RepoCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(RepoCatalogRepository.class);
        service = new RepoCatalogService(repo, mock(GitRefService.class), "https://gitlab.hamon.vip");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static RepoCatalogDto.UpsertRequest req(Boolean prebuildOn, String command, Boolean patch) {
        return new RepoCatalogDto.UpsertRequest("별칭", GIT_URL, null, null, true, null, null,
                prebuildOn, command, command == null ? null : "target/a.war", null,
                null, null, null, patch);
    }

    private RepoCatalogEntry existing(boolean patchOn, String command) {
        RepoCatalogEntry e = RepoCatalogEntry.create("별칭", GIT_URL, "github", "owner/repo", null, null, "admin");
        ReflectionTestUtils.setField(e, "id", 7L);
        e.setPrebuildCommand(command);
        e.setPatchEnabled(patchOn);
        when(repo.findById(7L)).thenReturn(Optional.of(e));
        when(repo.findByGitUrl(any())).thenReturn(Optional.of(e));
        return e;
    }

    private static void assertRejected(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TaskException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessage()).isEqualTo(MESSAGE);
        });
    }

    @Test
    void enablingPatchWithoutPrebuildCommandIsRejected() {
        assertRejected(() -> service.create(req(null, null, true), "admin"));
    }

    @Test
    void clearingPrebuildCommandWhilePatchEnabledIsRejected() {
        existing(true, "mvn package");
        // 미리 빌드를 끄면서 명령을 비움(끄면 검증 없이 값 보존·빈 값은 null) — 패치가 켜져 있으면 거부
        assertRejected(() -> service.update(7L, req(false, "", null)));
    }

    @Test
    void nullPatchEnabledKeepsCurrentValue() {
        RepoCatalogEntry e = existing(true, "mvn package");
        service.update(7L, req(null, null, null));
        assertThat(e.isPatchEnabled()).isTrue();
    }

    @Test
    void patchCanBeEnabledWhenPrebuildDisabledButCommandPresent() {
        RepoCatalogEntry e = existing(false, "mvn package");
        service.update(7L, req(false, "mvn package", true));
        assertThat(e.isPatchEnabled()).isTrue();
        assertThat(e.isPrebuildEnabled()).isFalse();
    }
}
