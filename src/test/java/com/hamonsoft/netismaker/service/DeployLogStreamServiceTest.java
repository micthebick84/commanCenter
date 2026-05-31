package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.TaskDeployLogChunk;
import com.hamonsoft.netismaker.repository.TaskDeployLogChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeployLogStreamServiceTest {

    private TaskDeployLogChunkRepository repo;
    private DeployLogStreamService svc;

    @BeforeEach
    void setUp() {
        repo = mock(TaskDeployLogChunkRepository.class);
        svc = new DeployLogStreamService(repo);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void ingest_persists_chunk() {
        svc.ingestChunk(7L, 0, "building...");
        verify(repo).save(any(TaskDeployLogChunk.class));
    }

    @Test
    void consolidate_joins_chunks_in_order() {
        when(repo.findByTaskIdOrderBySeqAsc(7L)).thenReturn(List.of(
                TaskDeployLogChunk.of(7L, 0, "a\n"),
                TaskDeployLogChunk.of(7L, 1, "b\n")));
        assertThat(svc.consolidate(7L)).contains("a\nb\n");
    }

    @Test
    void consolidate_empty_when_no_chunks() {
        when(repo.findByTaskIdOrderBySeqAsc(7L)).thenReturn(List.of());
        assertThat(svc.consolidate(7L)).isEmpty();
    }

    @Test
    void finish_deletes_chunks() {
        svc.finish(7L);
        verify(repo).deleteByTaskId(7L);
    }
}
