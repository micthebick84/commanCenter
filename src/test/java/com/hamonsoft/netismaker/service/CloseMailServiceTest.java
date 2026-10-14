package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloseMailServiceTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private JavaMailSender sender;
    private JdbcTemplate jdbc;
    private Task t;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        sender = mock(JavaMailSender.class);
        jdbc = mock(JdbcTemplate.class);
        t = Task.create("owner/repo", "main", "로그인\n수정", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.CLOSED);
        t.setCloseArtifactMode("REQUIRED");
        t.setArtifactName("repo-task-7-abcdef1.war");
        t.setArtifactSize(266_000_000L);
        t.setHeadSha("abcdef1234567890");
        t.setMergeCommitSha("a05196e0000");
        t.setRepoAlias("동부병원");
        t.setRequesterEmail("user1@hamonsoft.co.kr");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private CloseMailService service(String username) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return new CloseMailService(taskRepo, historyRepo, p, jdbc, mock(PlatformTransactionManager.class),
                username, "", "https://app.test/");
    }

    private String lastHistory() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue().getReason();
    }

    @Test
    void 등록자에게만_보내고_보냄을_남긴다() {
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("SENT");

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(cap.capture());
        SimpleMailMessage m = cap.getValue();
        assertThat(m.getTo()).containsExactly("user1@hamonsoft.co.kr");
        assertThat(m.getCc()).isNull();
        assertThat(m.getFrom()).isEqualTo("bot@hamonsoft.co.kr");
        assertThat(m.getSubject()).isEqualTo("[Symphony] 작업 7 종료 — 로그인 수정");
        assertThat(m.getText()).contains("레포: 동부병원").contains("산출물: repo-task-7-abcdef1.war (253.7 MB)")
                .contains("배포 커밋: abcdef1   병합 커밋: a05196e")
                .contains("로그인 후 내려받기: https://app.test/tasks/7")
                .contains("작업을 삭제하면 30일 뒤 파일도 지워집니다.");
        assertThat(t.getCloseMailStatus()).isEqualTo("SENT");
        assertThat(t.getCloseMailAt()).isNotNull();
        assertThat(lastHistory()).isEqualTo("메일 발송: user1@hamonsoft.co.kr");
    }

    @Test
    void SMTP_오류면_FAILED를_남기고_예외를_내지_않는다() {
        doThrow(new MailSendException("Connection refused")).when(sender).send(any(SimpleMailMessage.class));
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("FAILED");
        assertThat(t.getCloseMailStatus()).isEqualTo("FAILED");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSED);
        assertThat(lastHistory()).startsWith("메일 실패: ");
    }

    @Test
    void 실패_사유의_자격증명은_가리고_300자로_자른다() {
        doThrow(new MailSendException("auth failed at smtp://bot:app-secret@smtp.gmail.com " + "x".repeat(400)))
                .when(sender).send(any(SimpleMailMessage.class));
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("FAILED");
        String reason = lastHistory();
        assertThat(reason).startsWith("메일 실패: ").doesNotContain("app-secret").contains("***@");
        assertThat(reason.length()).isLessThanOrEqualTo("메일 실패: ".length() + 300);
    }

    @Test
    void 자르는_경계에_걸친_자격증명도_가린_뒤_자른다() {
        // "smtp://bot:app-secret@"의 비밀번호 중간(300자째)에서 잘리는 위치 — 자른 뒤 가리면 정규식이 못 잡아 일부가 남는다
        doThrow(new MailSendException("x".repeat(285) + " smtp://bot:app-secret@smtp.gmail.com"))
                .when(sender).send(any(SimpleMailMessage.class));
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("FAILED");
        String reason = lastHistory();
        assertThat(reason).contains("smtp://***@").doesNotContain("bot:").doesNotContain("app");
        assertThat(reason.length()).isLessThanOrEqualTo("메일 실패: ".length() + 300);
    }

    @Test
    void SMTP_발송은_행_잠금_없이_하고_결과를_남길_때만_잠근다() {
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("SENT");

        InOrder order = inOrder(taskRepo, sender);
        order.verify(taskRepo).findActiveById(7L);
        order.verify(sender).send(any(SimpleMailMessage.class));
        order.verify(taskRepo).findActiveByIdForUpdate(7L);
        // 발송 전에는 한 번도 잠그지 않았다 — 전체 호출이 발송 뒤 기록 단계의 1회뿐
        verify(taskRepo, times(1)).findActiveByIdForUpdate(7L);
    }

    @Test
    void 발송하는_사이_작업이_삭제되면_결과를_남기지_않는다() {
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.empty());
        CloseMailService svc = service("bot@hamonsoft.co.kr");

        assertThat(svc.send(7L)).isNull();

        verify(sender).send(any(SimpleMailMessage.class));
        verify(historyRepo, never()).save(any());
        assertThat(t.getCloseMailStatus()).isNull();
        // 다시 보내기는 null을 그대로 응답에 싣지 않고 409로 알린다
        assertThatThrownBy(() -> svc.resend(7L)).isInstanceOfSatisfying(TaskException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 발송하는_사이_작업종료가_아니게_되면_결과를_남기지_않는다() {
        doAnswer(inv -> {
            t.setStatus(TaskStatus.CLOSE_FAILED);
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));

        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isNull();

        verify(historyRepo, never()).save(any());
        assertThat(t.getCloseMailStatus()).isNull();
    }

    @Test
    void 설정이_없으면_DISABLED_주소가_없으면_NO_ADDRESS() {
        assertThat(service("").send(7L)).isEqualTo("DISABLED");
        verify(sender, never()).send(any(SimpleMailMessage.class));

        t.setRequesterEmail(null);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("user1"))).thenReturn(List.of());
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("NO_ADDRESS");
        assertThat(lastHistory()).isEqualTo("메일 건너뜀: 수신 주소 없음");
    }

    @Test
    void 저장된_주소가_없으면_사용자_테이블의_주소로_보낸다() {
        t.setRequesterEmail(null);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("user1"))).thenReturn(List.of("u1@hamonsoft.co.kr"));
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("SENT");
        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(cap.capture());
        assertThat(cap.getValue().getTo()).containsExactly("u1@hamonsoft.co.kr");
    }

    @Test
    void 산출물_없이_마무리했거나_대상이_아니면_아무것도_하지_않는다() {
        t.setCloseArtifactMode("SKIPPED");
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isNull();
        t.setCloseArtifactMode(null);
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isNull();
        verify(sender, never()).send(any(SimpleMailMessage.class));
        verify(historyRepo, never()).save(any());
        assertThat(t.getCloseMailStatus()).isNull();
    }

    @Test
    void 다시_보내기는_작업종료이고_산출물이_있을때만() {
        t.setStatus(TaskStatus.CLOSE_FAILED);
        assertThatThrownBy(() -> service("bot@hamonsoft.co.kr").resend(7L)).isInstanceOf(TaskException.class);
        t.setStatus(TaskStatus.CLOSED);
        assertThat(service("bot@hamonsoft.co.kr").resend(7L)).isEqualTo("SENT");
    }
}
