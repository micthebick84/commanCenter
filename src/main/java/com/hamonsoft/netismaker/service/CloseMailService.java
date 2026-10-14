package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 종료 등록자 메일 (스펙 2026-10-14 §7). 산출물이 보관된 작업종료만, 등록자에게만(참조 없음), 앱 비밀번호 SMTP.
 * 실패해도 작업종료를 되돌리지 않는다 — close_mail_status와 상태 이력에 남기고 관리자가 다시 보낸다.
 *
 * <p>SMTP는 연결·읽기·쓰기 타임아웃이 각 10초라 최악엔 30초 넘게 걸린다. 그동안 DB 커넥션과 행 잠금을 쥐면
 * Hikari 누수 경보(leak-detection-threshold 30초)에 걸리고 같은 작업을 건드리는 다른 요청이 막히므로
 * 세 단계로 나눈다(CloseArtifactService와 같은 방식).
 * <ol>
 *   <li>준비 — 읽기 전용 트랜잭션, 잠금 없음: 대상인지 보고 수신 주소·메시지를 정한다. 대상이 아니면 null.</li>
 *   <li>발송 — 트랜잭션 밖: SMTP 호출. MailException은 FAILED로 바꾼다.</li>
 *   <li>기록 — 새 트랜잭션, 행 잠금 후 대상인지 다시 판정: 그새 삭제·상태 변경이면 결과를 남기지 않는다.</li>
 * </ol>
 */
@Service
@Profile("api")
@Slf4j
public class CloseMailService {

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String NO_ADDRESS = "NO_ADDRESS";
    public static final String DISABLED = "DISABLED";

    /** 준비 단계가 정한 처리 결과(또는 발송 결과) — 상태 값과 이력 문구. */
    private record Result(String status, String note) {}

    /** 준비 단계 산출물. skip이 있으면 발송 없이 그 결과를 기록하고, 없으면 message를 보낸다. */
    private record Plan(Result skip, JavaMailSender sender, String to, SimpleMailMessage message) {}

    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final String username;
    private final String from;
    private final String publicBaseUrl;

    public CloseMailService(TaskRepository taskRepo, TaskStatusHistoryRepository historyRepo,
                            ObjectProvider<JavaMailSender> mailSender, JdbcTemplate jdbc,
                            PlatformTransactionManager txManager,
                            @Value("${spring.mail.username:}") String username,
                            @Value("${app.close.mail.from:}") String from,
                            @Value("${app.public-base-url:}") String publicBaseUrl) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.mailSender = mailSender;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.username = username;
        this.from = from;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * 작업종료 직후(CloseMailListener)와 다시 보내기가 함께 쓴다. 대상이 아니거나, 발송하는 사이 대상에서
     * 빠졌으면 null — 아무것도 남기지 않는다. 트랜잭션 없이 부르고, 안에서 단계별로 짧게 연다.
     */
    public String send(Long taskId) {
        // ① 준비 — 잠금 없음
        Task snapshot = readTx.execute(s -> {
            Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
            return eligible(t) ? t : null;
        });
        if (snapshot == null) return null;
        // 수신 주소 조회(JDBC)는 읽기 트랜잭션 밖에서 — 조회 오류가 트랜잭션을 오염시키지 않게
        Plan plan = plan(snapshot);

        // ② 발송 — 트랜잭션 밖
        Result result = deliver(taskId, plan);

        // ③ 기록 — 잠그고 다시 판정
        return tx.execute(s -> {
            Task t = taskRepo.findActiveByIdForUpdate(taskId).orElse(null);
            if (t == null || !eligible(t)) {
                log.warn("종료 메일 결과를 남기지 않음(발송 중 작업이 삭제되었거나 상태가 바뀜): task={} 결과={}",
                        taskId, result.status());
                return null;
            }
            t.setCloseMailStatus(result.status());
            t.setCloseMailAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(), t.getStatus(), t.getStatus(),
                    "system", "mail", result.note()));
            return result.status();
        });
    }

    /** 관리자 다시 보내기 — 작업종료 + 산출물 모드 + 산출물 기록이 있어야 한다. */
    public String resend(Long taskId) {
        Task t = readTx.execute(s -> taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound));
        if (!eligible(t)) {
            throw TaskException.conflict("작업종료이고 산출물이 보관된 작업만 메일을 다시 보낼 수 있습니다");
        }
        String status = send(taskId);
        if (status == null) {
            throw TaskException.conflict("메일을 보내는 사이 작업 상태가 바뀌어 결과를 남기지 못했습니다");
        }
        return status;
    }

    private Plan plan(Task t) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (username == null || username.isBlank() || sender == null) {
            return new Plan(new Result(DISABLED, "메일 건너뜀: 메일 설정 없음"), null, null, null);
        }
        String to = recipient(t);
        if (to == null) {
            return new Plan(new Result(NO_ADDRESS, "메일 건너뜀: 수신 주소 없음"), null, null, null);
        }
        return new Plan(null, sender, to, message(t, to));
    }

    private Result deliver(Long taskId, Plan plan) {
        if (plan.skip() != null) return plan.skip();
        try {
            plan.sender().send(plan.message());
            return new Result(SENT, "메일 발송: " + plan.to());
        } catch (MailException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String note = "메일 실패: " + GitRemotes.mask(msg.length() > 300 ? msg.substring(0, 300) : msg);
            log.warn("종료 메일 실패: task={} {}", taskId, note);
            return new Result(FAILED, note);
        }
    }

    private static boolean eligible(Task t) {
        return t.getStatus() == TaskStatus.CLOSED
                && CloseStep.ARTIFACT_REQUIRED.equals(t.getCloseArtifactMode())
                && t.getArtifactName() != null;
    }

    /** 저장된 주소(등록·종료 요청 때 JWT email) → 없으면 com."user".email(netis-backend 공용 테이블). */
    String recipient(Task t) {
        if (t.getRequesterEmail() != null && !t.getRequesterEmail().isBlank()) return t.getRequesterEmail().trim();
        try {
            List<String> rows = jdbc.queryForList("SELECT email FROM com.\"user\" WHERE user_id = ?", String.class,
                    t.getRequesterId());
            return rows.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).findFirst().orElse(null);
        } catch (DataAccessException e) {
            log.warn("종료 메일 수신 주소 조회 실패: task={} {}", t.getId(), e.getMessage());
            return null;
        }
    }

    private SimpleMailMessage message(Task t, String to) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from == null || from.isBlank() ? username : from);
        m.setTo(to);
        m.setSubject(subject(t));
        m.setText(body(t));
        return m;
    }

    static String subject(Task t) {
        String s = ("[Symphony] 작업 " + t.getId() + " 종료 — " + (t.getTitle() == null ? "" : t.getTitle()))
                .replaceAll("[\\r\\n]+", " ");
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private String body(Task t) {
        String repo = t.getRepoAlias() != null ? t.getRepoAlias() : t.getGithubRepo();
        String mb = String.format(Locale.ROOT, "%.1f",
                (t.getArtifactSize() == null ? 0 : t.getArtifactSize()) / 1024.0 / 1024.0);
        String link = CloseLinks.taskUrl(publicBaseUrl, t.getId());
        return "작업 " + t.getId() + " \"" + t.getTitle() + "\"이 종료되었습니다.\n\n"
                + "레포: " + repo + "\n"
                + "산출물: " + t.getArtifactName() + " (" + mb + " MB)\n"
                + "배포 커밋: " + sha7(t.getHeadSha()) + "   병합 커밋: " + sha7(t.getMergeCommitSha()) + "\n\n"
                + (link == null ? "Symphony에 로그인해 작업 " + t.getId() + " 화면에서 내려받으세요."
                                : "로그인 후 내려받기: " + link) + "\n\n"
                + "작업을 삭제하면 30일 뒤 파일도 지워집니다.\n";
    }

    private static String sha7(String sha) {
        return sha == null ? "-" : sha.substring(0, Math.min(7, sha.length()));
    }
}
