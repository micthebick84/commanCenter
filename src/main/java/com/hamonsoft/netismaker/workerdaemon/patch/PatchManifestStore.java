package com.hamonsoft.netismaker.workerdaemon.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 패치 "후" 해시 목록 보관소. 다음 패치의 "전"으로 쓰는 캐시라서 I/O 문제는 호출자에게 던지지 않는다.
 */
final class PatchManifestStore {

    private static final Logger log = LoggerFactory.getLogger(PatchManifestStore.class);
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;
    private final int keep;

    PatchManifestStore(Path dir, int keep) {
        this.dir = dir;
        this.keep = keep;
    }

    /** 저장 형식. */
    private record Stored(String shape, Map<String, String> hashes) {
    }

    Optional<PatchTree> load(String repoKey, String commit, String key) {
        if (!validCommit(commit) || repoKey == null || key == null) {
            return Optional.empty();
        }
        try {
            Path file = file(repoKey, commit, key);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            Stored s = MAPPER.readValue(file.toFile(), Stored.class);
            if (s == null || s.shape() == null || s.hashes() == null) {
                return Optional.empty();
            }
            return Optional.of(new PatchTree(s.shape(), new TreeMap<>(s.hashes())));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    void save(String repoKey, String commit, String key, PatchTree tree) {
        if (!validCommit(commit) || repoKey == null || key == null || tree == null) {
            return;
        }
        try {
            Path file = file(repoKey, commit, key);
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), new Stored(tree.shape(), new TreeMap<>(tree.hashes())));
            prune(file.getParent());
        } catch (Exception e) {
            log.warn("패치 해시 목록 보관 실패(무시): {}", e.toString());
        }
    }

    static String key(String command, String toolchain, String artifact, List<String> ignore) {
        String joined = String.join("\n",
                String.valueOf(command), String.valueOf(toolchain), String.valueOf(artifact),
                String.join(",", ignore == null ? List.of() : ignore));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean validCommit(String commit) {
        return commit != null && SHA.matcher(commit).matches();
    }

    private Path file(String repoKey, String commit, String key) {
        String folder = repoKey.replace('/', '+');
        String prefix = key.length() > 16 ? key.substring(0, 16) : key;
        return dir.resolve(folder).resolve(commit + "-" + prefix + ".json");
    }

    private void prune(Path repoDir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(repoDir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(PatchManifestStore::modified).reversed())
                    .toList();
        }
        for (int i = Math.max(keep, 0); i < files.size(); i++) {
            try {
                Files.deleteIfExists(files.get(i));
            } catch (IOException ignored) {
                // 정리 실패는 무시
            }
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
