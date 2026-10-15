package com.hamonsoft.netismaker.workerdaemon;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * SDD 구현에 --plugin-dir로 붙일 superpowers 플러그인 경로 (스펙 2026-10-15 §6.4·§6.9). 기동 스크립트가 인터뷰 서비스와
 * 같은 SUPERPOWERS_PLUGIN_PATH를 넘긴다. 경로 아래 skills/subagent-driven-development/SKILL.md가 있어야 SDD 가능 —
 * 없으면 SDD 요청은 리뷰로 대신 진행한다. WorkerProperties 레코드에 넣지 않은 것은 그 레코드를 위치 인자로 만드는
 * 테스트가 12곳이라서다.
 */
@Component
@Profile("worker")
@Slf4j
public class SuperpowersPlugin {

    static final String SDD_SKILL = "skills/subagent-driven-development/SKILL.md";

    private final String configured;
    /** 설정한 경로를 절대·정규화한 것 — 설정이 비었거나 경로로 읽을 수 없으면 null. */
    private final Path dir;

    public SuperpowersPlugin(@Value("${netis-maker.worker.superpowers-plugin-path:}") String configuredPath) {
        this.configured = configuredPath == null ? "" : configuredPath.trim();
        this.dir = absolute(this.configured);
    }

    @PostConstruct
    void logStatus() {
        if (sddReadyPath().isPresent()) {
            log.info("SDD 플러그인: {}", dir);
        } else if (configured.isEmpty()) {
            log.warn("SDD 플러그인 없음(SUPERPOWERS_PLUGIN_PATH 미설정) — SDD 요청은 리뷰로 대신 진행");
        } else {
            log.warn("SDD 플러그인 없음({} 아래 {} 없음) — SDD 요청은 리뷰로 대신 진행", configured, SDD_SKILL);
        }
    }

    /**
     * SDD를 돌릴 수 있는 플러그인 경로 — 준비가 안 됐으면 empty. 부를 때마다 SKILL.md를 다시 확인한다(파일 하나 stat):
     * 기동 뒤에 플러그인 폴더가 지워지거나 바뀌면 낡은 --plugin-dir을 주는 대신 리뷰로 대신 진행하게. 경로는 절대 경로다 —
     * claude는 worktree를 작업 디렉터리로 돌아서 워커 기준 상대 경로는 거기서 맞지 않는다.
     * 호출부는 구현 하나에서 한 번만 불러 그 값을 판정과 --plugin-dir에 함께 쓴다(그 사이 상태가 달라지지 않게).
     */
    public Optional<Path> sddReadyPath() {
        if (dir == null) return Optional.empty();
        return Files.isRegularFile(dir.resolve(SDD_SKILL)) ? Optional.of(dir) : Optional.empty();
    }

    private static Path absolute(String configured) {
        if (configured.isBlank()) return null;
        try {
            return Path.of(configured).toAbsolutePath().normalize();
        } catch (RuntimeException e) {   // InvalidPathException 등 — 경로로 읽을 수 없으면 없는 것으로
            return null;
        }
    }
}
