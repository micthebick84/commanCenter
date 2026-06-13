package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        svc.pushQuestion(42L, "다음 질문?");
        svc.pushStatus(42L, InterviewStatus.AWAITING_INPUT);          // 영문 enum name 전달
        svc.pushPlanReady(42L, "# 설계", "# 플랜", "[]");              // JSON 객체 전달
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
