package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.service.RefRepoChanges.Change;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 참조 repo 변경 짝 맞춤·검증·메모 (스펙 2026-10-24 §4.2, 1단계 2026-10-22 §4.2) — 질문 ask·작업 승인·인터뷰 답변이 함께 쓴다.
 * 대상 카탈로그는 1. 별칭은 선택 당시 스냅샷이라 같은 카탈로그 id라도 원소마다 다를 수 있다(문구 검증용 값).
 */
class RefRepoChangesTest {

    private static final String SHA_A = "a".repeat(40);

    private final RepoCatalogService catalog = mock(RepoCatalogService.class);

    private static RefRepo ref(long catalogId, String alias, String branch) {
        return new RefRepo(catalogId, alias, "grp/app-" + catalogId,
                "https://gitlab.example/grp/app-" + catalogId + ".git", branch, null, null, null);
    }

    private static RefRepoInput in(long catalogId, String branch) {
        return new RefRepoInput(catalogId, branch);
    }

    private final RefRepo a11 = ref(11L, "옛앱", "main");
    private final RefRepo a11Pinned = a11.pinned(SHA_A, "2026-10-24T10:00:00+09:00").withError("네트워크 오류");
    private final RefRepo b12 = ref(12L, "공통 UI", "dev");
    private final RefRepo c12 = ref(12L, "배치 서버", "master");

    private static void assertBadRequest(Throwable t, String message) {
        assertThat(t).isInstanceOfSatisfying(TaskException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void null_inputs_or_the_same_set_in_any_order_is_no_change_without_resolving() {
        assertThat(RefRepoChanges.diff(1L, List.of(a11, b12), null, catalog)).isNull();
        assertThat(RefRepoChanges.diff(1L, List.of(a11, b12), List.of(in(12, "dev"), in(11, " main ")), catalog)).isNull();
        verifyNoInteractions(catalog);
    }

    @Test
    void empty_inputs_with_nothing_selected_is_no_change() {
        assertThat(RefRepoChanges.diff(1L, List.of(), List.of(), catalog)).isNull();
        assertThat(RefRepoChanges.diff(1L, null, List.of(), catalog)).isNull();
        verifyNoInteractions(catalog);
    }

    @Test
    void retained_refs_keep_their_pin_and_only_added_ones_are_resolved() {
        when(catalog.resolveReferences(1L, List.of(new RefRepoInput(12L, "master")))).thenReturn(List.of(c12));

        Change c = RefRepoChanges.diff(1L, List.of(a11Pinned), List.of(in(11, "main"), in(12, "master")), catalog);

        assertThat(c.next()).containsExactly(a11Pinned, c12);
        assertThat(c.next().get(0)).isSameAs(a11Pinned);   // 기존 원소 그대로 — sha·fetchedAt·error 보존
        assertThat(c.added()).containsExactly(c12);
        assertThat(c.removed()).isEmpty();
        assertThat(RefRepoChanges.systemNote(c)).isEqualTo("참조 repo 변경: ＋ 배치 서버(master)");
    }

    @Test
    void branch_change_is_remove_plus_add() {
        RefRepo a11Dev = ref(11L, "옛앱", "dev");
        when(catalog.resolveReferences(1L, List.of(in(11, "dev")))).thenReturn(List.of(a11Dev));

        Change c = RefRepoChanges.diff(1L, List.of(a11Pinned), List.of(in(11, "dev")), catalog);

        assertThat(c.next()).containsExactly(a11Dev);
        assertThat(c.next().get(0).sha()).isNull();   // 새 원소 — 고정 커밋·오류가 비워진다
        assertThat(c.added()).containsExactly(a11Dev);
        assertThat(c.removed()).containsExactly(a11Pinned);
        assertThat(RefRepoChanges.systemNote(c)).isEqualTo("참조 repo 변경: ＋ 옛앱(dev), − 옛앱(main)");
    }

    @Test
    void empty_list_clears_everything() {
        Change c = RefRepoChanges.diff(1L, List.of(a11, b12), List.of(), catalog);

        assertThat(c.next()).isEmpty();
        assertThat(c.added()).isEmpty();
        assertThat(c.removed()).containsExactly(a11, b12);
        assertThat(RefRepoChanges.systemNote(c)).isEqualTo("참조 repo 변경: 없음(전부 해제)");
        assertThat(RefRepoChanges.approvalHistory(c)).isEqualTo("참조 repo 조정: − 옛앱(main), − 공통 UI(dev)");
        verify(catalog).resolveReferences(1L, List.of());   // 새로 들어온 것이 없다 — 검증할 항목 없음
    }

    @Test
    void approval_history_lists_additions_then_removals() {
        RefRepo a = ref(11L, "A", "main");
        RefRepo b = ref(12L, "B", "dev");
        RefRepo cc = ref(13L, "C", "master");
        when(catalog.resolveReferences(1L, List.of(in(13, "master")))).thenReturn(List.of(cc));

        Change c = RefRepoChanges.diff(1L, List.of(a, b), List.of(in(11, "main"), in(13, "master")), catalog);

        assertThat(c.next()).containsExactly(a, cc);
        assertThat(RefRepoChanges.approvalHistory(c)).isEqualTo("참조 repo 조정: ＋ C(master), − B(dev)");
        assertThat(RefRepoChanges.systemNote(c)).isEqualTo("참조 repo 변경: ＋ C(master), − B(dev)");
    }

    @Test
    void result_follows_the_input_order_mixing_retained_and_new_refs() {
        RefRepo n12 = ref(12L, "배치 서버", "main");
        RefRepo n13 = ref(13L, "분석 도구", "main");
        when(catalog.resolveReferences(1L, List.of(in(12, "main"), in(13, "main")))).thenReturn(List.of(n12, n13));

        Change c = RefRepoChanges.diff(1L, List.of(a11Pinned),
                List.of(in(12, "main"), in(11, "main"), in(13, "main")), catalog);

        assertThat(c.next()).containsExactly(n12, a11Pinned, n13);
        assertThat(RefRepoChanges.systemNote(c)).isEqualTo("참조 repo 변경: ＋ 배치 서버(main), ＋ 분석 도구(main)");
    }

    @Test
    void input_branches_are_stripped_and_a_blank_branch_matches_by_catalog_id() {
        // " main " == main, null·blank 브랜치는 RefRepo.matches가 카탈로그 id만 비교한다 → 모두 같은 집합
        assertThat(RefRepoChanges.diff(1L, List.of(a11Pinned), List.of(in(11, " main ")), catalog)).isNull();
        assertThat(RefRepoChanges.diff(1L, List.of(a11Pinned), List.of(in(11, null)), catalog)).isNull();
        assertThat(RefRepoChanges.diff(1L, List.of(a11Pinned), List.of(in(11, "  ")), catalog)).isNull();
        verifyNoInteractions(catalog);

        when(catalog.resolveReferences(eq(1L), any())).thenReturn(List.of(c12));
        RefRepoChanges.diff(1L, List.of(), List.of(in(12, "  master\t")), catalog);
        verify(catalog).resolveReferences(1L, List.of(in(12, "master")));
    }

    @Test
    void shape_is_checked_on_the_result_set() {
        RefRepo r13 = ref(13L, "공통 UI", "main");
        // 상한은 결과 집합 기준 — 3개에서 하나를 바꾸는 것은 되고, 4개는 400
        assertThatThrownBy(() -> RefRepoChanges.diff(1L, List.of(a11, b12, r13),
                List.of(in(11, "main"), in(12, "dev"), in(13, "main"), in(14, "main")), catalog))
                .satisfies(t -> assertBadRequest(t, "참조 repo는 최대 3개까지 고를 수 있습니다"));
        // 같은 현재 원소를 두 번 가리키는 입력은 "같은 집합"이 아니라 중복
        assertThatThrownBy(() -> RefRepoChanges.diff(1L, List.of(a11),
                List.of(in(11, "main"), in(11, "main")), catalog))
                .satisfies(t -> assertBadRequest(t, "같은 레포를 참조로 두 번 고를 수 없습니다"));
        assertThatThrownBy(() -> RefRepoChanges.diff(1L, List.of(a11),
                List.of(in(11, "main"), in(1, "main")), catalog))
                .satisfies(t -> assertBadRequest(t, "대상 레포는 참조로 고를 수 없습니다"));
        verifyNoInteractions(catalog);

        RefRepo r14 = ref(14L, "분석 도구", "main");
        when(catalog.resolveReferences(1L, List.of(in(14, "main")))).thenReturn(List.of(r14));
        Change swap = RefRepoChanges.diff(1L, List.of(a11, b12, r13),
                List.of(in(11, "main"), in(12, "dev"), in(14, "main")), catalog);
        assertThat(swap.next()).containsExactly(a11, b12, r14);
        assertThat(RefRepoChanges.systemNote(swap)).isEqualTo("참조 repo 변경: ＋ 분석 도구(main), − 공통 UI(main)");
    }

    @Test
    void a_catalog_rejection_of_an_added_ref_propagates() {
        when(catalog.resolveReferences(eq(1L), any()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "비활성화된 레포 카탈로그 항목: 배치 서버"));

        assertThatThrownBy(() -> RefRepoChanges.diff(1L, List.of(a11Pinned),
                List.of(in(11, "main"), in(12, "master")), catalog))
                .satisfies(t -> assertBadRequest(t, "비활성화된 레포 카탈로그 항목: 배치 서버"));
    }

    @Test
    void constants_are_the_stage_one_note_strings() {
        assertThat(RefRepoChanges.NOTE_PREFIX).isEqualTo("참조 repo 변경: ");
        assertThat(RefRepoChanges.CLEARED_NOTE).isEqualTo("참조 repo 변경: 없음(전부 해제)");
    }
}
