package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스트리밍 배포 로그 청크의 마스킹 경계.
 *
 * WorkerHttpClient는 생성자에서 RestClient를 직접 조립해 HTTP를 가짜로 끼우기 어렵다.
 * 대신 전송 직전 변환을 순수 함수(maskedChunk)로 떼어 그 seam을 검증한다 —
 * postDeployLog는 이 함수의 결과만 body로 넘긴다.
 */
class WorkerHttpClientMaskTest {

    @Test
    void masked_chunk_hides_credentials_and_keeps_seq() {
        var chunk = WorkerHttpClient.maskedChunk(7,
                "fatal: Authentication failed for 'https://oauth2:glpat-X@gitlab.hamon.vip/g/p.git'\n");

        assertThat(chunk.seq()).isEqualTo(7);
        assertThat(chunk.content())
                .contains("https://***@gitlab.hamon.vip")
                .doesNotContain("glpat-X");
    }

    @Test
    void masked_chunk_leaves_ordinary_build_output_alone() {
        var chunk = WorkerHttpClient.maskedChunk(0, "Step 3/9 : RUN npm ci\n");

        assertThat(chunk.content()).isEqualTo("Step 3/9 : RUN npm ci\n");
    }

    @Test
    void masked_chunk_is_null_safe() {
        assertThat(WorkerHttpClient.maskedChunk(1, null).content()).isNull();
    }

    /** 본문 없는 응답은 '진행 중 없음'이 아니라 조회 실패여야 정리 잡이 회차를 건너뛴다(fail-closed). */
    @Test
    void null_active_worktree_body_is_a_lookup_failure() {
        assertThatThrownBy(() -> WorkerHttpClient.requireActiveBody(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void empty_active_worktree_array_is_a_legitimate_nothing_active() {
        assertThat(WorkerHttpClient.requireActiveBody(List.of())).isEmpty();
        var one = List.of(new ActiveWorktreeTaskSummary(3L, TaskStatus.IMPLEMENTING));
        assertThat(WorkerHttpClient.requireActiveBody(one)).isSameAs(one);
    }
}
