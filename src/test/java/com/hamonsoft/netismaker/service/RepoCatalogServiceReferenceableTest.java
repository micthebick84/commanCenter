package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoCatalogServiceReferenceableTest {

    private RepoCatalogRepository repo;
    private RepoCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(RepoCatalogRepository.class);
        service = new RepoCatalogService(repo, mock(GitRefService.class), "https://gitlab.example.test");
    }

    private RepoCatalogEntry entry(long id, String gitUrl, String branch, boolean enabled) {
        RepoCatalogEntry e = RepoCatalogEntry.create("alias" + id, gitUrl, "github", "acme/x", branch, null, "admin");
        e.setEnabled(enabled);
        ReflectionTestUtils.setField(e, "id", id);
        when(repo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void 켜져_있고_기본_브랜치가_있고_지원하는_호스트면_true() {
        assertThat(service.isReferenceable(entry(1, "https://github.com/acme/x.git", "main", true))).isTrue();
    }

    @Test
    void 기본_브랜치가_비면_false() {
        assertThat(service.isReferenceable(entry(1, "https://github.com/acme/x.git", null, true))).isFalse();
        assertThat(service.isReferenceable(entry(2, "https://github.com/acme/x.git", "  ", true))).isFalse();
    }

    @Test
    void 꺼져_있으면_false() {
        assertThat(service.isReferenceable(entry(1, "https://github.com/acme/x.git", "main", false))).isFalse();
    }

    @Test
    void 지원하지_않는_호스트면_false() {
        assertThat(service.isReferenceable(entry(1, "https://other.example.test/acme/x.git", "main", true))).isFalse();
    }

    @Test
    void isReferenceable이_true인_항목은_resolveReferences를_통과한다() {
        RepoCatalogEntry e = entry(1, "https://github.com/acme/x.git", "main", true);
        assertThat(service.isReferenceable(e)).isTrue();
        assertThat(service.resolveReferences(99L, List.of(new RefRepoInput(1L, null)))).hasSize(1);
    }
}
