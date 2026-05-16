package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 워커의 claude -p 호출에 사용자 글로벌·프로젝트 스코프 MCP를 모두 주입.
 *
 * 동작:
 *   ① ~/.claude.json 읽고 mcpServers(글로벌) + projects[*].mcpServers를 합집합으로 병합
 *   ② 합본 JSON을 worker-mcp.json으로 기록
 *   ③ ClaudeExecAdapter가 --mcp-config + --strict-mcp-config + --allowedTools 로 주입
 *
 * 디렉토리 스코프 우회: 워커가 ~/netis-maker/repos/... 에서 claude를 실행하므로
 * IdeaProjects 같은 프로젝트 스코프 MCP가 자동으로 잡히지 않음. 이 클래스가 그 차이를 메움.
 */
@Component
@Profile("worker")
@Slf4j
public class WorkerMcpSupport {

    private final ObjectMapper json = new ObjectMapper();
    private final WorkerProperties props;
    private Path mergedConfigPath;
    private List<String> serverNames = List.of();

    public WorkerMcpSupport(WorkerProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            buildMergedConfig();
        } catch (Exception e) {
            log.warn("MCP config 빌드 실패 (MCP 없이 진행): {}", e.getMessage());
            mergedConfigPath = null;
            serverNames = List.of();
        }
    }

    private void buildMergedConfig() throws IOException {
        Path src = Paths.get(System.getProperty("user.home"), ".claude.json");
        if (!Files.exists(src)) {
            log.info("~/.claude.json 없음 — MCP 통합 skip");
            return;
        }

        JsonNode root = json.readTree(src.toFile());
        ObjectNode merged = json.createObjectNode();

        copyServers(root.path("mcpServers"), merged);
        JsonNode projects = root.path("projects");
        if (projects.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = projects.fields();
            while (it.hasNext()) {
                copyServers(it.next().getValue().path("mcpServers"), merged);
            }
        }

        if (merged.isEmpty()) {
            log.info("등록된 MCP 없음 — 통합 skip");
            return;
        }

        ObjectNode wrapper = json.createObjectNode();
        wrapper.set("mcpServers", merged);
        Path target = Paths.get(props.reposDir()).resolveSibling("worker-mcp.json");
        Files.createDirectories(target.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), wrapper);

        mergedConfigPath = target;
        List<String> names = new ArrayList<>();
        merged.fieldNames().forEachRemaining(names::add);
        serverNames = List.copyOf(names);
        log.info("MCP 통합: {} 서버 → {}", names, target);
    }

    private static void copyServers(JsonNode servers, ObjectNode merged) {
        if (!servers.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = servers.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            // 동일 이름 충돌 시 마지막 prevails (보통 프로젝트 스코프가 글로벌 override)
            merged.set(e.getKey(), e.getValue());
        }
    }

    /** UI 노출용 — 워커가 사용 가능한 MCP 서버 이름 목록 (예: "local-db", "obsidian-vault"). */
    public List<String> getServerNames() {
        return serverNames;
    }

    /** claude -p 인자에 prepend할 리스트. MCP 없으면 빈 리스트. */
    public List<String> buildClaudeArgs() {
        if (mergedConfigPath == null || serverNames.isEmpty()) return List.of();
        List<String> args = new ArrayList<>();
        args.add("--mcp-config");
        args.add(mergedConfigPath.toString());
        // 합본 외 다른 MCP 소스는 무시 (예측가능성)
        args.add("--strict-mcp-config");
        // 비대화식 모드에서 MCP 도구 호출 자동 허용 (server 단위 와일드카드).
        // mcp__<serverName> 는 해당 서버의 모든 도구 매칭.
        StringBuilder allowed = new StringBuilder();
        for (int i = 0; i < serverNames.size(); i++) {
            if (i > 0) allowed.append(',');
            allowed.append("mcp__").append(serverNames.get(i));
        }
        args.add("--allowedTools");
        args.add(allowed.toString());
        return args;
    }
}
