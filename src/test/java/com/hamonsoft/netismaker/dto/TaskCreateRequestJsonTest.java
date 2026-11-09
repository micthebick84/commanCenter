package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 등록 바디 — 레코드에 생성자가 여럿(정규 7개 + 호환 6개·4개)이어도 Jackson은 정규 생성자로 읽는다
 * (스펙 2026-10-24 §4.1). JSON 등록과 multipart `meta` 파트가 모두 이 경로다. 통합 테스트는 Docker가 없으면
 * 건너뛰므로 여기서 직접 확인한다. 매퍼는 스프링 부트처럼 모르는 속성을 무시한다(WorkerResultRequestJsonTest와 같은 설정).
 */
class TaskCreateRequestJsonTest {

    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void a_body_with_refs_and_a_source_question_binds_every_component() throws Exception {
        TaskCreateRequest req = json.readValue("""
                {"repoCatalogId":7,"githubBranch":"main","title":"제목","description":"설명",
                 "refRepos":[{"repoCatalogId":11,"branch":"release/6.5"},{"repoCatalogId":12}],
                 "sourceQuestionId":9}
                """, TaskCreateRequest.class);

        assertThat(req).isEqualTo(new TaskCreateRequest(7L, "main", "제목", "설명",
                List.of(new RefRepoInput(11L, "release/6.5"), new RefRepoInput(12L, null)), 9L));
    }

    @Test
    void a_four_field_body_reads_refs_and_source_as_null() throws Exception {
        TaskCreateRequest req = json.readValue(
                "{\"repoCatalogId\":7,\"githubBranch\":\"main\",\"title\":\"제목\",\"description\":\"설명\"}",
                TaskCreateRequest.class);

        assertThat(req).isEqualTo(new TaskCreateRequest(7L, "main", "제목", "설명"));
        assertThat(req.refRepos()).isNull();
        assertThat(req.sourceQuestionId()).isNull();
    }

    @Test
    void fields_the_registration_no_longer_takes_are_ignored() throws Exception {
        // 구 클라이언트가 보내던 모델/effort/MCP는 승인 때 정한다 — 등록 바디에 와도 무시
        TaskCreateRequest req = json.readValue(
                "{\"repoCatalogId\":7,\"title\":\"제목\",\"description\":\"설명\",\"model\":\"claude-opus-5-5\","
                        + "\"mcpCatalogIds\":[1]}",
                TaskCreateRequest.class);

        assertThat(req).isEqualTo(new TaskCreateRequest(7L, null, "제목", "설명"));
    }

    @Test
    void meta_JSON의_taskType_바인딩() throws Exception {
        // multipart meta 파트도 같은 레코드다 — 키가 있으면 문자열 그대로(검증은 서비스가 한다), 없으면 null
        TaskCreateRequest typed = json.readValue("""
                {"repoCatalogId":7,"githubBranch":"main","title":"제목","description":"설명",
                 "refRepos":[{"repoCatalogId":11}],"sourceQuestionId":9,"taskType":"BUG_FIX"}
                """, TaskCreateRequest.class);
        assertThat(typed.taskType()).isEqualTo("BUG_FIX");
        assertThat(typed).isEqualTo(new TaskCreateRequest(7L, "main", "제목", "설명",
                List.of(new RefRepoInput(11L, null)), 9L, "BUG_FIX"));

        // 모르는 값도 바인딩은 통과한다 — 400은 저장 전 서비스 검증에서
        assertThat(json.readValue(
                "{\"repoCatalogId\":7,\"title\":\"제목\",\"description\":\"설명\",\"taskType\":\"bug_fix\"}",
                TaskCreateRequest.class).taskType()).isEqualTo("bug_fix");

        // 키를 생략한 바디(옛 화면)는 null, 명시적 null도 null
        TaskCreateRequest omitted = json.readValue(
                "{\"repoCatalogId\":7,\"title\":\"제목\",\"description\":\"설명\"}", TaskCreateRequest.class);
        assertThat(omitted.taskType()).isNull();
        assertThat(json.readValue(
                "{\"repoCatalogId\":7,\"title\":\"제목\",\"description\":\"설명\",\"taskType\":null}",
                TaskCreateRequest.class).taskType()).isNull();
    }

    @Test
    void 호환_생성자는_타입_없음() {
        // 4인자·6인자(참조·출처) 호환 생성자 — 기존 호출부·시험은 그대로 타입 없음
        assertThat(new TaskCreateRequest(7L, "main", "제목", "설명").taskType()).isNull();
        assertThat(new TaskCreateRequest(7L, "main", "제목", "설명", null, 9L).taskType()).isNull();
        assertThat(new TaskCreateRequest(7L, "main", "제목", "설명", null, 9L))
                .isEqualTo(new TaskCreateRequest(7L, "main", "제목", "설명", null, 9L, null));
    }
}
