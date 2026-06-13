package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.DesignEvent;
import com.hamonsoft.netismaker.dto.QuestionEvent;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

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

    private static InterviewTurn turn(int seq, String role, String kind, String content) {
        return InterviewTurn.of(1L, seq, role, kind, content, null); // Phase 1 팩토리 시그니처
    }
}
