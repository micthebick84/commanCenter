package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactDownloadServiceTest {

    @TempDir Path root;
    private Task t;
    private ArtifactDownloadService service;

    @BeforeEach
    void setUp() throws Exception {
        TaskRepository taskRepo = mock(TaskRepository.class);
        t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.CLOSED);
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));
        service = new ArtifactDownloadService(taskRepo, new ArtifactStore(root.toString()), new ArtifactDownloadTokens());
    }

    private void storeFile() throws Exception {
        Path dir = Files.createDirectories(root.resolve("task-7/artifact"));
        Files.writeString(dir.resolve("repo-task-7-abcdef1.war"), "WAR");
        t.setArtifactName("repo-task-7-abcdef1.war");
    }

    private static void assertStatus(Throwable e, HttpStatus expected) {
        assertThat(e).isInstanceOfSatisfying(TaskException.class, te -> assertThat(te.getStatus()).isEqualTo(expected));
    }

    @Test
    void 등록자와_관리자는_1회용_주소를_받고_그_주소로_파일을_꺼낸다() throws Exception {
        storeFile();
        ArtifactDownloadService.Link link = service.link(7L, "user1", false);
        assertThat(link.url()).startsWith("/api/artifact-downloads/");
        assertThat(link.expiresInSec()).isEqualTo(60);
        String token = link.url().substring("/api/artifact-downloads/".length());

        ArtifactDownloadService.Download d = service.redeem(token);
        assertThat(d.name()).isEqualTo("repo-task-7-abcdef1.war");
        assertThat(d.size()).isEqualTo(3);
        assertThat(Files.readString(d.file())).isEqualTo("WAR");
        assertThatThrownBy(() -> service.redeem(token))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(service.link(7L, "admin", true).url()).startsWith("/api/artifact-downloads/");
    }

    @Test
    void 다른_사용자는_403_기록이_없으면_404_파일이_정리됐으면_410() throws Exception {
        assertThatThrownBy(() -> service.link(7L, "user1", false))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        t.setArtifactName("repo-task-7-abcdef1.war");
        assertThatThrownBy(() -> service.link(7L, "user1", false))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE));
        storeFile();
        assertThatThrownBy(() -> service.link(7L, "user2", false))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /** 스펙 §6 판정 순서: 작업 없음 404 → 권한 403 → 기록 없음 404 → 파일 없음 410. 권한이 기록 유무보다 먼저다. */
    @Test
    void 남의_작업이면_산출물_기록이_없어도_파일이_없어도_403이다() {
        // 기록 없음
        assertThatThrownBy(() -> service.link(7L, "user2", false)).satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        // 기록은 있는데 파일 없음 — 410이 아니라 403 (남의 작업의 보관 상태를 알려 주지 않는다)
        t.setArtifactName("repo-task-7-abcdef1.war");
        assertThatThrownBy(() -> service.link(7L, "user2", false)).satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
    }

    @Test
    void 관리자도_기록이_없으면_404_파일이_없으면_410_작업이_없으면_404() {
        assertThatThrownBy(() -> service.link(7L, "admin", true)).satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        t.setArtifactName("repo-task-7-abcdef1.war");
        assertThatThrownBy(() -> service.link(7L, "admin", true)).satisfies(e -> assertStatus(e, HttpStatus.GONE));
        assertThatThrownBy(() -> service.link(8L, "admin", true)).satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void 주소를_받은_뒤_파일이_정리되면_꺼낼_때_404() throws Exception {
        storeFile();
        String token = service.link(7L, "user1", false).url().substring("/api/artifact-downloads/".length());
        Files.delete(root.resolve("task-7/artifact/repo-task-7-abcdef1.war"));
        assertThatThrownBy(() -> service.redeem(token)).satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }

    @Test
    void 모르는_토큰은_404() {
        assertThatThrownBy(() -> service.redeem("nope")).satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.redeem(null)).satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
    }
}
