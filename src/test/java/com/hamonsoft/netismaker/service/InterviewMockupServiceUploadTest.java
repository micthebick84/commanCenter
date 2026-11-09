package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.dto.WorkerMockupRequest;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewMockupRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 목업 업로드 검사·번호 매김 (스펙 2026-11-03 §5). 검사 순서와 사유 문구는 에이전트에게 도구 오류로 그대로 돌아가므로 문구를 그대로 단언한다.
 */
class InterviewMockupServiceUploadTest {

    private static final long SID = 5L;
    private static final String HTML = "<!doctype html><html><body>목록</body></html>";

    InterviewSessionRepository sessionRepo;
    InterviewMockupRepository repo;
    InterviewMockupService service;

    @BeforeEach
    void setUp() {
        sessionRepo = mock(InterviewSessionRepository.class);
        repo = mock(InterviewMockupRepository.class);
        service = new InterviewMockupService(sessionRepo, repo);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** InterviewServiceRestartTest.running과 같은 모양 — 워커 iw-1이 잡은 RUNNING 세션(superpowers 켬). */
    private InterviewSession running(long id, String worker) {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", id);
        s.setStatus(InterviewStatus.RUNNING);
        s.setWorkerId(worker);
        when(sessionRepo.findByIdForUpdate(id)).thenReturn(Optional.of(s));
        return s;
    }

    private static WorkerMockupRequest req(String name, String title, String description, String html) {
        return new WorkerMockupRequest(name, title, description, html);
    }

    private static WorkerMockupRequest ok() {
        return req("list-filter-a", "A안", "필터를 위에 둔다", HTML);
    }

    private void assertRejected(WorkerMockupRequest req, HttpStatus status, String message) {
        assertThatThrownBy(() -> service.upload(SID, "iw-1", req))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
        verify(repo, never()).save(any());
    }

    @Test
    void 첫_목업은_n1_v1_같은이름은_v2() {
        running(SID, "iw-1");

        InterviewMockupSummary first = service.upload(SID, "iw-1", ok());

        assertThat(first.n()).isEqualTo(1);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.name()).isEqualTo("list-filter-a");
        assertThat(first.title()).isEqualTo("A안");
        assertThat(first.description()).isEqualTo("필터를 위에 둔다");
        assertThat(first.turnSeq()).as("어느 턴에도 안 묶인 채 저장").isNull();
        assertThat(first.turnKind()).isNull();

        // 같은 이름으로 다시 — 이 세션의 가장 큰 n이 1, 그 이름의 가장 큰 버전이 1
        when(repo.maxN(SID)).thenReturn(1);
        when(repo.maxVersion(SID, "list-filter-a")).thenReturn(1);
        InterviewMockupSummary second = service.upload(SID, "iw-1", ok());
        assertThat(second.n()).isEqualTo(2);
        assertThat(second.version()).isEqualTo(2);

        // 다른 이름은 n만 이어지고 버전은 1부터
        when(repo.maxN(SID)).thenReturn(2);
        InterviewMockupSummary other = service.upload(SID, "iw-1", req("list-filter-b", "B안", null, HTML));
        assertThat(other.n()).isEqualTo(3);
        assertThat(other.version()).isEqualTo(1);
        assertThat(other.description()).isNull();

        ArgumentCaptor<InterviewMockup> saved = ArgumentCaptor.forClass(InterviewMockup.class);
        verify(repo, org.mockito.Mockito.times(3)).save(saved.capture());
        InterviewMockup row = saved.getAllValues().get(0);
        assertThat(row.getSessionId()).isEqualTo(SID);
        assertThat(row.getHtml()).isEqualTo(HTML);
        assertThat(row.getSizeBytes()).isEqualTo(HTML.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(row.isDiscarded()).isFalse();
    }

    @Test
    void 버린_n은_다시_쓰지_않는다() {
        running(SID, "iw-1");
        // n 1·2·3이 있었고 3이 버려졌다 — 버리지 않은 행은 2개지만 maxN은 버린 행도 센다
        when(repo.countBySessionIdAndDiscardedFalse(SID)).thenReturn(2L);
        when(repo.maxN(SID)).thenReturn(3);

        assertThat(service.upload(SID, "iw-1", ok()).n()).isEqualTo(4);
    }

    @Test
    void 검증_사유_문구() {
        running(SID, "iw-1");

        // 5. 이름
        String nameMsg = "목업 이름은 영문 소문자·숫자·하이픈 64자 이하여야 합니다: ";
        for (String bad : new String[] {"Bad Name", "UPPER", "-lead", "a_b", "한글", "a".repeat(65), "../x", ""}) {
            assertRejected(req(bad, "t", null, HTML), HttpStatus.BAD_REQUEST, nameMsg + bad);
        }
        assertRejected(req(null, "t", null, HTML), HttpStatus.BAD_REQUEST, nameMsg + "null");
        // $는 끝의 줄바꿈 앞에서도 맞지만 전체 일치(matches)라 거절된다
        assertRejected(req("abc\n", "t", null, HTML), HttpStatus.BAD_REQUEST, nameMsg + "abc\n");

        // 6. 제목(정규화 뒤)·설명
        String titleMsg = "목업 제목은 1~80자여야 합니다";
        assertRejected(req("a", null, null, HTML), HttpStatus.BAD_REQUEST, titleMsg);
        assertRejected(req("a", "", null, HTML), HttpStatus.BAD_REQUEST, titleMsg);
        assertRejected(req("a", " \n\t ", null, HTML), HttpStatus.BAD_REQUEST, titleMsg);
        assertRejected(req("a", "가".repeat(81), null, HTML), HttpStatus.BAD_REQUEST, titleMsg);
        String descMsg = "목업 설명은 120자 이하여야 합니다";
        assertRejected(req("a", "t", "가".repeat(121), HTML), HttpStatus.BAD_REQUEST, descMsg);
        // 제목이 설명보다 먼저 검사된다
        assertRejected(req("a", "", "가".repeat(121), HTML), HttpStatus.BAD_REQUEST, titleMsg);

        // 7. html
        String emptyMsg = "목업 HTML이 비어 있습니다";
        assertRejected(req("a", "t", null, null), HttpStatus.BAD_REQUEST, emptyMsg);
        assertRejected(req("a", "t", null, ""), HttpStatus.BAD_REQUEST, emptyMsg);
        assertRejected(req("a", "t", null, " \n\t "), HttpStatus.BAD_REQUEST, emptyMsg);
        assertRejected(req("a", "t", null, "a".repeat(InterviewMockupService.MAX_BYTES + 1)),
                HttpStatus.BAD_REQUEST, "목업이 200KB를 넘습니다: 201KB");

        // 8. 세션당 상한 — 40개 이상이면 거절, 39개면 올라간다(버린 것은 세지 않는다)
        when(repo.countBySessionIdAndDiscardedFalse(SID)).thenReturn(40L);
        assertRejected(ok(), HttpStatus.BAD_REQUEST, "이 인터뷰의 목업이 40개에 이르러 더 올릴 수 없습니다");
        when(repo.countBySessionIdAndDiscardedFalse(SID)).thenReturn(39L);
        assertThat(service.upload(SID, "iw-1", ok()).n()).isEqualTo(1);
    }

    @Test
    void 제목_줄바꿈과_제어문자는_공백하나로() {
        running(SID, "iw-1");

        // [RF5] 제목이 계획 파일 머리·PR 본문에 들어가도 새 줄(### Task 9:)이 생기지 않는다
        InterviewMockupSummary m = service.upload(SID, "iw-1", req("a", "A안\n### Task 9:\t x", "첫 줄\r\n둘째 줄\u0007", HTML));
        assertThat(m.title()).isEqualTo("A안 ### Task 9: x");
        assertThat(m.description()).isEqualTo("첫 줄 둘째 줄");

        // 자바 정규식의 ^가 줄 끝으로 보는 U+0085·U+2028·U+2029와 NBSP·전각 공백도 공백 하나
        assertThat(InterviewMockupService.normalizeLine("a\u0085### Task 1 b c d　e")).isEqualTo("a ### Task 1 b c d e");
        assertThat(InterviewMockupService.normalizeLine("  앞뒤   공백\n\n")).isEqualTo("앞뒤 공백");
        assertThat(InterviewMockupService.normalizeLine(null)).isEmpty();
        // 설명이 정규화 뒤 비면 null로 저장
        assertThat(service.upload(SID, "iw-1", req("b", "t", " \n ", HTML)).description()).isNull();
        // 정규화 뒤 길이로 잰다 — 줄바꿈 때문에 81자를 넘어 보여도 정규화 뒤 80자면 올라간다
        String eighty = "가".repeat(40) + "\n\n\n\n" + "나".repeat(39);
        assertThat(service.upload(SID, "iw-1", req("c", eighty, null, HTML)).title()).hasSize(80);
    }

    @Test
    void 크기는_바이트_기준() {
        running(SID, "iw-1");
        String over = "가".repeat(68267);               // 3바이트 × 68267 = 204 801바이트
        assertThat(over.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(204_801);
        assertThat(over.length()).as("글자 수로는 상한 안이라 글자 수 기준이면 통과해 버린다").isLessThan(InterviewMockupService.MAX_BYTES);

        assertRejected(req("a", "t", null, over), HttpStatus.BAD_REQUEST, "목업이 200KB를 넘습니다: 201KB");

        String exact = "가".repeat(68266) + "ab";          // 204 798 + 2 = 204 800바이트
        assertThat(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(204_800);
        InterviewMockupSummary saved = service.upload(SID, "iw-1", req("a", "t", null, exact));
        assertThat(saved.n()).isEqualTo(1);
        ArgumentCaptor<InterviewMockup> row = ArgumentCaptor.forClass(InterviewMockup.class);
        verify(repo).save(row.capture());
        assertThat(row.getValue().getSizeBytes()).isEqualTo(204_800);
    }

    @Test
    void 간단인터뷰_질문세션_RUNNING아님_다른워커() {
        // 간단 인터뷰(superpowers 끔) — 400. 상태·워커 검사보다 먼저다
        InterviewSession simple = running(SID, "iw-1");
        simple.setSuperpowersEnabled(false);
        assertRejected(ok(), HttpStatus.BAD_REQUEST, "간단 인터뷰에는 목업을 올리지 않습니다");
        simple.setStatus(InterviewStatus.AWAITING_INPUT);
        assertRejected(ok(), HttpStatus.BAD_REQUEST, "간단 인터뷰에는 목업을 올리지 않습니다");

        // 질문 세션 — 404(없는 세션과 같은 응답)
        InterviewSession question = InterviewSession.createQuestion("owner/repo", "main", "T", "q", "u1",
                List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(question, "id", 6L);
        question.setStatus(InterviewStatus.RUNNING);
        question.setWorkerId("iw-1");
        when(sessionRepo.findByIdForUpdate(6L)).thenReturn(Optional.of(question));
        assertThatThrownBy(() -> service.upload(6L, "iw-1", ok()))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        // 없는 세션
        when(sessionRepo.findByIdForUpdate(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload(7L, "iw-1", ok()))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // RUNNING이 아님 — 409, 현재 상태의 한글 값. 워커 검사보다 먼저다(소유 워커가 달라도 이 문구)
        InterviewSession awaiting = running(8L, "iw-1");
        awaiting.setStatus(InterviewStatus.AWAITING_INPUT);
        assertThatThrownBy(() -> service.upload(8L, "iw-2", ok()))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("인터뷰 턴이 진행 중일 때만 목업을 올릴 수 있습니다 (현재: 입력대기)");
                });

        // 다른 워커가 잡은 RUNNING — 409 (InterviewService.requireWorker와 같은 문구)
        running(9L, "iw-1");
        assertThatThrownBy(() -> service.upload(9L, "iw-2", ok()))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("다른 워커가 잡은 인터뷰입니다 (소유: iw-1)");
                });

        verify(repo, never()).save(any());
        verify(repo, never()).maxN(anyLong());
        verify(repo, never()).maxVersion(anyLong(), anyString());
    }

    @Test
    void 요청_toString에_html이_없다() {
        String secret = "<html><body>SECRET-MOCKUP-BODY</body></html>";
        String text = req("list-a", "A안", "설명", secret).toString();

        assertThat(text).doesNotContain("SECRET-MOCKUP-BODY").doesNotContain("<html>");
        assertThat(text).contains("htmlLength=" + secret.length()).contains("name=list-a");
        assertThat(req("a", "t", null, null).toString()).contains("htmlLength=0");
    }

    @Test
    void 이름은_세션과_이름으로_버전을_센다() {
        running(SID, "iw-1");
        when(repo.maxVersion(eq(SID), eq("a"))).thenReturn(4);

        assertThat(service.upload(SID, "iw-1", req("a", "t", null, HTML)).version()).isEqualTo(5);
        assertThat(service.upload(SID, "iw-1", req("b", "t", null, HTML)).version()).isEqualTo(1);
    }
}
