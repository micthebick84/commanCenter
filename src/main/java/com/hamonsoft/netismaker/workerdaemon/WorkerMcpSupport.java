package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return assembleArgs(mergedConfigPath, serverNames);
    }

    /**
     * 작업별 추가 MCP가 있을 때: 베이스 합본 + extras를 임시 파일에 머지 → 그 path로 args 생성.
     * extras가 비어있으면 buildClaudeArgs()와 동일.
     *
     * 결과의 tempConfigPath != null이면 호출자가 exec 종료 후 Files.deleteIfExists로 정리해야 함.
     * 충돌 정책: extras 중 베이스와 같은 name이 있으면 extras가 prevails (사용자 의도 우선).
     */
    public TaskClaudeArgs buildClaudeArgsForTask(List<TaskMcpSpec> extras) {
        if (extras == null || extras.isEmpty()) {
            return new TaskClaudeArgs(buildClaudeArgs(), null);
        }
        try {
            // 베이스 mcpServers 복제 (있을 수 있고 없을 수도 있음)
            ObjectNode merged = json.createObjectNode();
            if (mergedConfigPath != null) {
                JsonNode root = json.readTree(mergedConfigPath.toFile());
                JsonNode base = root.path("mcpServers");
                if (base.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> it = base.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        merged.set(e.getKey(), e.getValue());
                    }
                }
            }
            // extras 주입 — name 충돌 시 extras 우선
            for (TaskMcpSpec spec : extras) {
                ObjectNode server = json.createObjectNode();
                server.put("type", spec.transport());
                server.put("url", spec.url());
                merged.set(spec.name(), server);
            }

            ObjectNode wrapper = json.createObjectNode();
            wrapper.set("mcpServers", merged);
            Path tmp = Files.createTempFile("netismaker-mcp-", ".json");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), wrapper);

            Set<String> allNames = new LinkedHashSet<>(serverNames);
            for (TaskMcpSpec spec : extras) allNames.add(spec.name());
            log.info("task별 MCP config 생성: 베이스 {}개 + extras {}개 → {}",
                    serverNames.size(), extras.size(), tmp);
            return new TaskClaudeArgs(assembleArgs(tmp, new ArrayList<>(allNames)), tmp);
        } catch (IOException e) {
            log.error("task별 MCP config 작성 실패 — extras 없이 진행: {}", e.getMessage());
            return new TaskClaudeArgs(buildClaudeArgs(), null);
        }
    }

    private static List<String> assembleArgs(Path configPath, List<String> serverNames) {
        List<String> args = new ArrayList<>();
        args.add("--mcp-config");
        args.add(configPath.toString());
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

    /**
     * 작업별 args + (있다면) 임시 파일 경로. 호출자가 exec 종료 후 임시 파일 삭제 책임.
     */
    public record TaskClaudeArgs(List<String> args, Path tempConfigPath) {}
}
