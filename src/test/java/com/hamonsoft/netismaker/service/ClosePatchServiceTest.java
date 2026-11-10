package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.PatchInfo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClosePatchServiceTest {

    @TempDir Path root;
    private TaskRepository taskRepo;
    private PatchStore store;
    private ClosePatchService service;
    private Task t;

    private final byte[] body = "PATCH-ZIP".getBytes(StandardCharsets.UTF_8);
    private String sha;

    @BeforeEach
    void setUp() throws Exception {
        taskRepo = mock(TaskRepository.class);
        store = new PatchStore(root.toString());
        // 2026-11-09 16:00Z = 서울 2026-11-10 01:00 — UTC 날짜(11-09)가 아니라 서울 날짜가 이름에 들어가야 한다
        Clock clock = Clock.fixed(Instant.parse("2026-11-09T16:00:00Z"), ZoneOffset.UTC);
        service = new ClosePatchService(taskRepo, store, new ClosePatchProperties(true, null),
                mock(PlatformTransactionManager.class), clock);
        t = Task.create("owner/repo", "main", "로그인 오류 수정", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        t.setClosePatchMode("REQUIRED");
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        sha = ArtifactStoreTest.sha(body);
    }

    private PatchInfo receive() {
        return service.receive(7L, "w1", sha, 12, new ByteArrayInputStream(body));
    }

    @Test
    void 받은_패치를_저장하고_이름_크기_해시_개수를_기록한다() {
        PatchInfo info = receive();

        assertThat(info).isEqualTo(new PatchInfo("20261110_로그인_오류_수정.zip", body.length, sha, 12));
        assertThat(t.getPatchName()).isEqualTo("20261110_로그인_오류_수정.zip");
        assertThat(t.getPatchSize()).isEqualTo((long) body.length);
        assertThat(t.getPatchSha256()).isEqualTo(sha);
        assertThat(t.getPatchFileCount()).isEqualTo(12);
        assertThat(t.getPatchUploadedAt()).isNotNull();
        assertThat(Files.exists(root.resolve("task-7/patch/patch.zip"))).isTrue();
    }

    @Test
    void 종료중이_아니거나_다른_워커거나_모드가_REQUIRED가_아니면_409() {
        t.setStatus(TaskStatus.CLOSE_FAILED);
        assertThatThrownBy(this::receive).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        t.setStatus(TaskStatus.CLOSING);
        t.setWorkerId("w2");
        assertThatThrownBy(this::receive).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        t.setWorkerId("w1");
        t.setClosePatchMode(null);
        assertThatThrownBy(this::receive).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        t.setClosePatchMode("SKIPPED");
        assertThatThrownBy(this::receive).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(t.getPatchName()).isNull();
    }

    @Test
    void 같은_해시를_다시_보내면_본문을_버리고_기존_값을_준다() {
        PatchInfo first = receive();
        reset(taskRepo);
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));

        PatchInfo again = service.receive(7L, "w1", sha.toUpperCase(), 99, new ByteArrayInputStream(body));

        assertThat(again).isEqualTo(first);
        verify(taskRepo, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void 형식이_틀린_해시나_개수는_400_해시가_다르면_400_상한을_넘으면_413() {
        assertThatThrownBy(() -> service.receive(7L, "w1", "xyz", 1, new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.receive(7L, "w1", sha, -1, new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.receive(7L, "w1", "0".repeat(64), 1, new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        ClosePatchService tiny = new ClosePatchService(taskRepo, store, new ClosePatchProperties(true, 1L),
                mock(PlatformTransactionManager.class), Clock.systemUTC());
        byte[] big = new byte[2 * 1024 * 1024];
        assertThatThrownBy(() -> tiny.receive(7L, "w1", ArtifactStoreTest.sha(big), 1, new ByteArrayInputStream(big)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThat(t.getPatchName()).isNull();
    }

    @Test
    void 현재_패치는_기록과_파일이_모두_있을때만() {
        assertThat(service.current(7L)).isEmpty();
        PatchInfo info = receive();
        assertThat(service.current(7L)).contains(info);
    }
}
