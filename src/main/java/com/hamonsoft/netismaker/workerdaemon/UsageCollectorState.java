package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 사용량 수집기의 파일별 이어 읽기 표시(로컬 상태 파일).
 * 형식: {@code {"version":1,"files":{"<경로>":{"offset":…,"size":…,"mtimeMillis":…}}}}.
 * 파일이 없거나 깨졌으면(형식·버전이 다르거나 값이 음수여도) 빈 상태로 시작한다 — 모든 파일을 처음부터
 * 다시 읽고, 중복은 API가 키로 막는다.
 */
public final class UsageCollectorState {

    static final int VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param offset      읽은 뒤의 nextOffset(상한에 걸려 멈춘 파일은 {@code offset < size})
     * @param size        읽기 직전에 본 파일 크기
     * @param mtimeMillis 읽기 직전에 본 수정 시각
     */
    public record FileMark(long offset, long size, long mtimeMillis) {}

    private final Map<String, FileMark> files;
    private final boolean fresh;

    private UsageCollectorState(Map<String, FileMark> files, boolean fresh) {
        this.files = files;
        this.fresh = fresh;
    }

    public static UsageCollectorState load(Path file) {
        try {
            if (!Files.isRegularFile(file)) return empty();
            Map<String, FileMark> parsed = parse(MAPPER.readTree(Files.readAllBytes(file)));
            return parsed == null ? empty() : new UsageCollectorState(parsed, false);
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    private static UsageCollectorState empty() {
        return new UsageCollectorState(new TreeMap<>(), true);
    }

    /** 모양이 하나라도 다르면 null(깨진 파일). */
    private static Map<String, FileMark> parse(JsonNode root) {
        if (root == null || !root.isObject()) return null;
        JsonNode version = root.get("version");
        JsonNode filesNode = root.get("files");
        if (version == null || !version.isInt() || version.intValue() != VERSION) return null;
        if (filesNode == null || !filesNode.isObject()) return null;
        Map<String, FileMark> out = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : filesNode.properties()) {
            Long offset = longValue(e.getValue().get("offset"));
            Long size = longValue(e.getValue().get("size"));
            Long mtime = longValue(e.getValue().get("mtimeMillis"));
            if (offset == null || size == null || mtime == null || offset < 0 || size < 0) return null;
            out.put(e.getKey(), new FileMark(offset, size, mtime));
        }
        return out;
    }

    private static Long longValue(JsonNode n) {
        return n != null && n.isIntegralNumber() && n.canConvertToLong() ? n.longValue() : null;
    }

    /** 상태 파일이 없거나 깨져서 빈 상태로 시작했으면 true. */
    public boolean fresh() {
        return fresh;
    }

    public FileMark mark(String path) {
        return files.get(path);
    }

    public void put(String path, FileMark m) {
        files.put(path, m);
    }

    /** 이번 회차에 걸은 경로만 남긴다(사라진 파일의 표시를 지운다). */
    public void retainOnly(Set<String> paths) {
        files.keySet().retainAll(paths);
    }

    /** temp에 쓰고 ATOMIC_MOVE로 바꾼다 — 쓰다 멈춰도 상태 파일이 반쯤 깨지지 않는다(SilentLossTracker와 같은 모양). */
    public void saveAtomically(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", VERSION);
        json.put("files", files);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, MAPPER.writeValueAsBytes(json));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 끝까지 읽었고 그 뒤로 바뀌지 않은 파일만 true. 개행 없는 끝 조각이 남은 파일은 그 조각만 회차마다 다시 읽는다. */
    public static boolean unchanged(FileMark prev, long size, long mtimeMillis) {
        return prev != null && prev.offset() == size && size == prev.size() && mtimeMillis == prev.mtimeMillis();
    }

    /**
     * 처음부터(0) 다시 읽을 때: 표시가 없음, 줄었음(size &lt; prev.size), 크기는 같은데 수정 시각이 다름(바뀌었음),
     * 지금 크기가 지난번 읽은 위치보다 작음(잘렸다가 다시 쓰임 — 읽는 사이 덧붙어 offset &gt; size로 남은 표시).
     * 그 밖에는 지난번 위치부터 이어 읽는다.
     */
    public static long startOffset(FileMark prev, long size, long mtimeMillis) {
        if (prev == null || size < prev.size() || size < prev.offset()) return 0;
        if (size == prev.size() && mtimeMillis != prev.mtimeMillis()) return 0;
        return prev.offset();
    }
}
