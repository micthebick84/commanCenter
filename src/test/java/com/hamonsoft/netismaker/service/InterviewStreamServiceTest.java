package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.ActivityEvent;
import com.hamonsoft.netismaker.dto.DesignEvent;
import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.dto.QuestionEvent;
import com.hamonsoft.netismaker.dto.WorkerActivityRequest;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.repository.InterviewMockupRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterviewStreamServiceTest {

    private final InterviewTurnRepository turnRepo = mock(InterviewTurnRepository.class);
    private final ObjectMapper json = new ObjectMapper();

    /** SseEmitter.send를 가로채 이벤트 이름을 수집하기는 어려우므로,
     *  서비스가 NPE 없이 동작하고 done 후 레지스트리에서 제거되는지를 검증한다. */
    @Test
    void subscribe_replays_existing_turns_then_registers_live() {
        when(turnRepo.findBySessionIdOrderBySeqAsc(42L))
                .thenReturn(List.of(turn(1, "assistant", "question", "어떤 인증?")));
        var svc = new InterviewStreamService(turnRepo, json);

        SseEmitter e = svc.subscribe(42L);
        assertThat(e).isNotNull();
        // 라이브 푸시가 등록된 emitter에 도달 — 예외 없이 호출되면 통과
        svc.pushQuestion(42L, 2, "다음 질문?");
        svc.pushDesign(42L, 3, "# 설계 초안");
        svc.pushStatus(42L, InterviewStatus.AWAITING_INPUT);          // 영문 enum name 전달
        svc.pushPlanReady(42L, "# 설계", "# 플랜", "[]");              // JSON 객체 전달
    }

    /**
     * 와이어 계약 잠금: question/design 이벤트는 프론트(useInterviewStream)가 JSON.parse하는
     * 객체여야 한다 — bare 문자열이면 프론트가 드롭한다. (status는 별개로 bare 영문 enum.)
     */
    @Test
    void question_and_design_events_serialize_to_frontend_json_contract() throws Exception {
        JsonNode q = json.readTree(json.writeValueAsString(new QuestionEvent(3, "어떤 인증?")));
        assertThat(q.get("seq").asInt()).isEqualTo(3);
        assertThat(q.get("content").asText()).isEqualTo("어떤 인증?");

        JsonNode d = json.readTree(json.writeValueAsString(new DesignEvent("design-5", "설계", "본문", false)));
        assertThat(d.get("key").asText()).isEqualTo("design-5");
        assertThat(d.get("title").asText()).isEqualTo("설계");
        assertThat(d.get("body").asText()).isEqualTo("본문");
        assertThat(d.get("approved").asBoolean()).isFalse();
    }

    @Test
    void finish_sends_done_and_clears_registry() {
        when(turnRepo.findBySessionIdOrderBySeqAsc(99L)).thenReturn(List.of());
        var svc = new InterviewStreamService(turnRepo, json);
        svc.subscribe(99L);
        svc.finish(99L);
        // finish 후 두 번째 push는 구독자 없음 — 예외 없이 no-op
        svc.pushStatus(99L, InterviewStatus.REGISTERED);
        assertThat(svc.subscriberCount(99L)).isZero();
    }

    /**
     * 와이어 계약 잠금: activity 페이로드 = {events:[{seq,type,label,detail,content}]} JSON.
     * 프론트(useInterviewStream.onActivity)가 data.events 배열을 순회한다.
     */
    @Test
    void activity_batch_serializes_to_frontend_json_contract() throws Exception {
        var batch = new WorkerActivityRequest(List.of(
                new ActivityEvent(1, "tool", "Read", "src/pages/login.vue", null),
                new ActivityEvent(2, "text", null, null, "이제 인증 흐름을")));
        JsonNode n = json.readTree(json.writeValueAsString(batch));
        assertThat(n.get("events")).hasSize(2);
        assertThat(n.get("events").get(0).get("seq").asLong()).isEqualTo(1);
        assertThat(n.get("events").get(0).get("type").asText()).isEqualTo("tool");
        assertThat(n.get("events").get(0).get("label").asText()).isEqualTo("Read");
        assertThat(n.get("events").get(0).get("detail").asText()).isEqualTo("src/pages/login.vue");
        assertThat(n.get("events").get(1).get("content").asText()).isEqualTo("이제 인증 흐름을");
    }

    /** transient 계약: 구독자 유무와 무관하게 예외 없이 동작, DB 접근 없음(turnRepo 미호출). */
    @Test
    void pushActivity_is_safe_with_and_without_subscribers() {
        when(turnRepo.findBySessionIdOrderBySeqAsc(77L)).thenReturn(List.of());
        var svc = new InterviewStreamService(turnRepo, json);
        var batch = new WorkerActivityRequest(List.of(new ActivityEvent(1, "thinking", null, null, "음…")));
        svc.pushActivity(77L, batch);   // 구독자 없음 — no-op
        svc.subscribe(77L);
        svc.pushActivity(77L, batch);   // 구독자 1 — 예외 없이 전달
    }

    /**
     * replay 계약(스펙 2026-09-13 §5.3): role=system 턴은 note 이벤트로 나간다 — 예전엔 question으로 나가
     * 프론트 hydrate dedup에만 기대던 버그. design은 design, 나머지 assistant 턴은 question 그대로.
     */
    @Test
    void replay_maps_system_turns_to_note_and_keeps_design_and_question() {
        assertThat(InterviewStreamService.replayEventName(turn(1, "system", "note", "MCP 도구 변경: ctx7")))
                .isEqualTo("note");
        assertThat(InterviewStreamService.replayEventName(turn(2, "assistant", "design", "# 설계")))
                .isEqualTo("design");
        assertThat(InterviewStreamService.replayEventName(turn(3, "assistant", "question", "어떤 인증?")))
                .isEqualTo("question");
    }

    @Test
    void subscribe_replays_system_note_without_error_and_pushNote_reaches_subscribers() {
        when(turnRepo.findBySessionIdOrderBySeqAsc(43L))
                .thenReturn(List.of(turn(1, "assistant", "question", "답"), turn(2, "system", "note", "MCP 도구 변경: 없음(전부 해제)")));
        var svc = new InterviewStreamService(turnRepo, json);
        svc.subscribe(43L);
        svc.pushNote(43L, 3, "MCP 도구 변경: ctx7");   // 구독자 1 — 예외 없이 전달
        assertThat(svc.subscriberCount(43L)).isEqualTo(1);
    }

    /**
     * 와이어 계약 잠금(스펙 2026-11-03 §5): mockup 이벤트 데이터는 InterviewMockupSummary JSON — 화면이 turnSeq·turnKind로 질문·계획 턴에
     * 붙이므로 turnKind 키가 있고, 본문(html)은 SSE로 나가지 않는다. 실제 push가 내보낸 바이트를 본다.
     */
    @Test
    void 목업_이벤트_JSON에_html이_없다() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules(); // createdAt(OffsetDateTime) 직렬화
        when(turnRepo.findBySessionIdOrderBySeqAsc(44L)).thenReturn(List.of());
        var svc = new InterviewStreamService(turnRepo, mapper);
        SseEmitter e = svc.subscribe(44L);

        svc.pushMockup(44L, summary(11L, 3, "list-a", 2, 7, "question"));
        svc.pushMockup(44L, summary(12L, 4, "list-b", 1, null, null));

        List<String> frames = frames(e);
        assertThat(frames).hasSize(2).allSatisfy(f -> assertThat(f).startsWith("event:mockup\ndata:"));
        JsonNode first = mapper.readTree(frames.get(0).substring("event:mockup\ndata:".length()));
        assertThat(first.has("turnKind")).isTrue();
        assertThat(first.get("turnKind").asText()).isEqualTo("question");
        assertThat(first.get("turnSeq").asInt()).isEqualTo(7);
        assertThat(first.get("id").asLong()).isEqualTo(11L);
        assertThat(first.get("n").asInt()).isEqualTo(3);
        assertThat(first.get("name").asText()).isEqualTo("list-a");
        assertThat(first.get("version").asInt()).isEqualTo(2);
        assertThat(first.get("title").asText()).isEqualTo("A안");
        assertThat(first.get("description").asText()).isEqualTo("설명");
        assertThat(first.get("createdAt").asText()).isNotBlank();
        assertThat(first.has("html")).isFalse();
        // 안 묶인 목업은 turnSeq·turnKind가 null로 나가되 키는 있다
        JsonNode unbound = mapper.readTree(frames.get(1).substring("event:mockup\ndata:".length()));
        assertThat(unbound.has("turnKind")).isTrue();
        assertThat(unbound.get("turnKind").isNull()).isTrue();
        assertThat(unbound.get("turnSeq").isNull()).isTrue();
        assertThat(unbound.has("html")).isFalse();
    }

    /**
     * replay 계약(스펙 2026-11-03 §5): 턴 replay 뒤에 그 세션의 목업(버린 것 제외)을 mockup으로 잇는다 — 화면이 목업을 붙일 턴이 먼저 도착해 있어야 한다.
     * 목업 저장소를 주입하지 않으면(기존 시험의 2인자 생성) 예전과 같은 이벤트열이다.
     */
    @Test
    void replay는_턴_뒤에_목업() throws Exception {
        when(turnRepo.findBySessionIdOrderBySeqAsc(5L)).thenReturn(List.of(
                turn(1, "assistant", "question", "어떤 화면?"),
                turn(2, "user", "answer", "A안"),               // 사용자 답변은 replay하지 않는다
                turn(3, "assistant", "design", "# 설계")));
        InterviewMockupRepository mockupRepo = mock(InterviewMockupRepository.class);
        when(mockupRepo.findSummaries(5L)).thenReturn(List.of(
                summary(11L, 1, "list-a", 1, 1, "question"),
                summary(12L, 2, "list-b", 1, 3, "design")));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        var withMockups = new InterviewStreamService(turnRepo, mapper);
        withMockups.setInterviewMockupRepository(mockupRepo);
        List<InterviewStreamService.ReplayEvent> events = withMockups.replayEvents(5L);
        assertThat(events).extracting(InterviewStreamService.ReplayEvent::name)
                .containsExactly("question", "design", "mockup", "mockup");
        JsonNode m1 = mapper.readTree(events.get(2).data());
        assertThat(m1.get("id").asLong()).isEqualTo(11L);
        assertThat(m1.get("turnKind").asText()).isEqualTo("question");
        assertThat(mapper.readTree(events.get(3).data()).get("turnKind").asText()).isEqualTo("design");
        assertThat(events.get(2).data()).doesNotContain("\"html\"");

        // 저장소가 없으면 목업 없이 턴만
        var without = new InterviewStreamService(turnRepo, mapper);
        assertThat(without.replayEvents(5L)).extracting(InterviewStreamService.ReplayEvent::name)
                .containsExactly("question", "design");

        // 목업이 없는 세션은 저장소가 있어도 턴만 — 이벤트열이 예전과 같다
        when(mockupRepo.findSummaries(6L)).thenReturn(List.of());
        when(turnRepo.findBySessionIdOrderBySeqAsc(6L)).thenReturn(List.of(turn(1, "assistant", "question", "Q")));
        assertThat(withMockups.replayEvents(6L)).extracting(InterviewStreamService.ReplayEvent::name)
                .containsExactly("question");
    }

    /** subscribe가 접속 시 ping 뒤에 replay를 같은 순서로 보낸다 — 턴 → 목업. */
    @Test
    void subscribe가_목업_replay를_보낸다() {
        when(turnRepo.findBySessionIdOrderBySeqAsc(45L)).thenReturn(List.of(turn(1, "assistant", "question", "Q")));
        InterviewMockupRepository mockupRepo = mock(InterviewMockupRepository.class);
        when(mockupRepo.findSummaries(45L)).thenReturn(List.of(summary(11L, 1, "list-a", 1, 1, "question")));
        var svc = new InterviewStreamService(turnRepo, new ObjectMapper().findAndRegisterModules());
        svc.setInterviewMockupRepository(mockupRepo);

        List<String> frames = frames(svc.subscribe(45L));

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).startsWith("event:question\n");
        assertThat(frames.get(1)).startsWith("event:mockup\n");
    }

    private static InterviewMockupSummary summary(Long id, int n, String name, int version, Integer turnSeq, String turnKind) {
        return new InterviewMockupSummary(id, n, name, version, "A안", "설명", turnSeq, turnKind, OffsetDateTime.now());
    }

    /**
     * 아직 요청에 붙지 않은 SseEmitter가 쌓아 둔 전송(earlySendAttempts)에서 이벤트 프레임("event:이름\ndata:본문")만 꺼낸다 — ping 주석은 뺀다.
     * SseEmitter는 핸들러가 붙기 전까지 보낸 것을 이 집합에 보관한다(Spring 6.2).
     */
    private static List<String> frames(SseEmitter emitter) {
        Set<?> early = (Set<?>) ReflectionTestUtils.getField(emitter, ResponseBodyEmitter.class, "earlySendAttempts");
        StringBuilder all = new StringBuilder();
        for (Object o : early) all.append(((ResponseBodyEmitter.DataWithMediaType) o).getData());
        return java.util.Arrays.stream(all.toString().split("\n\n"))
                .filter(f -> f.startsWith("event:"))
                .toList();
    }

    private static InterviewTurn turn(int seq, String role, String kind, String content) {
        return InterviewTurn.of(1L, seq, role, kind, content, null); // Phase 1 팩토리 시그니처
    }
}
