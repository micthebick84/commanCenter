package com.hamonsoft.netismaker.service.suggest;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.RepoCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuestionSuggestCandidatesTest {

    private DbConnectionService db;
    private RepoCatalogService catalog;
    private QuestionSuggestCandidates candidates;

    @BeforeEach
    void setUp() {
        db = mock(DbConnectionService.class);
        catalog = mock(RepoCatalogService.class);
        candidates = new QuestionSuggestCandidates(db, catalog);
        when(db.isEnabled()).thenReturn(true);
        when(catalog.listEnabled()).thenReturn(List.of());
        when(catalog.isReferenceable(any())).thenReturn(true);
    }

    private static InterviewSession session(List<Long> dbIds, List<RefRepo> refs) {
        InterviewSession s = InterviewSession.createQuestion("acme/x", "main", "t", "q", "user1", null, "m", "e");
        s.setRepoCatalogId(8L);
        s.setDbConnectionIds(dbIds);
        s.setRefRepos(refs);
        return s;
    }

    private static DbConnectionDto.Chip chip(long id) {
        return new DbConnectionDto.Chip(id, "conn" + id, "POSTGRESQL");
    }

    private static RefRepo ref(long catalogId) {
        return new RefRepo(catalogId, "a" + catalogId, "acme/r" + catalogId,
                "https://github.com/acme/r" + catalogId + ".git", "main", null, null, null);
    }

    private static RepoCatalogEntry entry(long id, String branch, String hint) {
        RepoCatalogEntry e = RepoCatalogEntry.create("alias" + id, "https://github.com/acme/r" + id + ".git",
                "github", "acme/r" + id, branch, "내부 설명 " + id, "admin");
        e.setRouteHint(hint);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    void DB_후보는_요청자_기준이다() {
        when(db.selectableChips(8L, "user1")).thenReturn(List.of(chip(3)));
        QuestionSuggestCandidates.Candidates c = candidates.forSession(session(new ArrayList<>(), List.of()));
        verify(db).selectableChips(8L, "user1");
        assertThat(c.db()).containsExactly(chip(3));
        assertThat(c.dbSlotsLeft()).isEqualTo(3);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 이미_붙은_DB_연결은_후보에서_빠진다() {
        when(db.selectableChips(8L, "user1")).thenReturn(List.of(chip(3), chip(4)));
        // JSONB 왕복으로 Integer가 섞여 들어와도 같은 id로 본다
        List attached = new ArrayList();
        attached.add(Integer.valueOf(3));
        QuestionSuggestCandidates.Candidates c = candidates.forSession(session(attached, List.of()));
        assertThat(c.db()).containsExactly(chip(4));
        assertThat(c.dbSlotsLeft()).isEqualTo(2);
    }

    @Test
    void DB가_3개_붙었으면_후보가_비고_selectableChips를_부르지_않는다() {
        QuestionSuggestCandidates.Candidates c = candidates.forSession(session(List.of(1L, 2L, 3L), List.of()));
        assertThat(c.db()).isEmpty();
        assertThat(c.dbSlotsLeft()).isZero();
        verify(db, never()).selectableChips(any(), any());
    }

    @Test
    void DB_기능이_꺼졌으면_후보가_빈다() {
        when(db.isEnabled()).thenReturn(false);
        QuestionSuggestCandidates.Candidates c = candidates.forSession(session(new ArrayList<>(), List.of()));
        assertThat(c.db()).isEmpty();
        verify(db, never()).selectableChips(any(), any());
    }

    @Test
    void 참조_후보에서_대상과_붙은_레포와_참조할_수_없는_레포가_빠진다() {
        RepoCatalogEntry bad = entry(10, null, null);
        when(catalog.listEnabled()).thenReturn(List.of(entry(8, "main", null), entry(9, "main", null), bad,
                entry(11, "main", null)));
        when(catalog.isReferenceable(bad)).thenReturn(false);
        QuestionSuggestCandidates.Candidates c = candidates.forSession(session(new ArrayList<>(), List.of(ref(9))));
        assertThat(c.refs()).extracting(QuestionSuggestCandidates.RefCandidate::catalogId).containsExactly(11L);
        assertThat(c.refSlotsLeft()).isEqualTo(2);
    }

    @Test
    void 참조_후보의_hint는_route_hint이고_설명은_싣지_않는다() {
        when(catalog.listEnabled()).thenReturn(List.of(entry(11, "main", "시험 사업소, 7.0"), entry(12, "dev", " ")));
        QuestionSuggestCandidates.Candidates c = candidates.forSession(session(new ArrayList<>(), List.of()));
        assertThat(c.refs().get(0).hint()).isEqualTo("시험 사업소, 7.0");
        assertThat(c.refs().get(0).defaultBranch()).isEqualTo("main");
        assertThat(c.refs().get(0).alias()).isEqualTo("alias11");
        assertThat(c.refs().get(1).hint()).isNull();
        assertThat(Arrays.stream(QuestionSuggestCandidates.RefCandidate.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("catalogId", "alias", "hint", "defaultBranch");
    }

    @Test
    void 참조가_3개_붙었으면_참조_후보가_빈다() {
        when(catalog.listEnabled()).thenReturn(List.of(entry(11, "main", null)));
        QuestionSuggestCandidates.Candidates c =
                candidates.forSession(session(new ArrayList<>(), List.of(ref(1), ref(2), ref(3))));
        assertThat(c.refs()).isEmpty();
        assertThat(c.refSlotsLeft()).isZero();
        verify(catalog, never()).listEnabled();
    }

    @Test
    void 둘_다_비면_isEmpty다() {
        assertThat(candidates.forSession(session(new ArrayList<>(), List.of())).isEmpty()).isTrue();
        when(db.selectableChips(8L, "user1")).thenReturn(List.of(chip(3)));
        assertThat(candidates.forSession(session(new ArrayList<>(), List.of())).isEmpty()).isFalse();
    }
}
