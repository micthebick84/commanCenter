package com.hamonsoft.netismaker.workerdaemon.patch;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 산출물(war·jar·war 묶음 zip)의 항목별 해시를 구하고, 고른 항목만 꺼낸다. 순수 JDK 로직이다.
 *
 * <p>중첩 {@code .jar}는 풀지 않고 한 경로로 다룬다. 해시는 (무시 패턴을 뺀 항목 이름 + 내용 SHA-256)을
 * 이름순으로 이은 것의 SHA-256이라 항목 시각·순서·압축 방식이 달라도 같다. 큰 war(수백 MB)를 위해 항목은
 * 64KB 버퍼로 스트림 처리하고 통째로 메모리에 올리지 않는다.
 */
public final class PatchScanner {

    private static final int BUFFER = 64 * 1024;

    private enum Kind { WAR, JAR, BUNDLE }

    /** walk가 방문하는 한 파일. {@code in}은 호출자가 닫지 않는다. */
    private interface Visitor {
        void entry(String path, String name, InputStream in) throws IOException, PatchException;
    }

    private final List<Pattern> ignore;

    public PatchScanner(List<String> ignoreGlobs) {
        List<Pattern> patterns = new ArrayList<>();
        if (ignoreGlobs != null) {
            for (String g : ignoreGlobs) {
                if (g != null && !g.isBlank()) {
                    patterns.add(globToRegex(g.strip()));
                }
            }
        }
        this.ignore = List.copyOf(patterns);
    }

    public PatchTree scan(Path artifact) throws IOException, PatchException {
        Kind kind = kindOf(artifact);
        TreeMap<String, String> hashes = new TreeMap<>();
        Set<String> warNames = new TreeSet<>();
        walk(artifact, kind, prefix -> true, warNames, (path, name, in) -> {
            String h = isJar(name) ? jarHash(path, in) : sha256(in);
            if (hashes.putIfAbsent(path, h) != null) {
                // 덮어쓰면 한쪽 파일이 비교에서 빠져 변경이 사라진 패치가 정상처럼 나간다
                throw new PatchException("패치 경로가 겹칩니다: " + path);
            }
        });
        checkCaseCollisions(hashes.keySet());
        String shape = switch (kind) {
            case WAR -> "single:war";
            case JAR -> "single:jar";
            case BUNDLE -> "bundle:" + List.copyOf(warNames);
        };
        return new PatchTree(shape, hashes);
    }

    public void extract(Path artifact, Collection<String> paths, Path destDir) throws IOException, PatchException {
        Kind kind = kindOf(artifact);
        Path root = destDir.toAbsolutePath().normalize();
        TreeSet<String> wanted = new TreeSet<>(paths);
        for (String p : wanted) {
            checkName(p);
            if (!root.resolve(p).normalize().startsWith(root)) {
                throw new PatchException("압축 항목 경로가 잘못됐습니다: " + p);
            }
        }
        Set<String> found = new TreeSet<>();
        walk(artifact, kind,
                prefix -> wanted.stream().anyMatch(p -> p.startsWith(prefix + "/")),
                new TreeSet<>(), (path, name, in) -> {
                    if (wanted.contains(path) && found.add(path)) {
                        Path dest = root.resolve(path).normalize();
                        Files.createDirectories(dest.getParent());
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                });
        for (String p : wanted) {
            if (!found.contains(p)) {
                throw new PatchException("패치 항목을 찾지 못했습니다: " + p);
            }
        }
    }

    // ---- 순회 ----

    private void walk(Path artifact, Kind kind, Predicate<String> warPrefixFilter, Set<String> warNames,
            Visitor visitor) throws IOException, PatchException {
        try (ZipFile zf = new ZipFile(artifact.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                checkName(name);
                if (isIgnored(name)) {
                    continue;
                }
                if (kind == Kind.BUNDLE && name.toLowerCase(Locale.ROOT).endsWith(".war")) {
                    walkWar(zf, e, warPrefixFilter, warNames, visitor);
                } else {
                    try (InputStream in = zf.getInputStream(e)) {
                        visitor.entry(name, name, in);
                    }
                }
            }
        }
    }

    private void walkWar(ZipFile zf, ZipEntry warEntry, Predicate<String> warPrefixFilter, Set<String> warNames,
            Visitor visitor) throws IOException, PatchException {
        String warName = lastSegment(warEntry.getName());
        String prefix = warName.substring(0, warName.length() - ".war".length());
        if (!warNames.add(warName)) {
            throw new PatchException("묶음 안에 같은 이름의 war가 둘 이상 있습니다: " + warName);
        }
        if (!warPrefixFilter.test(prefix)) {
            return;
        }
        try (ZipInputStream zin = new ZipInputStream(zf.getInputStream(warEntry))) {
            InputStream shared = new NonClosingInputStream(zin);
            ZipEntry inner;
            while ((inner = zin.getNextEntry()) != null) {
                if (inner.isDirectory()) {
                    continue;
                }
                String name = inner.getName();
                checkName(name);
                if (isIgnored(name)) {
                    continue;
                }
                visitor.entry(prefix + "/" + name, name, shared);
            }
        }
    }

    // ---- 해시 ----

    /** 패치는 Windows 워커에서 풀린다 — 대소문자만 다른 두 경로는 디스크에서 한 파일이 된다. */
    private static void checkCaseCollisions(Collection<String> paths) throws PatchException {
        Map<String, String> seen = new TreeMap<>();
        for (String p : paths) {
            String prev = seen.putIfAbsent(p.toLowerCase(Locale.ROOT), p);
            if (prev != null) {
                throw new PatchException("대소문자만 다른 경로가 있습니다: " + prev + " / " + p);
            }
        }
    }

    private String jarHash(String jarPath, InputStream in) throws IOException, PatchException {
        TreeMap<String, String> inner = new TreeMap<>();
        // 한 번만 읽을 수 있는 스트림이라 원본 바이트 해시를 같은 패스에서 함께 계산한다.
        MessageDigest raw = digest();
        DigestInputStream rawIn = new DigestInputStream(new NonClosingInputStream(in), raw);
        ZipInputStream zin = new ZipInputStream(rawIn);
        ZipEntry e;
        int seen = 0;
        while ((e = zin.getNextEntry()) != null) {
            seen++;
            if (e.isDirectory() || isIgnored(e.getName())) {
                continue;
            }
            if (inner.putIfAbsent(e.getName(), sha256(new NonClosingInputStream(zin))) != null) {
                throw new PatchException("패치 경로가 겹칩니다: " + jarPath + "!/" + e.getName());
            }
        }
        if (seen == 0) {
            // zip으로 읽히지 않는 .jar(앞에 스크립트가 붙은 실행 jar, zip이 아닌 파일, 빈 zip): 항목 해시가 없으면
            // 모든 버전이 같은 해시가 되어 변경이 사라지므로 원본 바이트 SHA-256으로 비교한다.
            byte[] buf = new byte[BUFFER];
            while (rawIn.read(buf) != -1) {
                // 남은 바이트를 모두 해시에 반영한다.
            }
            return HexFormat.of().formatHex(raw.digest());
        }
        MessageDigest md = digest();
        for (Map.Entry<String, String> en : inner.entrySet()) {
            md.update((en.getKey() + "\0" + en.getValue() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String sha256(InputStream in) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[BUFFER];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---- 이름 규칙 ----

    private static Kind kindOf(Path artifact) throws PatchException {
        String fileName = artifact.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            throw new PatchException("tar.gz 묶음은 부분 패치를 지원하지 않습니다");
        }
        if (lower.endsWith(".war")) {
            return Kind.WAR;
        }
        if (lower.endsWith(".jar")) {
            return Kind.JAR;
        }
        if (lower.endsWith(".zip")) {
            return Kind.BUNDLE;
        }
        throw new PatchException("지원하지 않는 산출물 형식입니다: " + fileName);
    }

    private static boolean isJar(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static String lastSegment(String name) {
        String n = name.replace('\\', '/');
        return n.substring(n.lastIndexOf('/') + 1);
    }

    /** 절대 경로이거나 정규화 뒤 밖(..)을 가리키는 항목 이름을 거부한다. */
    private static void checkName(String name) throws PatchException {
        String n = name.replace('\\', '/');
        boolean bad = n.isEmpty() || n.startsWith("/") || n.matches("^[A-Za-z]:.*");
        if (!bad) {
            int depth = 0;
            for (String seg : n.split("/")) {
                if (seg.equals("..")) {
                    if (--depth < 0) {
                        bad = true;
                        break;
                    }
                } else if (!seg.isEmpty() && !seg.equals(".")) {
                    depth++;
                }
            }
        }
        if (bad) {
            throw new PatchException("압축 항목 경로가 잘못됐습니다: " + name);
        }
    }

    // ---- 무시 패턴 ----

    boolean isIgnored(String entryName) {
        for (Pattern p : ignore) {
            if (p.matcher(entryName).matches()) {
                return true;
            }
        }
        return false;
    }

    /** '/'만 구분자. {@code *}는 한 단계 안, {@code **}는 단계를 넘어 맞는다. 와일드카드가 없으면 정확히 같아야 한다. */
    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (glob.startsWith("**", i)) {
                    if (glob.startsWith("**/", i)) {
                        sb.append("(?:.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return Pattern.compile(sb.toString());
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // 바깥 스트림은 순회하는 쪽이 닫는다.
        }
    }
}
