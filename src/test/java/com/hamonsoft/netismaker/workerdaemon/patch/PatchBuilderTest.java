package com.hamonsoft.netismaker.workerdaemon.patch;

import com.hamonsoft.netismaker.dto.CloseContext;
import com.hamonsoft.netismaker.dto.ClosePatchSpec;
import com.hamonsoft.netismaker.dto.PatchInfo;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.workerdaemon.DeployPrebuilder;
import com.hamonsoft.netismaker.workerdaemon.WorkerHttpClient;
import com.hamonsoft.netismaker.workerdaemon.WorktreeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

import static com.hamonsoft.netismaker.workerdaemon.patch.PatchTestZips.b;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatchBuilderTest {

    private static final String BASE = "1111111111111111111111111111111111111111";
    private static final String MERGE = "2222222222222222222222222222222222222222";
    private static final String HEAD = "3333333333333333333333333333333333333333";
    private static final long T = 1_700_000_000_000L;

    @TempDir Path tmp;
    private Path closeRoot;
    private WorkerHttpClient http;
    private FakeGit git;
    private final Map<WorktreeKind, Map<String, byte[]>> contents = new HashMap<>();
    private final Map<WorktreeKind, String> artifactName = new HashMap<>();
    private final Map<WorktreeKind, DeployPrebuilder.PrebuildException> buildFailure = new HashMap<>();
    private final List<WorktreeKind> built = new ArrayList<>();
    private final AtomicReference<List<String>> uploadedEntries = new AtomicReference<>();
    private final AtomicReference<String> uploadedInfo = new AtomicReference<>();
    private ClosePatchWorkerProperties cfg;
    private PatchBuilder builder;

    /** 가짜 git: worktree는 빈 폴더, 커밋 받기·부모·소스 변경은 정해 둔 값. */
    final class FakeGit implements PatchBuilder.GitFn {
        List<String> parents = List.of(BASE, HEAD);
        final List<String> discarded = new ArrayList<>();
        final Map<WorktreeKind, Path> created = new HashMap<>();
        int fetches;

        @Override
        public File fetchPatchCommits(RepoRef ref, String baseBranch, String afterSha) {
            fetches++;
            return tmp.resolve("cache").toFile();
        }

        @Override
        public List<String> parents(File dir, String sha) {
            return parents;
        }

        @Override
        public List<String> diffNameStatus(File dir, String before, String after) {
            return List.of("M\tsrc/main/java/A.java");
        }

        @Override
        public File createAtCommit(File cacheDir, String repoKey, WorktreeKind kind, long taskId, String sha)
                throws IOException {
            Path dir = Files.createDirectories(tmp.resolve("worktrees").resolve(kind.name() + "-" + taskId));
            created.put(kind, dir);
            return dir.toFile();
        }

        @Override
        public void discard(String repoKey, WorktreeKind kind, long taskId) {
            discarded.add(kind.name());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        closeRoot = tmp.resolve("close");
        http = mock(WorkerHttpClient.class);
        when(http.currentPatch(anyLong())).thenReturn(Optional.empty());
        when(http.uploadPatch(anyLong(), anyInt(), any(), anyString(), any())).thenAnswer(inv -> {
            Path zip = inv.getArgument(2);
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                uploadedEntries.set(Collections.list(zf.entries()).stream().map(e -> e.getName()).toList());
                uploadedInfo.set(new String(zf.getInputStream(zf.getEntry("PATCH-INFO.txt")).readAllBytes(),
                        StandardCharsets.UTF_8));
            }
            return new PatchInfo("w-task-7-patch.zip", Files.size(zip), inv.getArgument(3), inv.getArgument(1));
        });
        git = new FakeGit();
        cfg = new ClosePatchWorkerProperties(null, null, null);
        PatchBuilder.BuildFn build = (spec, worktree) -> {
            WorktreeKind kind = git.created.entrySet().stream()
                    .filter(e -> e.getValue().equals(worktree)).findFirst().orElseThrow().getKey();
            built.add(kind);
            if (buildFailure.containsKey(kind)) throw buildFailure.get(kind);
            try {
                Path target = Files.createDirectories(worktree.resolve("target"));
                return PatchTestZips.zip(target.resolve(artifactName.getOrDefault(kind, "app.war")),
                        contents.get(kind), T);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneId.of("UTC"));
        builder = new PatchBuilder(closeRoot, cfg, http, build, git, clock);
        contents.put(WorktreeKind.PATCH_BEFORE, war("a1", "b"));
        contents.put(WorktreeKind.PATCH_AFTER, war("a2", "b"));
    }

    private static Map<String, byte[]> war(String a, String bContent) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("META-INF/MANIFEST.MF", b("Built-At: " + a));   // 기본 무시 목록 — 달라도 패치에 안 들어간다
        m.put("WEB-INF/classes/A.class", b(a));
        m.put("WEB-INF/classes/B.class", b(bContent));
        return m;
    }

    private static WorkerTaskResponse task(String baseSha) {
        CloseContext ctx = new CloseContext(null, null, null, MERGE, "http://h:1", null, List.of(), List.of(),
                null, null, false, null, "웹 포털", null, null);
        return new WorkerTaskResponse(7L, "acme/w", "main", "로그인 수정", "d", WorkerTaskResponse.Kind.CLOSE,
                List.of(), null, null, "netismaker/task-7", HEAD, List.of(), null, null, null, null, null, null,
                null, null, null, "github", null, 3, "https://github.com/acme/w/pull/3", "MERGED", ctx, null,
                null, null, List.of(), null, List.of(), List.of(),
                new ClosePatchSpec("mvn -q package", "jdk8-maven", "target/*.war", baseSha));
    }

    private String manifestKey() {
        return PatchManifestStore.key("mvn -q package", "jdk8-maven", "target/*.war", cfg.ignore());
    }

    private PatchManifestStore store() {
        return new PatchManifestStore(closeRoot.resolve("patch-manifests"), 20);
    }

    @Test
    void buildsBothSidesAndUploadsOnlyChangedEntries() throws Exception {
        PatchBuilder.Outcome o = builder.build(task(BASE), MERGE);

        assertThat(o).isEqualTo(new PatchBuilder.Outcome(false, 1));
        assertThat(built).containsExactly(WorktreeKind.PATCH_BEFORE, WorktreeKind.PATCH_AFTER);
        assertThat(uploadedEntries.get()).containsExactly("PATCH-INFO.txt", "DELETED.txt",
                "patch/WEB-INF/classes/A.class");
        verify(http).uploadPatch(eq(7L), eq(1), any(), anyString(), eq(Duration.ofMinutes(10)));
        assertThat(uploadedInfo.get()).contains("작업: #7 로그인 수정").contains("레포: 웹 포털")
                .contains("패치 전 커밋: " + BASE).contains("패치 후 커밋: " + MERGE)
                .contains("만든 시각: 2026-11-10T12:00:00+09:00").contains("M\tsrc/main/java/A.java");
    }

    @Test
    void emptyDeltaUploadsNothing() throws Exception {
        contents.put(WorktreeKind.PATCH_AFTER, war("a1", "b"));
        contents.get(WorktreeKind.PATCH_AFTER).put("META-INF/MANIFEST.MF", b("Built-At: later"));   // 무시 항목만 다르다

        PatchBuilder.Outcome o = builder.build(task(BASE), MERGE);

        assertThat(o).isEqualTo(new PatchBuilder.Outcome(true, 0));
        verify(http, never()).uploadPatch(anyLong(), anyInt(), any(), anyString(), any());
        assertThat(store().load(GitRemotes.localKey(task(BASE).repoRef()), MERGE, manifestKey())).isPresent();
    }

    @Test
    void cachedBeforeManifestSkipsBeforeBuild() throws Exception {
        Path beforeWar = PatchTestZips.zip(tmp.resolve("before.war"), war("a1", "b"), T);
        store().save(GitRemotes.localKey(task(BASE).repoRef()), BASE, manifestKey(),
                new PatchScanner(cfg.ignore()).scan(beforeWar));

        PatchBuilder.Outcome o = builder.build(task(BASE), MERGE);

        assertThat(built).containsExactly(WorktreeKind.PATCH_AFTER);
        assertThat(o).isEqualTo(new PatchBuilder.Outcome(false, 1));
    }

    @Test
    void cachedBeforeManifestWithDifferentIgnoreListIsNotReused() throws Exception {
        Path beforeWar = PatchTestZips.zip(tmp.resolve("before.war"), war("a1", "b"), T);
        String otherKey = PatchManifestStore.key("mvn -q package", "jdk8-maven", "target/*.war", List.of());
        store().save(GitRemotes.localKey(task(BASE).repoRef()), BASE, otherKey,
                new PatchScanner(List.of()).scan(beforeWar));

        builder.build(task(BASE), MERGE);

        assertThat(built).containsExactly(WorktreeKind.PATCH_BEFORE, WorktreeKind.PATCH_AFTER);
    }

    @Test
    void retryAfterUploadSkipsBuild() throws Exception {
        when(http.currentPatch(7L)).thenReturn(Optional.of(new PatchInfo("w-task-7-patch.zip", 10, "ab", 3)));

        PatchBuilder.Outcome o = builder.build(task(BASE), MERGE);

        assertThat(o).isEqualTo(new PatchBuilder.Outcome(false, 3));
        assertThat(built).isEmpty();
        assertThat(git.fetches).isZero();
    }

    @Test
    void baseMismatchFailsBeforeAnyBuild() {
        git.parents = List.of(HEAD, BASE);

        assertThatThrownBy(() -> builder.build(task(BASE), MERGE))
                .isInstanceOf(PatchException.class)
                .hasMessageStartingWith("패치 기준 커밋을 확인할 수 없습니다: ");
        assertThat(built).isEmpty();
    }

    @Test
    void shapeMismatchFailsWithFixedMessage() {
        artifactName.put(WorktreeKind.PATCH_AFTER, "app.jar");

        assertThatThrownBy(() -> builder.build(task(BASE), MERGE))
                .isInstanceOf(PatchException.class)
                .hasMessage("패치 전·후 빌드 결과의 구성이 다릅니다");
    }

    @Test
    void beforeBuildFailureIsReportedAsBefore() {
        buildFailure.put(WorktreeKind.PATCH_BEFORE, new DeployPrebuilder.PrebuildException(
                "미리 빌드 실패(exit=1)", "[ERROR] https://oauth2:tok123@gitlab.example/g/p.git 못 받음"));

        assertThatThrownBy(() -> builder.build(task(BASE), MERGE))
                .isInstanceOf(PatchException.class)
                .hasMessageStartingWith("패치 빌드 실패(전): 미리 빌드 실패(exit=1)")
                .hasMessageContaining("못 받음")
                .hasMessageNotContaining("tok123");
        assertThat(built).containsExactly(WorktreeKind.PATCH_BEFORE);
        assertThat(store().load(GitRemotes.localKey(task(BASE).repoRef()), MERGE, manifestKey())).isEmpty();
    }

    @Test
    void worktreesAndWorkDirAreRemovedOnFailure() {
        buildFailure.put(WorktreeKind.PATCH_AFTER, new DeployPrebuilder.PrebuildException("미리 빌드 실패(exit=2)", ""));

        assertThatThrownBy(() -> builder.build(task(BASE), MERGE))
                .isInstanceOf(PatchException.class)
                .hasMessageStartingWith("패치 빌드 실패(후): ");
        assertThat(git.discarded).containsExactlyInAnyOrder("PATCH_BEFORE", "PATCH_AFTER");
        assertThat(closeRoot.resolve("task-7").resolve("patch")).doesNotExist();
        assertThat(closeRoot.resolve("task-7")).doesNotExist();
    }

    @Test
    void successAlsoRemovesWorktreesAndWorkDir() throws Exception {
        builder.build(task(BASE), MERGE);

        assertThat(git.discarded).containsExactlyInAnyOrder("PATCH_BEFORE", "PATCH_AFTER");
        assertThat(closeRoot.resolve("task-7")).doesNotExist();
    }
}
