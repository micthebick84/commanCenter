package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import com.hamonsoft.netismaker.repository.InterviewMockupRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 확정 때 고른 목업 검증·제외 번호·이력 문구 (스펙 2026-11-03 §7). */
class InterviewMockupServiceConfirmTest {

    private static final long SID = 5L;

    InterviewMockupRepository repo;
    InterviewMockupService service;
    /** 세션의 버리지 않은 목업 전체(요약 목록의 원천). */
    List<InterviewMockup> all = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(InterviewMockupRepository.class);
        service = new InterviewMockupService(mock(InterviewSessionRepository.class), repo);
        when(repo.findSummaries(SID)).thenAnswer(i -> all.stream().map(m -> new InterviewMockupSummary(
                m.getId(), m.getN(), m.getName(), m.getVersion(), m.getTitle(), m.getDescription(),
                null, null, null)).toList());
        when(repo.findByIdInAndDiscardedFalseOrderByNAsc(any())).thenAnswer(i -> {
            java.util.Collection<Long> ids = i.getArgument(0);
            return all.stream().filter(m -> ids.contains(m.getId()))
                    .sorted(java.util.Comparator.comparingInt(InterviewMockup::getN)).toList();
        });
    }

    private InterviewMockup mockup(long id, long sessionId, int n, String title, int version) {
        InterviewMockup m = InterviewMockup.create(sessionId, n, "m" + n, version, title, null, "<html></html>");
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    private void seed() {
        all.add(mockup(8, SID, 2, "C안", 1));
        all.add(mockup(10, SID, 3, "A안", 2));
        all.add(mockup(12, SID, 5, "B안", 1));
    }

    @Test
    void 목업없는_세션은_계획에_목업N이_있어도_제외없음() {
        var sel = service.resolveForConfirm(SID, null, "목업 2 기준으로 만든다. 목업 2개를 비교했다.");
        assertThat(sel.idsByN()).isEmpty();
        assertThat(sel.excludedNumbers()).isEmpty();
        assertThat(sel.historyReason()).isNull();
    }

    @Test
    void 전부_해제해도_제외는_계산하고_이력에_남긴다() {
        seed();
        var sel = service.resolveForConfirm(SID, List.of(), "목업 2 기준");
        assertThat(sel.idsByN()).isEmpty();
        assertThat(sel.excludedNumbers()).containsExactly(2);
        assertThat(sel.historyReason()).isEqualTo("구현 목업: 없음 / 제외: 목업 2");
    }

    @Test
    void 계획이_안_가리키고_고르지_않으면_이력_없음() {
        seed();
        var sel = service.resolveForConfirm(SID, null, "# 플랜");
        assertThat(sel.historyReason()).isNull();
        assertThat(sel.idsByN()).isEmpty();
    }

    @Test
    void n순_저장과_이력() {
        seed();
        var sel = service.resolveForConfirm(SID, List.of(12L, 10L), "# 플랜");
        assertThat(sel.idsByN()).containsExactly(10L, 12L);
        assertThat(sel.excludedNumbers()).isEmpty();
        assertThat(sel.historyReason()).isEqualTo("구현 목업: 목업 3(A안 v2), 목업 5(B안 v1)");
    }

    @Test
    void 계획이_가리킨_것을_빼면_제외() {
        seed();
        var sel = service.resolveForConfirm(SID, List.of(10L), "목업 2, 목업 3, 목업 9");
        assertThat(sel.idsByN()).containsExactly(10L);
        assertThat(sel.excludedNumbers()).containsExactly(2);   // 없는 9는 무시
        assertThat(sel.historyReason()).isEqualTo("구현 목업: 목업 3(A안 v2) / 제외: 목업 2");
    }

    @Test
    void 제외가_여럿이면_번호순으로_쉼표() {
        seed();
        var sel = service.resolveForConfirm(SID, List.of(10L), "목업 5, 목업 2");
        assertThat(sel.excludedNumbers()).containsExactly(2, 5);
        assertThat(sel.historyReason()).endsWith(" / 제외: 목업 2, 목업 5");
    }

    @Test
    void 중복_id는_한번만() {
        seed();
        var sel = service.resolveForConfirm(SID, List.of(10L, 10L, 10L), "# 플랜");
        assertThat(sel.idsByN()).containsExactly(10L);
    }

    @Test
    void 남의세션_버린것은_400() {
        seed();
        all.add(mockup(30, 99L, 1, "남의 것", 1));   // 다른 세션 — 조회는 id로 되지만 소속이 다르다
        // 99번 id(없음/버림)와 30번(남의 세션)
        assertThatThrownBy(() -> service.resolveForConfirm(SID, List.of(10L, 30L, 777L), "x"))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("이 인터뷰의 목업이 아닙니다: 30, 777");
                });
    }

    @Test
    void 열한개는_400() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 11; i++) ids.add(i);
        assertThatThrownBy(() -> service.resolveForConfirm(SID, ids, "x"))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("구현에 넘길 목업은 10개까지입니다");
                });
    }

    @Test
    void 계획_번호는_모두_찾는다() {
        assertThat(InterviewMockupService.planMockupNumbers("목업 3, 목업 5")).containsExactly(3, 5);
        assertThat(InterviewMockupService.planMockupNumbers("목업 3, 5")).containsExactly(3);
        assertThat(InterviewMockupService.planMockupNumbers("목업 5 그리고 목업 3 그리고 목업 5")).containsExactly(5, 3);
        assertThat(InterviewMockupService.planMockupNumbers(null)).isEmpty();
        assertThat(InterviewMockupService.planMockupNumbers("목업 99999999999")).isEmpty();
        assertThat(Set.copyOf(InterviewMockupService.planMockupNumbers("목업 1"))).containsExactly(1);
    }
}
