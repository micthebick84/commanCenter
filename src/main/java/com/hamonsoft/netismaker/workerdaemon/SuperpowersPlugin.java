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
    private final Path ready;

    public SuperpowersPlugin(@Value("${netis-maker.worker.superpowers-plugin-path:}") String configuredPath) {
        this.configured = configuredPath == null ? "" : configuredPath.trim();
        this.ready = resolve(this.configured);
    }

    @PostConstruct
    void logStatus() {
        if (ready != null) {
            log.info("SDD 플러그인: {}", ready);
        } else if (configured.isEmpty()) {
            log.warn("SDD 플러그인 없음(SUPERPOWERS_PLUGIN_PATH 미설정) — SDD 요청은 리뷰로 대신 진행");
        } else {
            log.warn("SDD 플러그인 없음({} 아래 {} 없음) — SDD 요청은 리뷰로 대신 진행", configured, SDD_SKILL);
        }
    }

    /** SDD를 돌릴 수 있는 플러그인 경로 — 준비가 안 됐으면 empty. */
    public Optional<Path> sddReadyPath() {
        return Optional.ofNullable(ready);
    }

    static Path resolve(String configured) {
        if (configured == null || configured.isBlank()) return null;
        try {
            Path dir = Path.of(configured.trim());
            return Files.isRegularFile(dir.resolve(SDD_SKILL)) ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }
}
