package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CloseArtifactServiceTest {

    @TempDir Path root;
    private TaskRepository taskRepo;
    private ArtifactStore store;
    private CloseArtifactService service;
    private Task t;
    private boolean lockedDuringRead;

    private final byte[] body = "WAR-BYTES".getBytes(StandardCharsets.UTF_8);
    private String sha;

    @BeforeEach
    void setUp() throws Exception {
        taskRepo = mock(TaskRepository.class);
        store = new ArtifactStore(root.toString());
        service = new CloseArtifactService(taskRepo, store, new CloseArtifactProperties(true, null),
                mock(PlatformTransactionManager.class));
        t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        t.setCloseArtifactMode("REQUIRED");
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        sha = ArtifactStoreTest.sha(body);
    }

    private InputStream tracking(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                lockedDuringRead |= mockingDetails(taskRepo).getInvocations().stream()
                        .anyMatch(i -> i.getMethod().getName().equals("findActiveByIdForUpdate"));
                return super.read(b, off, len);
            }
        };
    }

    @Test
    void 받은_파일을_저장하고_작업에_기록한다() {
        ArtifactInfo info = service.receive(7L, "w1", "repo-task-7-abcdef1.war", "netis-task-7:abcdef1:/app/x.war",
                sha, new ByteArrayInputStream(body));

        assertThat(info.sizeBytes()).isEqualTo(body.length);
        assertThat(t.getArtifactName()).isEqualTo("repo-task-7-abcdef1.war");
        assertThat(t.getArtifactSize()).isEqualTo((long) body.length);
        assertThat(t.getArtifactSha256()).isEqualTo(sha);
        assertThat(t.getArtifactSource()).isEqualTo("netis-task-7:abcdef1:/app/x.war");
        assertThat(t.getArtifactUploadedAt()).isNotNull();
        assertThat(Files.exists(root.resolve("task-7/artifact/repo-task-7-abcdef1.war"))).isTrue();
    }

    @Test
    void 본문을_읽는_동안에는_행을_잠그지_않는다() {
        service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha, tracking(body));
        assertThat(lockedDuringRead).isFalse();
        var order = inOrder(taskRepo);
        order.verify(taskRepo).findActiveById(7L);
        order.verify(taskRepo).findActiveByIdForUpdate(7L);
    }

    @Test
    void 같은_파일을_다시_보내면_본문을_버리고_기존_정보를_준다() {
        service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha, new ByteArrayInputStream(body));
        reset(taskRepo);
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));

        ArtifactInfo again = service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha.toUpperCase(),
                new ByteArrayInputStream(body));

        assertThat(again.name()).isEqualTo("repo-task-7-abcdef1.war");
        verify(taskRepo, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void 상태_워커_모드가_안_맞으면_409() {
        t.setWorkerId("w2");
        assertThatThrownBy(() -> service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha,
                new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        t.setWorkerId("w1");
        t.setCloseArtifactMode(null);
        assertThatThrownBy(() -> service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha,
                new ByteArrayInputStream(body))).isInstanceOf(TaskException.class).hasMessageContaining("산출물 보관 대상");
        t.setCloseArtifactMode("REQUIRED");
        t.setStatus(TaskStatus.CLOSE_FAILED);
        assertThatThrownBy(() -> service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha,
                new ByteArrayInputStream(body))).isInstanceOf(TaskException.class).hasMessageContaining("종료중");
    }

    @Test
    void 이름_해시_형식이_틀리거나_해시가_다르거나_상한을_넘으면_4xx() {
        assertThatThrownBy(() -> service.receive(7L, "w1", "../x.war", null, sha, new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.receive(7L, "w1", "a.war", null, "xyz", new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.receive(7L, "w1", "a.war", null, "0".repeat(64), new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        CloseArtifactService tiny = new CloseArtifactService(taskRepo, store, new CloseArtifactProperties(true, 1L),
                mock(PlatformTransactionManager.class));
        byte[] big = new byte[2 * 1024 * 1024];
        assertThatThrownBy(() -> tiny.receive(7L, "w1", "a.war", null, ArtifactStoreTest.sha(big), new ByteArrayInputStream(big)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThat(t.getArtifactName()).isNull();
    }

    @Test
    void 현재_산출물은_기록과_파일이_모두_있을때만() {
        assertThat(service.current(7L)).isEmpty();
        service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha, new ByteArrayInputStream(body));
        assertThat(service.current(7L)).contains(new ArtifactInfo("repo-task-7-abcdef1.war", body.length, sha));
    }

    @Test
    void 저장_중_IO_오류는_500으로_바꾸되_서버_로그에_원인을_남긴다() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(CloseArtifactService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            InputStream broken = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("disk full");
                }
            };
            assertThatThrownBy(() -> service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha, broken))
                    .isInstanceOfSatisfying(TaskException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                        assertThat(e.getMessage()).contains("disk full");
                    });
            assertThat(appender.list).anySatisfy(ev -> {
                assertThat(ev.getLevel()).isEqualTo(Level.WARN);
                assertThat(ev.getFormattedMessage()).contains("taskId=7");
                assertThat(ev.getThrowableProxy().getMessage()).isEqualTo("disk full");
            });
            assertThat(t.getArtifactName()).isNull();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
