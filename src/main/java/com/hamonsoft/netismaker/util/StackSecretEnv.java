package com.hamonsoft.netismaker.util;

import java.util.List;
import java.util.Map;

/**
 * 자식 프로세스에 넘기지 않는 스택 비밀 env 이름 — 워커의 claude 자식(ClaudeExecAdapter)과 API의 soffice 자식
 * (ProcessBuilderStarter)이 함께 쓴다. 서비스 자신은 쓰지만(clone/push·PR·API 호출·DNS 등록·DB) 에이전트나 대상 레포의
 * 코드(npm postinstall 등), 신뢰할 수 없는 첨부를 여는 soffice가 볼 이유는 없다. claude 로그인(CLAUDE_*)과 빌드 도구
 * 자체의 토큰은 건드리지 않도록 패턴이 아니라 이 스택이 정의한 이름만 뺀다.
 * 인터뷰 서비스 쪽 같은 목록: netismaker-interview-service/src/sdk/childEnv.ts — 바꿀 때 같이 바꿀 것.
 */
public final class StackSecretEnv {

    public static final List<String> NAMES = List.of(
            "WORKER_API_KEY", "SPRING_DATASOURCE_PASSWORD", "GITLAB_TOKEN", "GITHUB_PAT", "GH_TOKEN", "GITHUB_TOKEN",
            "CLOUDFLARE_API_TOKEN", "NETISMAKER_DB_SECRET_KEY", "NETISMAKER_DB_SECRET_SALT", "MAIL_PASSWORD", "PGPASSWORD");

    private StackSecretEnv() {}

    /** env에서 스택 비밀을 뺀다. 이름은 대소문자 구분 없이 비교한다 — Windows env는 대소문자를 가리지 않는다. */
    public static void strip(Map<String, String> env) {
        env.keySet().removeIf(k -> NAMES.stream().anyMatch(k::equalsIgnoreCase));
    }
}
