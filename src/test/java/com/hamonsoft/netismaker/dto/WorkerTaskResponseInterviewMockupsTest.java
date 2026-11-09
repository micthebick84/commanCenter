package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 구현·디자인 claim의 인터뷰 목업 파일과 제외 번호 (스펙 2026-11-03 §7). */
class WorkerTaskResponseInterviewMockupsTest {

    private final ObjectMapper lenient = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Task t = task();

    private static Task task() {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        return t;
    }

    private static WorkerTaskResponse.InterviewMockupFile file(int n) {
        return new WorkerTaskResponse.InterviewMockupFile(n, "list-a", 2, "A안", "<html>본문 " + n + "</html>");
    }

    @Test
    void 목업없는_작업은_JSON에_빈배열() throws Exception {
        String j = lenient.writeValueAsString(WorkerTaskResponse.forImplementation(t, null, null));

        assertThat(j).contains("\"interviewMockups\":[]").contains("\"excludedMockupNumbers\":[]");
        assertThat(WorkerTaskResponse.forDeploy(t).interviewMockups()).isEmpty();
        assertThat(WorkerTaskResponse.forDesign(t, null, null, null, null).excludedMockupNumbers()).isEmpty();
    }

    @Test
    void withInterviewMockups는_두_칸을_함께_바꾼_사본() {
        WorkerTaskResponse base = WorkerTaskResponse.forImplementation(t, null, null);

        WorkerTaskResponse r = base.withInterviewMockups(List.of(file(3), file(5)), List.of(2));

        assertThat(r.interviewMockups()).extracting(WorkerTaskResponse.InterviewMockupFile::n).containsExactly(3, 5);
        assertThat(r.excludedMockupNumbers()).containsExactly(2);
        assertThat(r.id()).isEqualTo(7L);
        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(base.interviewMockups()).isEmpty();   // 원본은 그대로
    }

    @Test
    void withDbMcp_사본이_목업과_제외번호를_유지() {
        WorkerTaskResponse r = WorkerTaskResponse.forImplementation(t, null, null)
                .withInterviewMockups(List.of(file(3)), List.of(2))
                .withDbMcp(new DbMcpGrant(null, List.of(), List.of()));

        assertThat(r.interviewMockups()).containsExactly(file(3));
        assertThat(r.excludedMockupNumbers()).containsExactly(2);
    }

    @Test
    void 호환_생성자는_빈_목록이고_null도_빈_목록() {
        WorkerTaskResponse r = new WorkerTaskResponse(7L, "acme/widgets", "main", "T", "d",
                WorkerTaskResponse.Kind.IMPLEMENTATION, List.of(), "", "[]", null, null, List.of(), null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(), null);   // 32인자
        assertThat(r.interviewMockups()).isNotNull().isEmpty();
        assertThat(r.excludedMockupNumbers()).isNotNull().isEmpty();

        assertThat(r.withInterviewMockups(null, null).interviewMockups()).isEmpty();
    }

    @Test
    void 옛_페이로드는_빈목록_InterviewMockupFile_toString에_html없음() throws Exception {
        WorkerTaskResponse old = lenient.readValue(
                "{\"id\":7,\"githubRepo\":\"acme/widgets\",\"githubBranch\":\"main\",\"kind\":\"IMPLEMENTATION\"}",
                WorkerTaskResponse.class);
        assertThat(old.interviewMockups()).isNotNull().isEmpty();
        assertThat(old.excludedMockupNumbers()).isNotNull().isEmpty();

        WorkerTaskResponse round = lenient.readValue(lenient.writeValueAsString(
                WorkerTaskResponse.forImplementation(t, null, null).withInterviewMockups(List.of(file(3)), List.of(2))),
                WorkerTaskResponse.class);
        assertThat(round.interviewMockups()).containsExactly(file(3));
        assertThat(round.excludedMockupNumbers()).containsExactly(2);

        String s = file(3).toString();
        assertThat(s).doesNotContain("본문").doesNotContain("<html>").contains("n=3").contains("htmlLength=");
        assertThat(WorkerTaskResponse.forImplementation(t, null, null)
                .withInterviewMockups(List.of(file(3)), List.of()).toString()).doesNotContain("본문");
    }
}
