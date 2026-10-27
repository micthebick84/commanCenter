package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.entity.RefRepo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 참조 repo 변경의 짝 맞춤·검증·문구 (1단계 스펙 2026-10-22 §4.2, 2단계 스펙 2026-10-24 §4.2·§4.3).
 * 질문 대화 중 변경({@code QuestionService.applyRefChange})·작업 승인({@code TaskService.startInterview})·
 * 작업 인터뷰 답변이 함께 쓴다. 노트·이력을 남기는 자리는 호출하는 쪽이 각자 정한다.
 */
public final class RefRepoChanges {

    /** 대화 중 변경 시스템 메모의 머리 (1단계 문구 그대로). */
    public static final String NOTE_PREFIX = "참조 repo 변경: ";
    /** 결과가 비었을 때의 시스템 메모 (1단계 문구 그대로). */
    public static final String CLEARED_NOTE = "참조 repo 변경: 없음(전부 해제)";
    private static final String APPROVAL_PREFIX = "참조 repo 조정: ";

    private RefRepoChanges() {
    }

    /**
     * 바뀐 결과.
     * @param next    바꾼 뒤의 목록 — 입력 순서. 유지되는 원소는 기존 인스턴스 그대로(sha·fetchedAt·error 포함)
     * @param added   새로 들어온 원소(카탈로그로 해석한 스냅샷), 입력 순서
     * @param removed 빠진 기존 원소, 기존 순서
     */
    public record Change(List<RefRepo> next, List<RefRepo> added, List<RefRepo> removed) {
        public Change {
            next = List.copyOf(next);
            added = List.copyOf(added);
            removed = List.copyOf(removed);
        }
    }

    /**
     * 현재 목록과 요청 입력의 차이. inputs == null → 바꿀 것 없음(null).
     * 입력마다 현재 원소 중 {@link RefRepo#matches}인 첫 원소를 찾아 있으면 유지, 없으면 새로 들어오는 짝으로 본다.
     * 새 짝이 없고 현재 원소를 빠짐없이 한 번씩 가리키면 <b>같은 집합</b>(순서 무관)이라 <b>검증 없이</b> null이다 — 사후
     * 비활성·삭제된 카탈로그 항목이 무관한 변경을 막지 않게. 같은 현재 원소를 두 번 가리키는 입력(중복)은 같은 집합이
     * 아니므로 아래 모양 검사에서 400이다.
     * 다르면 결과 집합을 {@link RepoCatalogService#checkRefShape}로 먼저 검사(상한은 결과 기준)한 뒤 <b>새로 들어오는
     * 짝만</b> {@link RepoCatalogService#resolveReferences}로 검증·스냅샷한다(400은 두 메서드가 그대로 던진다). 이미 있던
     * 짝은 카탈로그가 나중에 꺼지거나 지워져도 유지·해제할 수 있다. 같은 카탈로그 id의 브랜치가 바뀌면 "빼고 다시 넣기"라
     * 새 원소가 되어 sha·fetchedAt·error가 비워진다.
     * 입력 브랜치는 strip한 뒤 비교한다 — resolveReferences가 strip해 저장하므로 {@code " main "}이 저장된
     * {@code main}과 같은 짝이어야 같은 집합이고 고정 커밋이 초기화되지 않는다.
     */
    public static Change diff(Long targetCatalogId, List<RefRepo> current, List<RefRepoInput> inputs,
                              RepoCatalogService catalog) {
        if (inputs == null) return null;
        List<RefRepo> now = current == null ? List.of() : current;
        List<RefRepoInput> wanted = inputs.stream().filter(Objects::nonNull)
                .map(in -> new RefRepoInput(in.repoCatalogId(), in.branch() == null ? null : in.branch().strip()))
                .toList();

        // wanted[i]에 대응하는 현재 원소 — 없으면 null(= 새로 들어오는 짝)
        List<RefRepo> slots = new ArrayList<>(wanted.size());
        List<RefRepoInput> added = new ArrayList<>();
        for (RefRepoInput in : wanted) {
            RefRepo hit = now.stream().filter(r -> r.matches(in.repoCatalogId(), in.branch()))
                    .findFirst().orElse(null);
            slots.add(hit);
            if (hit == null) added.add(in);
        }
        Set<RefRepo> kept = slots.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // kept는 Set이라 같은 현재 원소를 두 번 가리키는 중복 입력이 하나로 합쳐진다 — slots와 크기를 비교해야 [A,A]가 "같은 집합"으로 새지 않는다
        if (added.isEmpty() && kept.size() == slots.size() && kept.size() == now.size()) return null;

        RepoCatalogService.checkRefShape(targetCatalogId, wanted);
        List<RefRepo> resolved = catalog.resolveReferences(targetCatalogId, added);
        Iterator<RefRepo> fresh = resolved.iterator();
        List<RefRepo> next = new ArrayList<>(slots.size());
        for (RefRepo hit : slots) next.add(hit != null ? hit : fresh.next());
        List<RefRepo> removed = now.stream().filter(r -> !kept.contains(r)).toList();
        return new Change(next, resolved, removed);
    }

    /** 대화 중 변경의 시스템 메모 — 결과가 비면 {@link #CLEARED_NOTE}, 아니면 추가·해제 목록. */
    public static String systemNote(Change c) {
        return c.next().isEmpty() ? CLEARED_NOTE : NOTE_PREFIX + delta(c);
    }

    /** 승인 때 조정한 이력 행 — 전부 뺀 경우도 뺀 것을 나열한다. */
    public static String approvalHistory(Change c) {
        return APPROVAL_PREFIX + delta(c);
    }

    /** 추가 먼저({@code ＋} U+FF0B), 해제 나중({@code −} U+2212), {@code ", "}로 잇는다 (스펙의 문구 그대로). */
    private static String delta(Change c) {
        return Stream.concat(
                c.added().stream().map(r -> "＋ " + r.label()),
                c.removed().stream().map(r -> "− " + r.label())).collect(Collectors.joining(", "));
    }
}
