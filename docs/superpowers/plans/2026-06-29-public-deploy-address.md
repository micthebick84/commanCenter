# 공개 배포 주소 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배포된 앱을 `https://task-{id}.micthebick.dev` 공개 서브도메인으로 외부 접속 가능하게 한다.

**Architecture:** Cloudflare Tunnel 와일드카드 ingress(`*.micthebick.dev` → 로컬 Traefik) + Traefik(Docker provider + 라벨)로 Host 기반 라우팅. 워커는 배포 컨테이너에 Traefik 라벨을 부착하고 공유 도커 네트워크 `netis-deploy`에 합류시킨다. 무료 1단계 Universal SSL(`*.micthebick.dev`)로 TLS 커버. host-port `-p` 발행은 유지(로컬 직접 접근).

**Tech Stack:** Java 21 / Spring Boot 3.4 (record `@ConfigurationProperties`), JUnit 5 + AssertJ, Docker CLI(ProcessRunner), Traefik v3, cloudflared.

## Global Constraints

- 슬러그 = `task-{id}` (라우터·서비스·서브도메인 동일). 추측 가능, 변경 금지.
- 공개 URL = `https://task-{id}.micthebick.dev` (1단계, 무료 Universal SSL 커버). 2단계(`*.deploy.*`)·경로기반 금지.
- 접근 제어 없음(완전 공개). CF Access/OAuth 비목표.
- host-port `-p {hostPort}:{containerPort}` 발행 + PortAllocator **유지**. 헬스체크는 로컬 `localhost:{hostPort}` 그대로.
- 공개 모드 기본 **off**(`deploy.public-access.enabled=false`) → 기존 `http://localhost:{hostPort}` 동작 회귀 없음.
- Traefik 엔트리포인트 호스트 포트 = `18080`(배포 포트 범위 19000–19099와 분리). 공유 네트워크 = `netis-deploy`.
- 라이브 인프라(와일드카드 DNS + cloudflared ingress 편집 + cloudflared 재기동)는 **사용자 승인 후** 실행. cloudflared 재기동은 auth/app 터널을 순간 끊음.
- 백틱(`)이 포함된 Traefik Host 라벨은 `ProcessRunner`가 셸 미경유 exec(arg 배열)이라 리터럴 안전.

---

### Task 1: `PublicRoute` 라우팅 헬퍼 (순수·테스트 가능)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicRoute.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicRouteTest.java`

**Interfaces:**
- Consumes: (없음 — 순수 정적 유틸)
- Produces:
  - `static String PublicRoute.slug(long taskId)` → `"task-" + taskId`
  - `static String PublicRoute.publicUrl(long taskId, String baseDomain)` → `"https://task-{id}.{baseDomain}"`
  - `static java.util.List<String> PublicRoute.dockerLabels(long taskId, int containerPort, String baseDomain)` → `key=value` 라벨 3종

- [ ] **Step 1: Write the failing test**

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRouteTest {

    @Test
    void slug_uses_task_id() {
        assertThat(PublicRoute.slug(7)).isEqualTo("task-7");
    }

    @Test
    void publicUrl_is_https_subdomain() {
        assertThat(PublicRoute.publicUrl(7, "micthebick.dev"))
                .isEqualTo("https://task-7.micthebick.dev");
    }

    @Test
    void dockerLabels_emit_enable_router_and_service() {
        List<String> labels = PublicRoute.dockerLabels(7, 8080, "micthebick.dev");
        assertThat(labels).containsExactly(
                "traefik.enable=true",
                "traefik.http.routers.task-7.rule=Host(`task-7.micthebick.dev`)",
                "traefik.http.services.task-7.loadbalancer.server.port=8080");
    }

    @Test
    void dockerLabels_use_parsed_container_port() {
        List<String> labels = PublicRoute.dockerLabels(3, 3000, "micthebick.dev");
        assertThat(labels.get(2))
                .isEqualTo("traefik.http.services.task-3.loadbalancer.server.port=3000");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.PublicRouteTest'`
Expected: FAIL — compile error `cannot find symbol PublicRoute`.

- [ ] **Step 3: Write minimal implementation**

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.List;

/**
 * 공개 배포 라우팅(Traefik) 슬러그·라벨·URL 생성. 순수 함수 — docker 불필요, 단위테스트 대상.
 *
 * 라우터/서비스/서브도메인은 모두 같은 슬러그 task-{id}를 쓴다.
 */
public final class PublicRoute {

    private PublicRoute() {}

    /** Traefik 라우터/서비스명 + 서브도메인 공용 슬러그. */
    public static String slug(long taskId) {
        return "task-" + taskId;
    }

    /** https 공개 URL. */
    public static String publicUrl(long taskId, String baseDomain) {
        return "https://" + slug(taskId) + "." + baseDomain;
    }

    /**
     * docker run 에 부착할 Traefik 라벨 3종 (key=value).
     * Host 규칙의 백틱은 ProcessRunner가 셸 미경유 exec라 리터럴 안전.
     */
    public static List<String> dockerLabels(long taskId, int containerPort, String baseDomain) {
        String s = slug(taskId);
        return List.of(
                "traefik.enable=true",
                "traefik.http.routers." + s + ".rule=Host(`" + s + "." + baseDomain + "`)",
                "traefik.http.services." + s + ".loadbalancer.server.port=" + containerPort);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.PublicRouteTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicRoute.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicRouteTest.java
git commit -m "feat(deploy): 공개 배포 라우팅 헬퍼 PublicRoute (Traefik 라벨·https URL)"
```

---

### Task 2: 공개 모드 설정 — `WorkerProperties.Deploy.PublicAccess`

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java:35-69,92`
- Modify: `src/main/resources/application-worker.yml` (deploy 블록)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerPropertiesDeployTest.java`

**Interfaces:**
- Consumes: (없음)
- Produces:
  - 신규 중첩 레코드 `WorkerProperties.Deploy.PublicAccess(Boolean enabled, String baseDomain, String network)` — 접근자 `enabled()`/`baseDomain()`/`network()`, 기본값 `false`/`"micthebick.dev"`/`"netis-deploy"`
  - `Deploy.publicAccess()` 접근자 (null 시 기본 PublicAccess)

> **Note (Java 키워드):** YAML 키는 `public-access`(컴포넌트 `publicAccess`)로 둔다 — `public`은 예약어라 레코드 컴포넌트명으로 못 씀. Spring relaxed binding이 `public-access` → `publicAccess` 매핑.

- [ ] **Step 1: Write the failing test**

```java
package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPropertiesDeployTest {

    @Test
    void publicAccess_defaults_when_null() {
        WorkerProperties.Deploy d = new WorkerProperties.Deploy(
                null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, null);
        assertThat(d.publicAccess()).isNotNull();
        assertThat(d.publicAccess().enabled()).isFalse();
        assertThat(d.publicAccess().baseDomain()).isEqualTo("micthebick.dev");
        assertThat(d.publicAccess().network()).isEqualTo("netis-deploy");
    }

    @Test
    void publicAccess_keeps_explicit_values() {
        WorkerProperties.Deploy.PublicAccess pa =
                new WorkerProperties.Deploy.PublicAccess(true, "example.com", "web");
        WorkerProperties.Deploy d = new WorkerProperties.Deploy(
                null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, pa);
        assertThat(d.publicAccess().enabled()).isTrue();
        assertThat(d.publicAccess().baseDomain()).isEqualTo("example.com");
        assertThat(d.publicAccess().network()).isEqualTo("web");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.WorkerPropertiesDeployTest'`
Expected: FAIL — compile error (`PublicAccess` 없음, `Deploy` 생성자 인자 수 불일치).

- [ ] **Step 3: 중첩 레코드 + 필드 추가**

`WorkerProperties.java`의 `Deploy` 레코드 헤더에 마지막 컴포넌트 `PublicAccess publicAccess` 추가 — 기존:
```java
            Boolean gcEnabled,
            int gcIntervalMinutes,
            int gcOrphanGraceMinutes,
            int gcKeepImagesPerTask
    ) {
```
변경:
```java
            Boolean gcEnabled,
            int gcIntervalMinutes,
            int gcOrphanGraceMinutes,
            int gcKeepImagesPerTask,
            PublicAccess publicAccess
    ) {
```

`Deploy`의 compact 생성자 끝(`if (gcKeepImagesPerTask <= 0) ...` 다음 줄)에 추가:
```java
            if (publicAccess == null) publicAccess = new PublicAccess(null, null, null);
```

`Deploy` 레코드 본문(닫는 `}` 직전, `portTo()` 메서드 아래)에 중첩 레코드 추가:
```java
        /** 공개 배포 주소(Traefik 라우팅) 설정. */
        public record PublicAccess(Boolean enabled, String baseDomain, String network) {
            public PublicAccess {
                if (enabled == null) enabled = false;
                if (baseDomain == null || baseDomain.isBlank()) baseDomain = "micthebick.dev";
                if (network == null || network.isBlank()) network = "netis-deploy";
            }
        }
```

`WorkerProperties`의 compact 생성자 92행 기본 Deploy 생성 — 기존:
```java
        if (deploy == null) deploy = new Deploy(null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0);
```
변경(마지막에 `, null` 추가):
```java
        if (deploy == null) deploy = new Deploy(null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, null);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.WorkerPropertiesDeployTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: application-worker.yml 기본값 추가**

`src/main/resources/application-worker.yml`의 `netis-maker.worker.deploy:` 블록 안에(기존 `gc-*` 등과 같은 들여쓰기 레벨) 추가:
```yaml
      deploy:
        # ... 기존 키 유지 ...
        public-access:
          enabled: false           # true 로 켜면 공개 서브도메인 라우팅 활성
          base-domain: micthebick.dev
          network: netis-deploy
```

- [ ] **Step 6: 전체 테스트 회귀 확인 + Commit**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.*'`
Expected: PASS (기존 워커 테스트 + 신규 2 tests).

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java \
        src/main/resources/application-worker.yml \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerPropertiesDeployTest.java
git commit -m "feat(deploy): 공개 모드 설정 deploy.public-access (enabled/base-domain/network)"
```

---

### Task 3: `LocalDockerTarget` 공개 모드 — run 인자 + URL

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java:38-45` (DeploySpec에 `taskId` 추가)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java:88-94` (DeploySpec 생성 시 `task.id()` 전달)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java:68-84,143` (run 인자 빌더 추출 + 공개 분기 + URL)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTargetRunArgsTest.java`

**Interfaces:**
- Consumes: `PublicRoute.dockerLabels(long, int, String)`, `PublicRoute.publicUrl(long, String)` (Task 1); `Deploy.publicAccess()` (Task 2)
- Produces:
  - `DeployTarget.DeploySpec` 첫 컴포넌트 `long taskId` 추가
  - `static List<String> LocalDockerTarget.buildRunArgs(DeployTarget.DeploySpec spec, int hostPort, boolean publicMode, String network, String baseDomain)` (package-private, docker 불필요 — 테스트 대상)

- [ ] **Step 1: Write the failing test**

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDockerTargetRunArgsTest {

    private DeployTarget.DeploySpec spec() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FOO", "bar");
        return new DeployTarget.DeploySpec(
                7, Path.of("/tmp/wt"),
                "netis-task-7:abc1234", "netis-task-7", 8080,
                env, Map.of("netis-maker.task", "7"));
    }

    @Test
    void local_mode_publishes_port_no_network_no_traefik() {
        List<String> args = LocalDockerTarget.buildRunArgs(spec(), 19000, false, "netis-deploy", "micthebick.dev");
        assertThat(args).containsSequence("--name", "netis-task-7");
        assertThat(args).containsSequence("-p", "19000:8080");
        assertThat(args).containsSequence("--label", "netis-maker.task=7");
        assertThat(args).containsSequence("-e", "FOO=bar");
        assertThat(args).doesNotContain("--network");
        assertThat(args).noneMatch(a -> a.startsWith("traefik."));
        assertThat(args.get(args.size() - 1)).isEqualTo("netis-task-7:abc1234");
    }

    @Test
    void public_mode_adds_network_and_traefik_labels_and_keeps_port() {
        List<String> args = LocalDockerTarget.buildRunArgs(spec(), 19042, true, "netis-deploy", "micthebick.dev");
        assertThat(args).containsSequence("-p", "19042:8080");                 // host-port 유지
        assertThat(args).containsSequence("--network", "netis-deploy");
        assertThat(args).contains("traefik.enable=true");
        assertThat(args).contains("traefik.http.routers.task-7.rule=Host(`task-7.micthebick.dev`)");
        assertThat(args).contains("traefik.http.services.task-7.loadbalancer.server.port=8080");
        assertThat(args.get(args.size() - 1)).isEqualTo("netis-task-7:abc1234"); // 이미지가 마지막
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.LocalDockerTargetRunArgsTest'`
Expected: FAIL — compile error (`DeploySpec` 인자 수 불일치, `buildRunArgs` 없음).

- [ ] **Step 3: DeploySpec에 taskId 추가**

`DeployTarget.java`의 `DeploySpec` 레코드 — 기존:
```java
    record DeploySpec(
            Path contextDir,
            String imageName,
            String containerName,
            int containerPort,
            Map<String, String> env,
            Map<String, String> labels
    ) {}
```
변경(첫 컴포넌트 `long taskId` 추가 + javadoc 한 줄):
```java
    record DeploySpec(
            long taskId,
            Path contextDir,
            String imageName,
            String containerName,
            int containerPort,
            Map<String, String> env,
            Map<String, String> labels
    ) {}
```

- [ ] **Step 4: DeployService 생성부에 task.id() 전달**

`DeployService.java:88` — 기존:
```java
            DeployTarget.DeploySpec spec = new DeployTarget.DeploySpec(
                    wt.toPath(),
                    "netis-task-" + task.id() + ":" + shortSha,
                    "netis-task-" + task.id(),
                    containerPort,
                    env,
                    Map.of("netis-maker.task", String.valueOf(task.id())));
```
변경(첫 인자 `task.id()` 추가):
```java
            DeployTarget.DeploySpec spec = new DeployTarget.DeploySpec(
                    task.id(),
                    wt.toPath(),
                    "netis-task-" + task.id() + ":" + shortSha,
                    "netis-task-" + task.id(),
                    containerPort,
                    env,
                    Map.of("netis-maker.task", String.valueOf(task.id())));
```

- [ ] **Step 5: LocalDockerTarget — buildRunArgs 추출 + 공개 분기**

`LocalDockerTarget.java`에 package-private 정적 메서드 추가(`deploy()` 메서드 위, 또는 클래스 하단 헬퍼들과 함께). `import java.util.List;`는 이미 있음:
```java
    /**
     * docker run 인자 빌드 (docker 미실행 환경에서도 검증 가능하도록 분리).
     * 공개 모드면 공유 네트워크 합류 + Traefik 라우팅 라벨 부착. host-port 발행은 모드 무관 유지.
     */
    static List<String> buildRunArgs(DeployTarget.DeploySpec spec, int hostPort,
                                     boolean publicMode, String network, String baseDomain) {
        List<String> run = new ArrayList<>(List.of(
                DOCKER, "run", "-d",
                "--name", spec.containerName(),
                "-p", hostPort + ":" + spec.containerPort()));
        spec.labels().forEach((k, v) -> { run.add("--label"); run.add(k + "=" + v); });
        spec.env().forEach((k, v) -> { run.add("-e"); run.add(k + "=" + v); });
        if (publicMode) {
            run.add("--network"); run.add(network);
            for (String label : PublicRoute.dockerLabels(spec.taskId(), spec.containerPort(), baseDomain)) {
                run.add("--label"); run.add(label);
            }
        }
        run.add(spec.imageName());
        return run;
    }
```

`deploy()`의 run 빌드부(68–74행) — 기존:
```java
        // 4. run
        List<String> run = new ArrayList<>(List.of(
                DOCKER, "run", "-d",
                "--name", spec.containerName(),
                "-p", hostPort + ":" + spec.containerPort()));
        spec.labels().forEach((k, v) -> { run.add("--label"); run.add(k + "=" + v); });
        spec.env().forEach((k, v) -> { run.add("-e"); run.add(k + "=" + v); });
        run.add(spec.imageName());
```
변경:
```java
        // 4. run (공개 모드면 Traefik 네트워크/라벨 포함 — buildRunArgs 참조)
        boolean publicMode = cfg.publicAccess().enabled();
        String pubNetwork = cfg.publicAccess().network();
        String pubBaseDomain = cfg.publicAccess().baseDomain();
        List<String> run = buildRunArgs(spec, hostPort, publicMode, pubNetwork, pubBaseDomain);
```

마스킹된 echo(76–84행)에 공개 모드 표시 추가 — 기존 `runEcho.append(' ').append(spec.imageName());` **직전**에 삽입:
```java
        if (publicMode) {
            runEcho.append(" --network ").append(pubNetwork);
            for (String label : PublicRoute.dockerLabels(spec.taskId(), spec.containerPort(), pubBaseDomain))
                runEcho.append(" --label ").append(label);
        }
```

URL 생성(143행) — 기존:
```java
        String url = "http://" + cfg.publicHost() + ":" + hostPort;
```
변경:
```java
        String url = publicMode
                ? PublicRoute.publicUrl(spec.taskId(), pubBaseDomain)
                : "http://" + cfg.publicHost() + ":" + hostPort;
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.LocalDockerTargetRunArgsTest'`
Expected: PASS (2 tests).

- [ ] **Step 7: 전체 테스트 회귀 확인 + Commit**

Run: `./gradlew test`
Expected: PASS — 기존 테스트 + Task1~3 신규 테스트 모두 green (DeploySpec 시그니처 변경이 컴파일·기존 테스트 깨지지 않음 확인).

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTargetRunArgsTest.java
git commit -m "feat(deploy): LocalDockerTarget 공개 모드 — Traefik 네트워크·라벨·https URL"
```

---

### Task 4: Traefik 인프라 스크립트 + 상태 점검

**Files:**
- Create: `scripts/start-traefik.sh`
- Create: `scripts/setup-public-deploy.sh`
- Modify: `scripts/status.sh:30-40` (포트 루프에 18080 + Traefik 컨테이너 체크 추가)

**Interfaces:**
- Consumes: docker, 공유 네트워크 `netis-deploy`
- Produces: 로컬 `:18080`에서 Traefik 기동(미지 Host는 404), `status.sh`가 Traefik 표시

- [ ] **Step 1: `scripts/start-traefik.sh` 작성**

```bash
#!/usr/bin/env bash
#
# Traefik 리버스 프록시 기동 — 공개 배포 라우팅(*.micthebick.dev → 컨테이너).
# Docker provider로 netis-deploy 네트워크의 traefik.enable=true 컨테이너를 Host 라벨로 라우팅.
#
# 사용: ./scripts/start-traefik.sh      중지: docker rm -f traefik
set -euo pipefail

NETWORK="${NETWORK:-netis-deploy}"
ENTRYPOINT_PORT="${ENTRYPOINT_PORT:-18080}"
IMAGE="${TRAEFIK_IMAGE:-traefik:v3.1}"

# 공유 네트워크 보장 (멱등)
docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK"

if docker ps --format '{{.Names}}' | grep -qx traefik; then
  echo "● Traefik 이미 실행 중 — skip"
  exit 0
fi
docker rm -f traefik >/dev/null 2>&1 || true

echo "● Traefik 기동 (:$ENTRYPOINT_PORT, net=$NETWORK)"
docker run -d --name traefik \
  --restart unless-stopped \
  --network "$NETWORK" \
  -p "${ENTRYPOINT_PORT}:80" \
  -v /var/run/docker.sock:/var/run/docker.sock:ro \
  "$IMAGE" \
  --providers.docker=true \
  --providers.docker.exposedByDefault=false \
  --entrypoints.web.address=:80

echo "  ✓ Traefik ready — http://localhost:${ENTRYPOINT_PORT} (미지 Host는 404)"
```

- [ ] **Step 2: `scripts/setup-public-deploy.sh` 작성 (일회성, 멱등)**

```bash
#!/usr/bin/env bash
#
# 공개 배포 인프라 일회성 셋업: 네트워크 + Traefik 기동 + (수동) cloudflared/DNS 안내.
# 멱등 — 재실행 안전.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "── 1) 공유 도커 네트워크 netis-deploy ──"
docker network inspect netis-deploy >/dev/null 2>&1 || docker network create netis-deploy
echo "  ✓ netis-deploy"

echo "── 2) Traefik 기동 ──"
./scripts/start-traefik.sh

cat <<'EOF'

── 3) 수동 단계 (라이브 터널·DNS — 운영자 승인 후 실행) ──
  a) ~/.cloudflared/config.yml 의 catch-all(http_status:404) 위, auth/app 아래에 추가:
       - hostname: "*.micthebick.dev"
         service: http://localhost:18080
  b) 검증:    cloudflared tunnel ingress validate
  c) 재기동:  cloudflared 재시작 (LaunchAgent) — auth/app 순간 끊김 감수
  d) 와일드카드 DNS: *.micthebick.dev proxied CNAME →
       d1418766-bfc3-4179-a29b-79b2e1d63319.cfargotunnel.com
     (Cloudflare 대시보드, 또는: cloudflared tunnel route dns netis '*.micthebick.dev')
EOF
```

- [ ] **Step 3: `status.sh`에 Traefik 점검 추가**

`scripts/status.sh`의 포트 루프 — 기존:
```bash
for p in "$API_PORT" 3001 9000; do
```
변경:
```bash
for p in "$API_PORT" 3001 9000 18080; do
```
그리고 `── 포트 LISTEN ──` 블록 다음에 추가:
```bash
echo ""
echo "── Traefik (공개 배포 프록시) ──"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx traefik; then
  echo "  ✓ traefik 컨테이너 실행 중"
else
  echo "  ✗ traefik 미실행 (공개 URL 비활성 — ./scripts/start-traefik.sh)"
fi
```

- [ ] **Step 4: 실행 권한 + 로컬 검증**

```bash
chmod +x scripts/start-traefik.sh scripts/setup-public-deploy.sh
./scripts/start-traefik.sh
docker ps --format '{{.Names}} {{.Ports}}' | grep traefik
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18080   # 404 = Traefik 살아있음
./scripts/status.sh | sed -n '/Traefik/,+1p'
```
Expected: `traefik ... 0.0.0.0:18080->80/tcp`; curl `404`; status에 `✓ traefik 컨테이너 실행 중`.

- [ ] **Step 5: Commit**

```bash
git add scripts/start-traefik.sh scripts/setup-public-deploy.sh scripts/status.sh
git commit -m "feat(deploy): Traefik 인프라 스크립트(start/setup) + status.sh Traefik 점검"
```

---

### Task 5: 라이브 인프라 적용 + 외부 스모크 (⚠️ 사용자 승인 후 실행 — 코드 아님)

> **이 태스크는 라이브 공개 터널·DNS·운영자 워커를 건드린다.** cloudflared 재기동은 auth/app 터널을 순간 끊는다. **반드시 사용자 승인 후, 사용자와 함께 단계별로 실행.** 자동화/서브에이전트 단독 실행 금지.

**Files:** (코드 변경 없음 — 운영 절차)
- Modify(운영): `~/.cloudflared/config.yml`
- Cloudflare DNS(대시보드/CLI)

- [ ] **Step 1: 인프라 셋업 스크립트 실행**

```bash
./scripts/setup-public-deploy.sh
```
Expected: 네트워크·Traefik 준비 + 수동 단계 안내 출력.

- [ ] **Step 2: cloudflared ingress 편집 (승인 후)**

`~/.cloudflared/config.yml`의 catch-all(`- service: http_status:404`) **위**, auth/app **아래**에 추가:
```yaml
  - hostname: "*.micthebick.dev"
    service: http://localhost:18080
```
검증: `cloudflared tunnel ingress validate`
Expected: `OK`.

- [ ] **Step 3: 와일드카드 DNS 생성 (승인 후)**

Cloudflare 대시보드에서 `*.micthebick.dev` proxied CNAME → `d1418766-bfc3-4179-a29b-79b2e1d63319.cfargotunnel.com`
(또는 `cloudflared tunnel route dns netis '*.micthebick.dev'` — 와일드카드 거부 시 대시보드 사용).

- [ ] **Step 4: cloudflared 재기동 (승인 후 — auth/app 순간 끊김)**

LaunchAgent 재시작(운영자 환경에 맞게). 재기동 후 `auth.micthebick.dev`/`app.micthebick.dev` 정상 복구 확인.

- [ ] **Step 5: 공개 모드 켜고 워커 재기동**

`application-worker.yml`에서 `deploy.public-access.enabled: true` (또는 env `NETIS_MAKER_WORKER_DEPLOY_PUBLIC_ACCESS_ENABLED=true`). 워커 재기동.

- [ ] **Step 6: 외부 스모크**

작업 1건을 배포(`PR생성` → 배포) → `com.task.deploy_url`이 `https://task-{id}.micthebick.dev`인지 확인 → **외부 기기(예: 휴대폰 LTE)에서 해당 URL 접속**해 앱이 뜨는지 확인. undeploy 후 URL이 502/404로 떨어지는지(라우트 자동 제거) 확인.

- [ ] **Step 7: 문서/메모 갱신**

`CLAUDE.md` "환경 메모"에 공개 배포 토글·Traefik 기동 한 줄 추가. (커밋)

---

## Self-Review

**1. Spec coverage:**
- §3 D1 완전공개 → Task 5(접근제어 없음, 단순 노출). ✅
- §3 D2 1단계 서브도메인/무료 SSL → Task1 `publicUrl`/`dockerLabels`, Task5 DNS. ✅
- §3 D3 Traefik 라벨+공유 네트워크 → Task1 라벨, Task3 `--network`+라벨, Task4 Traefik. ✅
- §3 D4 host-port 유지 → Task3 테스트 `-p` 유지 단언. ✅
- §3 D5 ingress 배치 → Task5 Step2(catch-all 위/auth·app 아래). ✅
- §5 일회성 인프라(네트워크/Traefik/ingress/DNS) → Task4(스크립트)+Task5(라이브). ✅
- §6.1 설정 → Task2. §6.2 PublicRoute → Task1. §6.3 LocalDockerTarget+URL → Task3. §6.4 무변경(DB/프론트/undeploy/GC) → 명시적으로 미변경(어느 태스크도 안 건드림). ✅
- §7 라이프사이클(undeploy/redeploy/GC 자동) → 코드 무변경, Task5 Step6 스모크로 라우트 자동 제거 확인. ✅
- §9 테스트(PublicRoute 단위 + 인자 빌더 docker 미실행 검증) → Task1, Task3. ✅

**2. Placeholder scan:** TBD/TODO/"적절히 처리" 없음. 모든 코드 스텝에 실제 코드 포함. ✅

**3. Type consistency:**
- `PublicRoute.slug/publicUrl/dockerLabels` 시그니처 Task1 정의 ↔ Task3 사용 일치(`long taskId, int containerPort, String baseDomain`). ✅
- `DeploySpec(long taskId, ...)` Task3 정의 ↔ DeployService 생성/buildRunArgs 사용 ↔ 테스트 생성자 인자 일치. ✅
- `Deploy.PublicAccess(Boolean,String,String)` + `publicAccess()` Task2 정의 ↔ Task3 `cfg.publicAccess().enabled()/network()/baseDomain()` 사용 일치. ✅
- `Deploy` 생성자 인자 수: Task2에서 14개(기존 13 + publicAccess)로 통일 — 테스트·WorkerProperties:92 모두 14개. ✅
