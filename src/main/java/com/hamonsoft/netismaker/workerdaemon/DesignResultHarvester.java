package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * claude 디자인 세션의 .design-out/ 산출물을 수확해 보고 페이로드로 변환.
 *
 *  계약 (design-prompt-template과 동기):
 *    ERROR.txt        → 존재하면 즉시 실패 (인증/pull 실패 fail-fast)
 *    DESIGN.md        → 필수. designMarkdown
 *    screens/*.html   → 1개 이상 필수. mockup_files의 html 원문
 *    result.json      → 선택. {screens:[{path,title}], designProjectId, designUrl}
 *                       title은 result.json 우선, 없으면 파일명 사용
 */
@Component
@Profile("worker")
public class DesignResultHarvester {

    private static final long MAX_MOCKUP_BYTES = 200 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();

    public HarvestResult harvest(File worktreeDir) throws HarvestException {
        Path out = worktreeDir.toPath().resolve(".design-out");
        try {
            Path error = out.resolve("ERROR.txt");
            if (Files.exists(error)) {
                throw new HarvestException("디자인 세션 중단: " + Files.readString(error).trim());
            }
            Path designMd = out.resolve("DESIGN.md");
            if (!Files.exists(designMd)) {
                throw new HarvestException(".design-out/DESIGN.md 없음 — 세션이 산출물을 만들지 못함");
            }
            Path screensDir = out.resolve("screens");
            List<Path> screens = new ArrayList<>();
            if (Files.isDirectory(screensDir)) {
                try (var s = Files.list(screensDir)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".html"))
                     .sorted().forEach(screens::add);
                }
            }
            if (screens.isEmpty()) {
                throw new HarvestException(".design-out/screens/*.html 없음 — 목업이 생성되지 않음");
            }

            String designProjectId = null;
            String designUrl = null;
            java.util.Map<String, String> titles = new java.util.HashMap<>();
            Path resultJson = out.resolve("result.json");
            if (Files.exists(resultJson)) {
                JsonNode root = mapper.readTree(Files.readString(resultJson));
                designProjectId = root.path("designProjectId").isTextual()
                        ? root.get("designProjectId").asText() : null;
                designUrl = root.path("designUrl").isTextual()
                        ? root.get("designUrl").asText() : null;
                for (JsonNode s : root.path("screens")) {
                    if (s.path("path").isTextual() && s.path("title").isTextual()) {
                        titles.put(s.get("path").asText(), s.get("title").asText());
                    }
                }
            }

            ArrayNode mockups = mapper.createArrayNode();
            for (Path p : screens) {
                if (Files.size(p) > MAX_MOCKUP_BYTES) {
                    throw new HarvestException("목업 파일 200KB 초과: " + p.getFileName());
                }
                String rel = "screens/" + p.getFileName();
                ObjectNode node = mockups.addObject();
                node.put("path", rel);
                node.put("title", titles.getOrDefault(rel, p.getFileName().toString()));
                node.put("html", Files.readString(p));
            }

            return new HarvestResult(Files.readString(designMd),
                    mapper.writeValueAsString(mockups), designProjectId, designUrl);
        } catch (IOException e) {
            throw new HarvestException(".design-out 수확 실패: " + e.getMessage());
        }
    }

    public record HarvestResult(String designMarkdown, String mockupFilesJson,
                                String designProjectId, String designUrl) {}

    public static class HarvestException extends Exception {
        public HarvestException(String message) {
            super(message);
        }
    }
}
