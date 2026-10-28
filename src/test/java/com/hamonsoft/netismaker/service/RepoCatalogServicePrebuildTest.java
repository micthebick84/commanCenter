package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 카탈로그 미리 빌드 설정 저장·검증 (스펙 2026-10-28 §3·§4). */
class RepoCatalogServicePrebuildTest {

    private static final String GIT_URL = "https://github.com/owner/repo.git";

    private RepoCatalogRepository repo;
    private RepoCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(RepoCatalogRepository.class);
        service = new RepoCatalogService(repo, mock(GitRefService.class), "https://gitlab.hamon.vip");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static RepoCatalogDto.UpsertRequest req(Boolean on, String command, String artifact, String toolchain) {
        return new RepoCatalogDto.UpsertRequest("별칭", GIT_URL, null, null, true, null, null,
                on, command, artifact, toolchain);
    }

    private static void assertBadRequest(ThrowingCallable call, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(TaskException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo(message);
                });
    }

    private RepoCatalogEntry existing(boolean on, String command, String artifact, String toolchain) {
        RepoCatalogEntry e = RepoCatalogEntry.create("별칭", GIT_URL, "github", "owner/repo", null, null, "admin");
        ReflectionTestUtils.setField(e, "id", 7L);
        e.setPrebuildEnabled(on);
        e.setPrebuildCommand(command);
        e.setPrebuildArtifact(artifact);
        e.setPrebuildToolchain(toolchain);
        when(repo.findById(7L)).thenReturn(Optional.of(e));
        when(repo.findByGitUrl(any())).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void 켜면_명령과_산출물이_필요하다() {
        assertBadRequest(() -> RepoCatalogService.validatePrebuild("  ", "a.war", null),
                "미리 빌드 명령이 필요합니다");
        assertBadRequest(() -> RepoCatalogService.validatePrebuild(null, "a.war", null),
                "미리 빌드 명령이 필요합니다");
        assertBadRequest(() -> RepoCatalogService.validatePrebuild("mvn package", null, null),
                "미리 빌드 산출물 경로가 필요합니다");
        assertBadRequest(() -> RepoCatalogService.validatePrebuild("mvn package", " ", null),
                "미리 빌드 산출물 경로가 필요합니다");
    }

    @Test
    void 명령은_한_줄() {
        assertBadRequest(() -> RepoCatalogService.validatePrebuild("mvn a\nmvn b", "a.war", null),
                "미리 빌드 명령은 한 줄이어야 합니다");
        assertBadRequest(() -> RepoCatalogService.validatePrebuild("mvn a\r\nmvn b", "a.war", null),
                "미리 빌드 명령은 한 줄이어야 합니다");
    }

    @Test
    void 산출물은_상대경로() {
        String msg = "미리 빌드 산출물 경로는 레포 안 상대 경로여야 합니다";
        for (String bad : new String[]{"/abs/x.war", "\\x.war", "C:/x.war", "a/../x.war", "..\\x.war"}) {
            assertBadRequest(() -> RepoCatalogService.validatePrebuild("mvn package", bad, null), msg);
        }
    }

    @Test
    void 별표는_마지막_조각만() {
        assertBadRequest(() -> RepoCatalogService.validatePrebuild("mvn package", "*/target/x.war", null),
                "미리 빌드 산출물 경로의 *는 마지막 이름에만 쓸 수 있습니다");
        assertThatCode(() -> RepoCatalogService.validatePrebuild("mvn package", "hm-dash\\target\\*.war", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> RepoCatalogService.validatePrebuild("mvn package", "hm-dash/target/*.war", null))
                .doesNotThrowAnyException();
    }

    @Test
    void 도구_이름_형식() {
        assertBadRequest(() -> RepoCatalogService.validatePrebuild("mvn package", "a.war", "jdk 8"),
                "미리 빌드 도구 이름은 영문·숫자·. _ -로 50자 이하여야 합니다");
        assertBadRequest(() -> RepoCatalogService.validatePrebuild("mvn package", "a.war", "a".repeat(51)),
                "미리 빌드 도구 이름은 영문·숫자·. _ -로 50자 이하여야 합니다");
        assertThatCode(() -> RepoCatalogService.validatePrebuild("mvn package", "a.war", null)).doesNotThrowAnyException();
        assertThatCode(() -> RepoCatalogService.validatePrebuild("mvn package", "a.war", "")).doesNotThrowAnyException();
        assertThatCode(() -> RepoCatalogService.validatePrebuild("mvn package", "a.war", "jdk8-maven")).doesNotThrowAnyException();
    }

    @Test
    void 켜면_trim해서_저장() {
        when(repo.findByAlias(any())).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());

        RepoCatalogEntry saved = service.create(
                req(true, " mvn -o package ", " hm-dash/target/*.war ", " jdk8 "), "admin");

        assertThat(saved.isPrebuildEnabled()).isTrue();
        assertThat(saved.getPrebuildCommand()).isEqualTo("mvn -o package");
        assertThat(saved.getPrebuildArtifact()).isEqualTo("hm-dash/target/*.war");
        assertThat(saved.getPrebuildToolchain()).isEqualTo("jdk8");
    }

    @Test
    void 켜고_검증_실패면_저장하지_않는다() {
        when(repo.findByAlias(any())).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());

        assertBadRequest(() -> service.create(req(true, "", "a.war", null), "admin"),
                "미리 빌드 명령이 필요합니다");
    }

    @Test
    void 끄면_검증없이_값을_보존() {
        RepoCatalogEntry e = existing(true, "old", "old.war", "jdk8");

        service.update(7L, req(false, "", "  ", "jdk 8 bad"));

        assertThat(e.isPrebuildEnabled()).isFalse();
        assertThat(e.getPrebuildCommand()).isNull();
        assertThat(e.getPrebuildArtifact()).isNull();
        assertThat(e.getPrebuildToolchain()).isEqualTo("jdk 8 bad");
    }

    @Test
    void 끄면_요청_값으로_저장() {
        RepoCatalogEntry e = existing(true, "old", "old.war", "jdk8");

        service.update(7L, req(false, "mvn x", "x/y.war", "jdk11"));

        assertThat(e.isPrebuildEnabled()).isFalse();
        assertThat(e.getPrebuildCommand()).isEqualTo("mvn x");
        assertThat(e.getPrebuildArtifact()).isEqualTo("x/y.war");
        assertThat(e.getPrebuildToolchain()).isEqualTo("jdk11");
    }

    @Test
    void prebuildEnabled_null이면_설정_유지() {
        RepoCatalogEntry e = existing(true, "mvn package", "a/b.war", "jdk8");

        service.update(7L, new RepoCatalogDto.UpsertRequest("별칭", GIT_URL, null, null, true, null, null));

        assertThat(e.isPrebuildEnabled()).isTrue();
        assertThat(e.getPrebuildCommand()).isEqualTo("mvn package");
        assertThat(e.getPrebuildArtifact()).isEqualTo("a/b.war");
        assertThat(e.getPrebuildToolchain()).isEqualTo("jdk8");
    }

    @Test
    void 뷰에_네_값이_실린다() {
        RepoCatalogEntry e = existing(true, "mvn package", "a/b.war", "jdk8");

        RepoCatalogDto.View v = service.toView(e);

        assertThat(v.prebuildEnabled()).isTrue();
        assertThat(v.prebuildCommand()).isEqualTo("mvn package");
        assertThat(v.prebuildArtifact()).isEqualTo("a/b.war");
        assertThat(v.prebuildToolchain()).isEqualTo("jdk8");
        assertThat(RepoCatalogDto.View.of(e).prebuildCommand()).isEqualTo("mvn package");
    }
}
