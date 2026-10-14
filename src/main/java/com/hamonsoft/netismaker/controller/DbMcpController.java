package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dbmcp.DbMcpTokenService;
import com.hamonsoft.netismaker.dbmcp.DbMcpTools;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 디자인·구현 단계 DB MCP (스펙 2026-10-14 §6.1). MCP Streamable HTTP의 stateless JSON 응답만 — SSE·세션 id 없음.
 * 인증은 Authorization: Bearer <작업 토큰> 하나(워커 API 키 아님). 보안 체인에서는 permitAll(SecurityConfig).
 * ⚠️ 접속정보(평문 비밀번호)와 결과값을 로그로 남기지 않는다 — 감사 로그는 메타데이터만.
 */
@RestController
@RequestMapping("/mcp/db")
@Profile("api")
public class DbMcpController {

    private static final Logger log = LoggerFactory.getLogger(DbMcpController.class);
    static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final Set<String> KNOWN_PROTOCOLS = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DbMcpTokenService tokens;
    private final DbConnectionService dbConnections;
    private final DbMcpTools tools;

    public DbMcpController(DbMcpTokenService tokens, DbConnectionService dbConnections, DbMcpTools tools) {
        this.tokens = tokens;
        this.dbConnections = dbConnections;
        this.tools = tools;
    }

    @PostMapping("/{connectionId}")
    public ResponseEntity<Object> handle(@PathVariable long connectionId,
                                         @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                         @RequestBody JsonNode body) {
        Optional<Task> task = tokens.authenticate(bearer(auth));
        if (task.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        List<Long> ids = task.get().getDbConnectionIds();
        boolean selected = ids != null && ids.stream().anyMatch(n -> n != null && ((Number) n).longValue() == connectionId);
        if (!selected) return ResponseEntity.notFound().build();
        if (body == null || !body.isObject()) {
            return ResponseEntity.badRequest().body(error(null, -32600, "Invalid Request"));
        }
        JsonNode id = body.get("id");
        String method = body.path("method").asText("");
        if (id == null || id.isNull()) return ResponseEntity.accepted().build(); // notification

        Object result = switch (method) {
            case "initialize" -> initialize(connectionId, body.path("params").path("protocolVersion").asText(""));
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", tools.toolDefinitions(labelOf(connectionId)));
            case "tools/call" -> callTool(task.get(), connectionId, body.path("params"));
            default -> null;
        };
        if (result == null) return ResponseEntity.ok(error(id, -32601, "Method not found: " + method));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("jsonrpc", "2.0");
        ok.put("id", id);
        ok.put("result", result);
        return ResponseEntity.ok(ok);
    }

    @RequestMapping(value = "/{connectionId}", method = {RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<Void> notAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    private Map<String, Object> initialize(long connectionId, String requested) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", KNOWN_PROTOCOLS.contains(requested) ? requested : DEFAULT_PROTOCOL);
        r.put("capabilities", Map.of("tools", Map.of()));
        r.put("serverInfo", Map.of("name", "netismaker-db-" + connectionId, "version", "1.0.0"));
        return r;
    }

    private String labelOf(long connectionId) {
        DbConnectionService.ClaimDb db = dbConnections.resolveForClaim(List.of(connectionId));
        return db.refs().isEmpty() ? "DB #" + connectionId : db.refs().get(0).label();
    }

    private Map<String, Object> callTool(Task task, long connectionId, JsonNode params) {
        String name = params.path("name").asText("");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.path("arguments").isObject()
                ? JSON.convertValue(params.path("arguments"), Map.class) : Map.of();
        long start = System.currentTimeMillis();
        DbConnectionService.ClaimDb db = dbConnections.resolveForClaim(List.of(connectionId));
        DbMcpTools.ToolResult r;
        if (db.refs().isEmpty()) {
            r = new DbMcpTools.ToolResult(db.notices().isEmpty()
                    ? "DB 연결 #" + connectionId + "를 사용할 수 없습니다" : String.join(" / ", db.notices()), true);
        } else {
            DbConnectionRef ref = db.refs().get(0);
            r = tools.call(ref, name, args);
        }
        Object sql = args.get("sql");
        log.info("db-mcp task={} conn={} tool={} sql={} error={} chars={} ms={}", task.getId(), connectionId, name,
                sql instanceof String s ? (s.length() > 200 ? s.substring(0, 200) + "…" : s) : "-",
                r.isError(), r.text().length(), System.currentTimeMillis() - start);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(Map.of("type", "text", "text", r.text())));
        out.put("isError", r.isError());
        return out;
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("jsonrpc", "2.0");
        e.put("id", id);
        e.put("error", Map.of("code", code, "message", message));
        return e;
    }

    private static String bearer(String auth) {
        if (auth == null) return null;
        String a = auth.strip();
        return a.regionMatches(true, 0, "Bearer ", 0, 7) ? a.substring(7).strip() : null;
    }
}
