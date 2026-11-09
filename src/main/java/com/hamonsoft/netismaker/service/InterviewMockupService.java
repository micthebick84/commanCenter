package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.dto.InterviewMockupView;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /** 계획 본문의 목업 가리킴 — '목업 3'. 앞 번호만 찾는다('목업 3, 5'의 5는 세지 않는다). */
    private static final Pattern PLAN_MOCKUP_REF = Pattern.compile("목업 (\\d+)");

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

    /**
     * 목업 단건(html 포함) — {@code GET /api/interviews/{sid}/mockups/{id}}. 세션 조회 권한은 호출자가 이미 확인했다(컨트롤러).
     * 다른 세션의 id·버린 목업은 존재를 숨겨 404다({@code QuestionService.getAttachment}와 같은 선례): 소속과 버림은 조회 조건에
     * 들어 있어 행을 읽은 뒤 비교하지 않는다. {@code taskId}는 세션에서 읽는다(새 탭의 "인터뷰로" 링크·부제).
     */
    @Transactional(readOnly = true)
    public InterviewMockupView get(Long sessionId, Long mockupId) {
        InterviewMockup m = repo.findByIdAndSessionIdAndDiscardedFalse(mockupId, sessionId)
                .orElseThrow(TaskException::notFound);
        InterviewSession s = sessionRepo.findById(sessionId).orElseThrow(TaskException::notFound);
        return new InterviewMockupView(m.getId(), sessionId, s.getTaskId(), m.getN(), m.getName(), m.getVersion(),
                m.getTitle(), m.getDescription(), m.getTurnSeq(), m.getTurnKind(), m.getCreatedAt(), m.getHtml());
    }

    /**
     * 확정에서 정한 목업 선택 — {@code idsByN}은 구현에 넘길 목업 id(n 순), {@code excludedNumbers}는 계획이 가리켰는데
     * 넘기지 않은 번호(오름차순), {@code historyReason}은 상태 이력에 남길 문구(둘 다 비면 null — 이력을 남기지 않는다).
     */
    public record ConfirmSelection(List<Long> idsByN, List<Integer> excludedNumbers, String historyReason) {
        static final ConfirmSelection EMPTY = new ConfirmSelection(List.of(), List.of(), null);
    }

    /**
     * 확정 때 고른 목업을 검증하고 제외 번호·이력 문구를 계산한다. 확정의 첫 상태 변경 전에 부른다 — 400이면 아무것도 바뀌지 않는다.
     * ids가 null·빈 목록이어도 부른다(모두 해제해도 계획이 가리킨 목업은 제외로 남는다). 순서: 중복 제거 → 10개 초과 400 →
     * 이 세션의 버리지 않은 목업이 아니면 400 → 제외 계산(계획이 가리킨 번호 ∩ 세션의 실제 번호 − 고른 번호).
     * 세션에 목업이 없으면 계획에 '목업 N'이 있어도 제외는 비어 있다(목업 없는 인터뷰는 지금과 같다).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConfirmSelection resolveForConfirm(Long sessionId, List<Long> ids, String planMarkdown) {
        Set<Long> unique = new LinkedHashSet<>();
        if (ids != null) ids.stream().filter(Objects::nonNull).forEach(unique::add);
        if (unique.size() > MAX_CONFIRM) throw bad("구현에 넘길 목업은 " + MAX_CONFIRM + "개까지입니다");

        List<InterviewMockup> found = unique.isEmpty() ? List.of()
                : repo.findByIdInAndDiscardedFalseOrderByNAsc(unique);
        List<InterviewMockup> mine = found.stream().filter(m -> sessionId.equals(m.getSessionId())).toList();
        if (mine.size() != unique.size()) {
            Set<Long> okIds = mine.stream().map(InterviewMockup::getId).collect(Collectors.toSet());
            String notMine = unique.stream().filter(id -> !okIds.contains(id)).map(String::valueOf)
                    .collect(Collectors.joining(", "));
            throw bad("이 인터뷰의 목업이 아닙니다: " + notMine);
        }

        Set<Integer> existing = repo.findSummaries(sessionId).stream()
                .map(InterviewMockupSummary::n).collect(Collectors.toSet());
        Set<Integer> chosenNs = mine.stream().map(InterviewMockup::getN).collect(Collectors.toSet());
        List<Integer> excluded = planMockupNumbers(planMarkdown).stream()
                .filter(existing::contains).filter(n -> !chosenNs.contains(n)).sorted().toList();
        if (mine.isEmpty() && excluded.isEmpty()) return ConfirmSelection.EMPTY;

        String reason = "구현 목업: " + (mine.isEmpty() ? "없음" : mine.stream()
                .map(m -> "목업 " + m.getN() + "(" + m.getTitle() + " v" + m.getVersion() + ")")
                .collect(Collectors.joining(", ")));
        if (!excluded.isEmpty()) {
            reason += " / 제외: " + excluded.stream().map(n -> "목업 " + n).collect(Collectors.joining(", "));
        }
        return new ConfirmSelection(mine.stream().map(InterviewMockup::getId).toList(), excluded, reason);
    }

    /** 계획 본문이 가리킨 목업 번호 — '목업 N' 전부, 중복 제거, 나온 순. 본문이 없으면 빈 목록. */
    static List<Integer> planMockupNumbers(String planMarkdown) {
        if (planMarkdown == null) return List.of();
        Set<Integer> out = new LinkedHashSet<>();
        Matcher m = PLAN_MOCKUP_REF.matcher(planMarkdown);
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) { // int를 넘는 숫자는 번호가 아니다
            }
        }
        return new ArrayList<>(out);
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
