package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *  DESIGN §11 분석 결과 엄격 파서.
 *
 *  입력 (claude -p stdout):
 *    ## 1. 요구사항 요약
 *    ...
 *    ## 2. 영향 범위
 *    ...
 *    ## 3. 접근 방법
 *    ...
 *    ## 4. Subtask 분해
 *    - **제목 A**: ...
 *      - 대상 파일: `path1`, `path2`
 *      - 예상 LoC: 약 30줄
 *      - 위험도: M
 *      - 설명: ...
 *    - **제목 B**: ...
 *    ## 5. 위험 요소 및 미해결 질문
 *    ...
 *
 *  출력:
 *    ParseResult { markdown, subtasksJson, warnings }
 *
 *  실패 조건 (ParseException):
 *    1. `## 1.`, `## 2.`, `## 3.` 중 하나라도 누락
 *  경고 조건 (warnings):
 *    - `## 4.` 누락 또는 subtask 0개 → subtasksJson="[]"
 *    - `## 5.` 누락
 *    - 한국어 비율 50% 미만
 */
@Component
public class PromptResultParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ## N. (선택적 한글 제목) 패턴. 줄 시작부터.
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^##\\s+(\\d+)\\.\\s*(.*)$", Pattern.MULTILINE);

    // ## 4. 섹션 내 subtask 항목: `- **제목**: 설명`
    private static final Pattern SUBTASK_HEADER = Pattern.compile(
            "^-\\s*\\*\\*([^*]+?)\\*\\*\\s*:\\s*(.*)$", Pattern.MULTILINE);

    // subtask 내부 속성 추출
    private static final Pattern PROP_FILES = Pattern.compile(
            "(?:^|\\n)\\s*-\\s*대상\\s*파일\\s*:\\s*(.+)", Pattern.MULTILINE);
    private static final Pattern PROP_LOC = Pattern.compile(
            "(?:^|\\n)\\s*-\\s*예상\\s*LoC\\s*:\\s*(.+)", Pattern.MULTILINE);
    private static final Pattern PROP_RISK = Pattern.compile(
            "(?:^|\\n)\\s*-\\s*위험도\\s*:\\s*([LMH])", Pattern.MULTILINE);

    public ParseResult parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new ParseException("출력이 비어있음");
        }

        // 섹션 헤더를 모두 찾아서 번호별 시작 위치 매핑
        java.util.Map<Integer, int[]> sections = new java.util.HashMap<>();  // num → [start, headerEnd]
        Matcher m = SECTION_HEADER.matcher(markdown);
        while (m.find()) {
            int num = Integer.parseInt(m.group(1));
            sections.put(num, new int[]{m.start(), m.end()});
        }

        // 필수 섹션 1~3 검증
        for (int required : new int[]{1, 2, 3}) {
            if (!sections.containsKey(required)) {
                throw new ParseException("섹션 ## " + required + ". 누락");
            }
        }

        java.util.List<String> warnings = new java.util.ArrayList<>();
        ArrayNode subtasks = MAPPER.createArrayNode();

        if (!sections.containsKey(4)) {
            warnings.add("섹션 ## 4. 누락 — subtasks 0개로 처리");
        } else {
            String section4 = extractSection(markdown, sections, 4);
            parseSubtasks(section4, subtasks);
            if (subtasks.isEmpty()) {
                warnings.add("섹션 ## 4. 본문에 subtask 0개");
            }
        }

        if (!sections.containsKey(5)) {
            warnings.add("섹션 ## 5. 누락");
        }

        // 한국어 비율 (best-effort 경고)
        long koreanChars = markdown.chars()
                .filter(c -> (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x1100 && c <= 0x11FF))
                .count();
        long alphaChars = markdown.chars().filter(Character::isLetter).count();
        if (alphaChars > 0 && (double) koreanChars / alphaChars < 0.5) {
            warnings.add("한국어 비율 50% 미만 (" + koreanChars + "/" + alphaChars + ")");
        }

        try {
            return new ParseResult(markdown, MAPPER.writeValueAsString(subtasks), warnings);
        } catch (Exception e) {
            throw new ParseException("subtasks JSON 직렬화 실패", e);
        }
    }

    /**
     * 섹션 N의 본문 (헤더 다음 줄부터 섹션 N+1 또는 EOF까지).
     */
    private String extractSection(String text, java.util.Map<Integer, int[]> sections, int num) {
        int start = sections.get(num)[1];  // 헤더 끝
        // 다음 섹션의 시작 찾기
        int end = text.length();
        for (java.util.Map.Entry<Integer, int[]> e : sections.entrySet()) {
            if (e.getKey() > num) {
                int candStart = e.getValue()[0];
                if (candStart > start && candStart < end) {
                    end = candStart;
                }
            }
        }
        return text.substring(start, end);
    }

    private void parseSubtasks(String section4, ArrayNode out) {
        Matcher m = SUBTASK_HEADER.matcher(section4);
        // 각 subtask 헤더의 시작 위치 수집
        java.util.List<int[]> headers = new java.util.ArrayList<>();
        java.util.List<String[]> titles = new java.util.ArrayList<>();
        while (m.find()) {
            headers.add(new int[]{m.start(), m.end()});
            titles.add(new String[]{m.group(1).trim(), m.group(2).trim()});
        }
        for (int i = 0; i < headers.size(); i++) {
            int bodyStart = headers.get(i)[1];
            int bodyEnd = (i + 1 < headers.size()) ? headers.get(i + 1)[0] : section4.length();
            String body = section4.substring(bodyStart, bodyEnd);

            ObjectNode item = MAPPER.createObjectNode();
            item.put("title", titles.get(i)[0]);
            item.put("summary", titles.get(i)[1]);

            Matcher mf = PROP_FILES.matcher(body);
            if (mf.find()) item.put("files", mf.group(1).trim());

            Matcher ml = PROP_LOC.matcher(body);
            if (ml.find()) item.put("estimatedLoc", ml.group(1).trim());

            Matcher mr = PROP_RISK.matcher(body);
            if (mr.find()) item.put("risk", mr.group(1));

            out.add(item);
        }
    }

    public record ParseResult(String markdown, String subtasksJson, java.util.List<String> warnings) {}

    public static class ParseException extends RuntimeException {
        public ParseException(String msg) { super(msg); }
        public ParseException(String msg, Throwable cause) { super(msg, cause); }
    }
}
