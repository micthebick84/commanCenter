package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 구현 세션이 worktree 루트에 남긴 배포 환경변수 목록(.netis-deploy-env.json)을 회수한다 (스펙 2026-10-07 §4.2).
 *
 * 계약 (WorkerMainLoop.DEPLOY_ENV_INSTRUCTION과 동기):
 *   {"vars":[{"key","description","secret","required"}]} — 최상위 배열도 받는다. 모르는 필드(value 등)는 버린다.
 * 파일은 커밋(git add -A) 전에 지운다 — 파싱 성공 여부와 무관. 어떤 실패도 구현을 막지 않는다(빈 목록 + 경고).
 * 링크는 따라가지 않는다 — Claude가 권한 우회로 worktree 밖 파일을 가리키게 만들어도 읽지 않고 링크만 지운다.
 */
@Slf4j
final class DeployEnvManifest {

    static final String FILE_NAME = ".netis-deploy-env.json";
    private static final long MAX_BYTES = 256 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeployEnvManifest() {}

    /** 목록을 읽고 파일을 지운다. 파일이 없거나 형식이 틀리면 빈 목록. 예외를 던지지 않는다. */
    static List<EnvTemplateItem> harvest(Path worktreeRoot) {
        Path file = worktreeRoot.resolve(FILE_NAME);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("배포 env 목록이 일반 파일이 아님(링크 등) — 읽지 않음: {}", file);
                return List.of();
            }
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                log.warn("배포 env 목록이 너무 큼({} bytes) — 무시: {}", size, file);
                return List.of();
            }
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            log.warn("배포 env 목록 읽기 실패 — 템플릿 없이 진행: {} ({})", file, e.toString());
            return List.of();
        } finally {
            delete(file);
        }
    }

    private static List<EnvTemplateItem> parse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode vars = root == null ? null : root.isArray() ? root : root.get("vars");
        if (vars == null || !vars.isArray()) {
            log.warn("배포 env 목록 형식이 다름(vars 배열 없음) — 템플릿 없이 진행");
            return List.of();
        }
        List<EnvTemplateItem> raw = new ArrayList<>();
        for (JsonNode n : vars) {
            if (!n.isObject()) continue;
            raw.add(new EnvTemplateItem(n.path("key").asText(""), n.path("description").asText(""),
                    n.path("secret").asBoolean(false), n.path("required").asBoolean(false)));
        }
        return EnvTemplateItem.sanitize(raw);
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);   // 링크면 링크 자체만 지운다
        } catch (IOException e) {
            log.warn("배포 env 목록 삭제 실패 — 커밋에 섞일 수 있음(값은 없음): {} ({})", file, e.toString());
        }
    }
}
