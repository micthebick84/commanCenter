package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.config.RenderProperties;
import com.hamonsoft.netismaker.service.AttachmentStorage;
import com.hamonsoft.netismaker.util.FileTrees;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 첨부 한 건을 슬라이드 PNG로 만든다 (스펙 2026-10-27 §6). 작업 폴더 {renderWorkRoot}/{uuid}에 원본을 input.&lt;ext&gt;로
 * 복사(원본 이름을 명령줄에 싣지 않는다) → soffice로 PDF(실행마다 새 사용자 프로필, 시간 상한, 넘으면 프로세스 트리째 종료)
 * → PDFBox로 페이지 PNG → (pptx면) ppt/media 원본 그림. 작업 폴더는 성공·실패 모두 지운다.
 * 출력 폴더는 먼저 비우고 쓰며, 실패하면 반쯤 쓴 출력도 비운다. 호출은 직렬 1건 전제(잡이 보장).
 */
@Slf4j
@Component
@Profile("api")
public class SlideRenderer {

    static final String MEDIA_DIR = "media";
    private static final Pattern SAFE_EXT = Pattern.compile("[a-z0-9]{1,10}");
    private static final long KILL_WAIT_SECONDS = 5;

    private final RenderProperties props;
    private final RenderTargetPolicy policy;
    private final ProcessStarter starter;
    private final Path workRoot;

    @Autowired
    public SlideRenderer(RenderProperties props, RenderTargetPolicy policy, ProcessStarter starter,
                         AttachmentStorage storage) {
        this(props, policy, starter, storage.renderWorkRoot());
    }

    SlideRenderer(RenderProperties props, RenderTargetPolicy policy, ProcessStarter starter, Path workRoot) {
        this.props = props;
        this.policy = policy;
        this.starter = starter;
        this.workRoot = workRoot.toAbsolutePath().normalize();
    }

    public RenderResult render(Path source, String extension, Path outDir) throws RenderException {
        Path soffice = policy.sofficePath().orElseThrow(() -> new RenderException(RenderTargetPolicy.OFF_REASON));
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (!SAFE_EXT.matcher(ext).matches()) throw new RenderException("지원하지 않는 형식");

        Path work = workRoot.resolve(UUID.randomUUID().toString());
        boolean ok = false;
        try {
            try {
                Files.createDirectories(work);
                Files.copy(source, work.resolve("input." + ext));
                clear(outDir);
            } catch (IOException e) {
                log.warn("슬라이드 렌더링 준비 실패: {} ({})", source, e.toString());
                throw new RenderException("렌더링 준비 실패", e);
            }

            runSoffice(command(soffice, work, ext), work);
            Path pdf = work.resolve("input.pdf");
            if (!Files.isRegularFile(pdf)) {
                log.warn("슬라이드 렌더링: soffice가 PDF를 만들지 않음 — {}", logTail(work));
                throw new RenderException("PDF 변환 실패");
            }

            RenderResult pages;
            try {
                pages = PdfPageRasterizer.rasterize(pdf, outDir, props.widthPx(), props.maxPages());
            } catch (IOException | RuntimeException e) {
                log.warn("슬라이드 렌더링: PDF → PNG 실패 ({})", e.toString());
                throw new RenderException("그림 변환 실패", e);
            }

            int media = 0;
            if ("pptx".equals(ext)) {
                try {
                    media = MediaExtractor.extract(work.resolve("input." + ext), outDir.resolve(MEDIA_DIR), props.maxMedia());
                } catch (IOException | RuntimeException e) {
                    // 슬라이드 그림은 이미 있다 — 원본 그림만 없이 성공으로 둔다
                    log.warn("슬라이드 렌더링: 원본 그림 추출 실패 — 슬라이드 그림만 남김 ({})", e.toString());
                }
            }
            ok = true;
            return new RenderResult(pages.pageCount(), pages.totalPages(), media);
        } finally {
            deleteWork(work);
            if (!ok) {
                try {
                    clear(outDir);
                } catch (IOException e) {
                    log.warn("슬라이드 렌더링: 실패한 출력 정리 실패: {} ({})", outDir, e.toString());
                }
            }
        }
    }

    /** soffice 명령 — 사용자 프로필을 작업 폴더 안에 따로 둬 동시 실행 잠금·운영자 프로필 오염을 막는다. */
    List<String> command(Path soffice, Path work, String ext) {
        return List.of(
                soffice.toString(), "--headless", "--norestore", "--nologo",
                "-env:UserInstallation=" + work.resolve("profile").toUri(),
                "--convert-to", "pdf", "--outdir", work.toString(), work.resolve("input." + ext).toString());
    }

    private void runSoffice(List<String> command, Path work) throws RenderException {
        Process p;
        try {
            p = starter.start(command, work);
        } catch (IOException e) {
            log.warn("슬라이드 렌더링: soffice 실행 실패 ({})", e.toString());
            throw new RenderException("PDF 변환 실패", e);
        }
        int timeout = props.timeoutSeconds();
        try {
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                killTree(p);
                log.warn("슬라이드 렌더링: {}초 안에 끝나지 않아 soffice 프로세스 트리를 종료함", timeout);
                throw new RenderException("렌더링 시간 초과(" + timeout + "초)");
            }
        } catch (InterruptedException e) {
            killTree(p);
            Thread.currentThread().interrupt();
            throw new RenderException("렌더링 중단", e);
        }
        if (p.exitValue() != 0) {
            log.warn("슬라이드 렌더링: soffice 종료 코드 {} — {}", p.exitValue(), logTail(work));
            throw new RenderException("PDF 변환 실패");
        }
    }

    /**
     * 프로세스 트리째 종료 — soffice.com·soffice는 실제 soffice.bin을 띄우는 런처라 런처만 죽이면 변환이 뒤에서 계속된다.
     * 자식 목록을 먼저 모은 뒤(부모가 죽으면 더는 자손으로 안 보인다) 자식 → 부모 순으로 강제 종료하고 잠깐 기다린다.
     */
    static void killTree(Process p) {
        List<ProcessHandle> descendants = p.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            for (ProcessHandle d : descendants) {
                d.onExit().get(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("슬라이드 렌더링: 종료한 프로세스가 아직 남아 있을 수 있음 ({})", e.toString());
        }
    }

    /** 출력 폴더를 비운다(폴더 자체는 남긴다). 없으면 만든다. */
    private static void clear(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
                for (Path c : children) {
                    if (FileTrees.deleteTree(c).failures() > 0) throw new IOException("지우지 못함: " + c);
                }
            }
        }
        Files.createDirectories(dir);
    }

    /** 방금 죽인 프로세스가 파일을 잠깐 쥐고 있을 수 있어(Windows) 몇 번 다시 시도한다. 남으면 기동 정리(Task 4)가 지운다. */
    private static void deleteWork(Path work) {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (FileTrees.deleteTree(work).failures() == 0 && !Files.exists(work)) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("슬라이드 렌더링: 작업 폴더를 지우지 못함: {}", work);
    }

    /** soffice 출력 끝부분(서버 로그용 — 사용자 문구에는 넣지 않는다). */
    private static String logTail(Path work) {
        Path log = work.resolve(ProcessBuilderStarter.LOG_NAME);
        try {
            if (!Files.isRegularFile(log)) return "(출력 없음)";
            // Windows 콘솔 출력은 UTF-8이 아닐 수 있다 — 깨진 바이트는 대체 문자로
            String s = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            return s.length() > 500 ? s.substring(s.length() - 500) : s;
        } catch (IOException | RuntimeException e) {
            return "(출력 읽기 실패)";
        }
    }
}
