package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.CloseContext;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 종료 노트 폴더 결정 (스펙 2026-10-28 close-note-folder §3). */
class CloseNoteFolderResolverTest {

    @TempDir Path tmp;
    private Path vault;
    private ClaudeExecAdapter claude;
    private CloseNoteFolderResolver resolver;
    private static final ClaudeExecAdapter.Usage USAGE =
            new ClaudeExecAdapter.Usage(new BigDecimal("0.01"), 100, 5, 0, 0);

    @BeforeEach
    void setUp() throws Exception {
        vault = tmp.resolve("vault");
        Files.createDirectories(vault.resolve("HamonSoft/동부병원"));
        Files.createDirectories(vault.resolve("HamonSoft/Netis-플랫폼공통/netisweb_v6.8/깊은폴더"));
        Files.createDirectories(vault.resolve("HamonSoft/.trash"));
        Files.writeString(vault.resolve("HamonSoft/메모.md"), "x");
        claude = mock(ClaudeExecAdapter.class);
        CloseProperties cp = new CloseProperties(vault.toString(), "HamonSoft/netisMaker", "merge",
                "sonnet", Duration.ofMinutes(1), null);
        resolver = new CloseNoteFolderResolver(claude, cp);
    }

    private static WorkerTaskResponse task(String alias, String noteFolder) {
        CloseContext ctx = new CloseContext(null, null, null, null, null, null, List.of(), List.of(),
                null, null, false, null, alias, "레포 설명", noteFolder);
        return new WorkerTaskResponse(7L, "product/netis/web/dashboard/netisdash_linaems_v6.8", "main", "대시보드 6.8 마이그레이션",
                "d", WorkerTaskResponse.Kind.CLOSE, List.of(), null, null, "netismaker/task-7", "abc", List.of(), null, null,
                null, null, null, null, null, null, "https://oauth2:tok@gitlab.hamon.vip/product/netis/web/dashboard/netisdash_linaems_v6.8.git",
                "gitlab", null, 3, "https://gitlab.hamon.vip/x/-/merge_requests/3", null, ctx);
    }

    private void answer(int exit, String stdout) throws Exception {
        when(claude.execWithoutTools(anyString(), any(), any(), anyString()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(exit, stdout, 10, USAGE));
    }

    private CloseNoteFolderResolver.Resolved resolve(WorkerTaskResponse t) throws Exception {
        return resolver.resolve(vault, t, tmp.resolve("work"));
    }

    @Test
    void 후보는_루트_아래_두_단계_폴더다() {
        assertThat(CloseNoteFolderResolver.candidates(vault, "HamonSoft")).containsExactly(
                "HamonSoft/Netis-플랫폼공통", "HamonSoft/Netis-플랫폼공통/netisweb_v6.8", "HamonSoft/동부병원");
        assertThat(CloseNoteFolderResolver.candidates(vault, "없음")).isEmpty();
    }

    @Test
    void 카탈로그_값이_있으면_그대로_쓰고_기록하지_않는다() throws Exception {
        CloseNoteFolderResolver.Resolved r = resolve(task("동부병원 대시보드", "HamonSoft\\동부병원"));
        assertThat(r.folder()).isEqualTo("HamonSoft/동부병원");
        assertThat(r.record()).isFalse();
        verifyNoInteractions(claude);
    }

    @Test
    void 카탈로그_값이_잘못됐으면_실패한다() {
        assertThatThrownBy(() -> resolve(task("동부병원 대시보드", "../x")))
                .isInstanceOf(CloseNoteFolderResolver.ResolveFailed.class)
                .hasMessageStartingWith("노트: 카탈로그 노트 폴더 값이 잘못됐습니다")
                .hasMessageContaining("레포 카탈로그에서 고친 뒤 재시도");
    }

    @Test
    void 클로드가_고른_후보를_쓰고_기록한다() throws Exception {
        answer(0, "`HamonSoft/동부병원/`\n");
        CloseNoteFolderResolver.Resolved r = resolve(task("동부병원 대시보드", null));
        assertThat(r.folder()).isEqualTo("HamonSoft/동부병원");
        assertThat(r.record()).isTrue();
        assertThat(r.usage()).isEqualTo(USAGE);
    }

    @Test
    void NONE이면_별칭_폴더를_만든다() throws Exception {
        answer(0, "NONE");
        CloseNoteFolderResolver.Resolved r = resolve(task("라이나생명 대시보드 v6.8", null));
        assertThat(r.folder()).isEqualTo("HamonSoft/라이나생명 대시보드 v6.8");
        assertThat(r.record()).isTrue();
    }

    @Test
    void 후보에_없는_답은_별칭_폴더로() throws Exception {
        answer(0, "HamonSoft/없는폴더");
        CloseNoteFolderResolver.Resolved r = resolve(task("라이나생명 대시보드 v6.8", null));
        assertThat(r.folder()).isEqualTo("HamonSoft/라이나생명 대시보드 v6.8");
        assertThat(r.record()).isTrue();
    }

    @Test
    void 후보가_없으면_클로드_없이_별칭_폴더() throws Exception {
        CloseProperties cp = new CloseProperties(vault.toString(), "Other/netisMaker", "merge",
                "sonnet", Duration.ofMinutes(1), null);
        CloseNoteFolderResolver.Resolved r = new CloseNoteFolderResolver(claude, cp)
                .resolve(vault, task("제2철도 대시보드", null), tmp.resolve("work"));
        assertThat(r.folder()).isEqualTo("Other/제2철도 대시보드");
        verifyNoInteractions(claude);
    }

    @Test
    void 별칭의_경로_문자는_바꾼다() {
        assertThat(CloseNoteFolderResolver.aliasFolder("HamonSoft", " A/B: 대시보드. ")).isEqualTo("HamonSoft/A-B- 대시보드");
        assertThat(CloseNoteFolderResolver.aliasFolder("HamonSoft", " .. ")).isNull();
    }

    @Test
    void 별칭이_없으면_기본_폴더에_기록_없이() throws Exception {
        CloseNoteFolderResolver.Resolved r = resolve(task(null, null));
        assertThat(r.folder()).isEqualTo("HamonSoft/netisMaker");
        assertThat(r.record()).isFalse();
        verifyNoInteractions(claude);
    }

    @Test
    void 클로드_실패는_예외() throws Exception {
        answer(1, "boom");
        assertThatThrownBy(() -> resolve(task("동부병원 대시보드", null)))
                .isInstanceOf(CloseNoteFolderResolver.ResolveFailed.class)
                .hasMessageStartingWith("노트 폴더 고르기 실패(exit=1)");
    }

    @Test
    void 프롬프트에_재료와_후보가_있고_자격증명은_없다() {
        String p = CloseNoteFolderResolver.render(task("라이나생명 대시보드 v6.8", null),
                List.of("HamonSoft/동부병원", "HamonSoft/Netis-플랫폼공통/netisweb_v6.8"));
        assertThat(p).contains("라이나생명 대시보드 v6.8", "레포 설명", "대시보드 6.8 마이그레이션",
                "- HamonSoft/동부병원", "- HamonSoft/Netis-플랫폼공통/netisweb_v6.8", "NONE",
                "product/netis/web/dashboard/netisdash_linaems_v6.8");
        assertThat(p).doesNotContain("oauth2", "tok@");
    }
}
