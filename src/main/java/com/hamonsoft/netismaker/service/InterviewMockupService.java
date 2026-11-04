package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.dto.WorkerMockupRequest;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewMockupRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 인터뷰 목업 (스펙 2026-11-03 §5) — 에이전트가 인터뷰 중 올린 화면 목업을 검사해 세션에 쌓는다 (api 프로파일).
 *
 * <p>사유 문구는 에이전트에게 도구 오류로 그대로 돌아간다(인터뷰 서비스의 {@code show_mockup}) — 에이전트가 고쳐 다시 올리거나
 * 글로 설명하고 넘어가게. 그래서 검사는 Bean Validation이 아니라 여기서 하고 {@link TaskException}으로 던진다
 * ({@code GlobalExceptionHandler}가 {@code {error, message}} 바디로 돌려준다).
 */
@Service
@Profile("api")
public class InterviewMockupService {

    /** 목업 하나의 상한 — html의 UTF-8 바이트(글자 수 아님). 디자인 단계({@code DesignResultHarvester})와 같은 값이다. */
    public static final int MAX_BYTES = 200 * 1024;
    /** 세션당 상한 — 버린 것은 세지 않는다. */
    public static final int MAX_PER_SESSION = 40;
    /** 확정 때 구현에 넘길 수 있는 목업 수(프론트 {@code MAX_CONFIRM_MOCKUPS}와 같은 값). */
    public static final int MAX_CONFIRM = 10;
    /** 목업 이름 — 워커의 목업 파일 이름({@code {n}-{name}-v{version}.html})에 들어가므로 경로·공백이 못 들어온다. */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private static final int TITLE_MAX = 80;
    private static final int DESCRIPTION_MAX = 120;

    /**
     * 제목·설명의 줄바꿈·제어문자 정리용. 제목은 계획 파일 머리·PR 본문에 들어가므로 줄바꿈이 남으면 '### Task 9:' 같은 줄이 생긴다.
     * 유니코드 모드라 U+0085·U+2028·U+2029(자바 정규식의 ^가 줄 끝으로 보는 문자)와 NBSP도 공백 하나로 바뀐다.
     */
    private static final Pattern CONTROL_OR_SPACE =
            Pattern.compile("[\\p{Cntrl}\\s\\p{Zl}\\p{Zp}]+", Pattern.UNICODE_CHARACTER_CLASS);

    private final InterviewSessionRepository sessionRepo;
    private final InterviewMockupRepository repo;

    public InterviewMockupService(InterviewSessionRepository sessionRepo, InterviewMockupRepository repo) {
        this.sessionRepo = sessionRepo;
        this.repo = repo;
    }

    /**
     * 목업 한 건을 저장한다. 세션 행 잠금(FOR UPDATE)이 n·버전 매김을 직렬화한다 — 에이전트가 한 메시지에서 show_mockup을
     * 여러 번 동시에 불러도(SDK는 tool_use를 병렬로 돈다) 번호가 겹치지 않고 모두 올라간다. 같은 잠금을 recordQuestion·recordPlan도
     * 잡으므로 턴 끝의 묶기와 업로드가 섞이지 않는다.
     *
     * <p>검사 순서(앞선 것이 이긴다): 세션(없음·질문 → 404) → 간단 인터뷰(400) → 진행 중 아님(409) → 소유 워커(409) →
     * 이름 → 제목·설명 → html → 세션당 상한 → 저장(n = 가장 큰 n + 1, 버전 = 그 이름의 가장 큰 버전 + 1).
     *
     * @return 저장된 목업의 요약(html 없음) — 호출자가 커밋 뒤 SSE로 보낸다
     */
    @Transactional
    public InterviewMockupSummary upload(Long sessionId, String workerId, WorkerMockupRequest req) {
        InterviewSession s = sessionRepo.findByIdForUpdate(sessionId).orElseThrow(TaskException::notFound);
        if (s.isQuestion()) throw TaskException.notFound();
        if (!s.isSuperpowersEnabled()) throw bad("간단 인터뷰에는 목업을 올리지 않습니다");
        if (s.getStatus() != InterviewStatus.RUNNING) {
            throw TaskException.conflict("인터뷰 턴이 진행 중일 때만 목업을 올릴 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        InterviewService.requireWorker(s, workerId);

        String name = req.name();
        if (name == null || !NAME.matcher(name).matches()) { // matches(): $가 끝의 줄바꿈 앞에서도 맞는 find()를 쓰지 않는다
            throw bad("목업 이름은 영문 소문자·숫자·하이픈 64자 이하여야 합니다: " + name);
        }
        String title = normalizeLine(req.title());
        if (isOutside(title, 1, TITLE_MAX)) throw bad("목업 제목은 1~" + TITLE_MAX + "자여야 합니다");
        String description = normalizeLine(req.description());
        if (isOutside(description, 0, DESCRIPTION_MAX)) throw bad("목업 설명은 " + DESCRIPTION_MAX + "자 이하여야 합니다");
        String html = req.html();
        if (html == null || html.isBlank()) throw bad("목업 HTML이 비어 있습니다");
        int bytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw bad("목업이 " + (MAX_BYTES / 1024) + "KB를 넘습니다: " + ((bytes + 1023) / 1024) + "KB");
        }
        if (repo.countBySessionIdAndDiscardedFalse(sessionId) >= MAX_PER_SESSION) {
            throw bad("이 인터뷰의 목업이 " + MAX_PER_SESSION + "개에 이르러 더 올릴 수 없습니다");
        }

        int n = repo.maxN(sessionId) + 1;
        int version = repo.maxVersion(sessionId, name) + 1;
        InterviewMockup m = repo.save(InterviewMockup.create(sessionId, n, name, version, title,
                description.isEmpty() ? null : description, html));
        return new InterviewMockupSummary(m.getId(), m.getN(), m.getName(), m.getVersion(), m.getTitle(),
                m.getDescription(), m.getTurnSeq(), m.getTurnKind(), m.getCreatedAt());
    }

    /**
     * 턴 도중 올라온(안 묶인) 목업을 그 턴에 묶는다 — 턴이 질문(question|design)이나 계획(design 턴)으로 끝날 때.
     * 호출자({@code InterviewService.recordQuestion}·{@code recordPlan})의 트랜잭션 안에서만 부른다: 그 트랜잭션이 잡은
     * 세션 행 잠금이 업로드({@link #upload})와 묶기를 직렬화하고, 턴 저장과 묶기가 함께 커밋·롤백돼야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bindUnbound(Long sessionId, int turnSeq, String turnKind) {
        repo.bindUnbound(sessionId, turnSeq, turnKind);
    }

    /**
     * 끊긴 턴의 안 묶인 목업을 버린다 — 세션이 RUNNING을 떠나 그 턴이 질문·계획으로 끝나지 못할 때(재기동 재큐·실패·취소·만료·작업 삭제).
     * 버린 목업은 목록·상한·확정에서 빠진다. 호출자의 상태 전이 트랜잭션 안에서만 부른다(전이와 함께 커밋·롤백).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void discardUnbound(Long sessionId) {
        repo.discardUnbound(sessionId);
    }

    /** 세션의 목업 목록(html 없음, 버린 것 제외, n 순) — 보고 뒤 SSE push·세션 상세·목록 조회가 쓴다. */
    @Transactional(readOnly = true)
    public List<InterviewMockupSummary> list(Long sessionId) {
        return repo.findSummaries(sessionId);
    }

    /** 줄바꿈·제어문자·연속 공백을 공백 하나로 바꾸고 앞뒤를 자른다. null은 빈 문자열. */
    static String normalizeLine(String s) {
        if (s == null) return "";
        return CONTROL_OR_SPACE.matcher(s).replaceAll(" ").trim();
    }

    /** DB 칸(VARCHAR)이 글자(코드 포인트) 수로 세므로 같은 단위로 잰다. */
    private static boolean isOutside(String s, int min, int max) {
        int len = s.codePointCount(0, s.length());
        return len < min || len > max;
    }

    private static TaskException bad(String message) {
        return new TaskException(HttpStatus.BAD_REQUEST, message);
    }
}
