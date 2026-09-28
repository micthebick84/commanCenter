# 원격 Docker 배포 공개 주소 (배포별 DNS 자동 등록) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Windows 워커가 원격 Docker(10.1.1.75)에 띄운 배포를 `https://task-{id}-win.micthebick.dev`로 외부 공개한다 — 배포마다 Cloudflare CNAME을 만들고, 내리거나 GC할 때 지운다.

**Architecture:** 기존 공개 모드(Traefik Host 라벨)를 슬러그 접미사로 확장하고, `LocalDockerTarget`을 감싸는 `DnsRegisteringDeployTarget` 데코레이터가 deploy/stop/gc 전후에 `PublicDnsRegistrar`(Cloudflare REST)를 호출한다. 데코레이터는 `provider=cloudflare`일 때만 `@Primary` 빈으로 등록되어 `DeployService`·`DockerGcJob`·`DeployReconcileJob`은 무변경. 맥 스택은 기본값(suffix 없음, provider none)으로 현행과 동일.

**Tech Stack:** Java 21, Spring Boot(`@ConfigurationProperties` record), `java.net.http.HttpClient`, Jackson, JUnit 5 + AssertJ, JDK `com.sun.net.httpserver.HttpServer`(가짜 API), `ApplicationContextRunner`.

**Spec:** `docs/superpowers/specs/2026-09-23-remote-public-deploy-dns-design.md`

## Global Constraints

- 주소 형식: `task-{id}{suffix}.{baseDomain}` — Windows `suffix=-win`, `baseDomain=micthebick.dev`. 1단계 서브도메인만(2단계 금지).
- `slug-suffix` 허용 형식: 정규식 `(-[a-z0-9]+)*` (빈 값 허용). 그 외는 기동 실패.
- `provider=cloudflare`면 `enabled=true`와 `CLOUDFLARE_API_TOKEN`/`CLOUDFLARE_ZONE_ID`/`CLOUDFLARE_TUNNEL_ID` 필수, 누락 시 기동 실패.
- DNS 레코드: `type=CNAME`, `content={tunnelId}.cfargotunnel.com`, `proxied=true`, `ttl=1`, `comment=netis-maker:{WORKER_ID}`.
- **토큰은 로그·예외 메시지·`toString()`·배포 로그 어디에도 나오면 안 된다.**
- DNS upsert 실패 = 컨테이너 정리 + 배포실패. DNS delete 실패 = 경고만(GC가 회수).
- GC는 comment 태그가 붙고 `task-(\d+){suffix}.{baseDomain}`에 맞으며, 보호 목록에 없고, 컨테이너 상태가 `STOPPED`인 레코드만 지운다.
- 맥 스택 회귀 금지: suffix `""` + provider `none`이면 라벨/URL/빈 구성이 현행과 동일.
- 테스트 실행(이 PC, Git Bash): `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "<FQCN>"` — `--tests "*Foo*"` 글롭은 쓰지 말 것(디렉터리로 확장돼 실패).
- 프로덕션 코드 스타일: 한국어 주석, 기존 파일의 밀도·관용구를 따른다. 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

## File Structure

| 파일 | 책임 |
|---|---|
| Modify `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java` | `PublicAccess`에 `slugSuffix`, `Dns` 추가 + 검증 |
| Modify `src/main/resources/application-worker.yml` | 신규 env 키 바인딩 |
| Modify `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicRoute.java` | suffix 슬러그·호스트명·역파싱 |
| Modify `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java` | run 인자/URL에 suffix 반영 |
| Create `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicDnsRegistrar.java` | DNS 등록 포트(인터페이스) |
| Create `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/CloudflareDnsRegistrar.java` | Cloudflare REST 구현 + 설정 검증 팩토리 |
| Create `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DnsRegisteringDeployTarget.java` | deploy/stop/gc에 DNS 수명주기를 얹는 데코레이터 |
| Create `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicDnsConfiguration.java` | provider=cloudflare일 때 `@Primary` 데코레이터 빈 |
| Tests `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerPropertiesDeployTest.java`, `.../deploy/PublicRouteTest.java`, `.../deploy/LocalDockerTargetRunArgsTest.java`, 신규 `.../deploy/CloudflareDnsRegistrarTest.java`, `.../deploy/DnsRegisteringDeployTargetTest.java`, `.../deploy/PublicDnsConfigurationTest.java` | |
| Modify `CLAUDE.md` | 원격 Docker 배포 섹션의 공개 주소 안내 |

---

### Task 1: 설정 — `slugSuffix`와 `Dns`

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java:85-92` (`PublicAccess`), `:81` (`Deploy` 기본값 생성 줄)
- Modify: `src/main/resources/application-worker.yml:173-179` (`public-access:` 블록)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerPropertiesDeployTest.java`

**Interfaces:**
- Produces:
  - `record WorkerProperties.Deploy.PublicAccess(Boolean enabled, String baseDomain, String network, String slugSuffix, Dns dns)` — `slugSuffix()`는 null이 아닌 트림된 문자열(기본 `""`), `dns()`는 null 아님.
  - `record WorkerProperties.Deploy.PublicAccess.Dns(String provider, String apiToken, String zoneId, String tunnelId)` — `provider()`는 소문자(기본 `"none"`), `boolean cloudflare()`, `toString()`은 토큰 마스킹.

- [ ] **Step 1: 실패하는 테스트 작성** — `WorkerPropertiesDeployTest.java`를 아래로 교체

```java
package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerPropertiesDeployTest {

    private static WorkerProperties.Deploy deployWith(WorkerProperties.Deploy.PublicAccess pa) {
        return new WorkerProperties.Deploy(
                null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, null, 0, pa);
    }

    @Test
    void publicAccess_defaults_when_null() {
        WorkerProperties.Deploy d = deployWith(null);
        assertThat(d.publicAccess()).isNotNull();
        assertThat(d.publicAccess().enabled()).isFalse();
        assertThat(d.publicAccess().baseDomain()).isEqualTo("micthebick.dev");
        assertThat(d.publicAccess().network()).isEqualTo("netis-deploy");
        assertThat(d.publicAccess().slugSuffix()).isEmpty();
        assertThat(d.publicAccess().dns().provider()).isEqualTo("none");
        assertThat(d.publicAccess().dns().cloudflare()).isFalse();
    }

    @Test
    void reconcile_defaults_enabled_with_5min_interval() {
        WorkerProperties.Deploy d = deployWith(null);
        assertThat(d.reconcileEnabled()).isTrue();
        assertThat(d.reconcileIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void publicAccess_keeps_explicit_values() {
        WorkerProperties.Deploy.PublicAccess pa = new WorkerProperties.Deploy.PublicAccess(
                true, "example.com", "web", "-win",
                new WorkerProperties.Deploy.PublicAccess.Dns("Cloudflare", "tok", "zone", "tun"));
        WorkerProperties.Deploy d = deployWith(pa);
        assertThat(d.publicAccess().enabled()).isTrue();
        assertThat(d.publicAccess().baseDomain()).isEqualTo("example.com");
        assertThat(d.publicAccess().network()).isEqualTo("web");
        assertThat(d.publicAccess().slugSuffix()).isEqualTo("-win");
        assertThat(d.publicAccess().dns().provider()).isEqualTo("cloudflare");   // 소문자 정규화
        assertThat(d.publicAccess().dns().cloudflare()).isTrue();
    }

    @Test
    void slugSuffix_rejects_non_dns_label_values() {
        for (String bad : new String[]{"win", "_win", "-Win", "-win.", "--", "-"}) {
            assertThatThrownBy(() -> new WorkerProperties.Deploy.PublicAccess(null, null, null, bad, null))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("slug-suffix");
        }
    }

    @Test
    void slugSuffix_accepts_multi_segment_suffix() {
        assertThat(new WorkerProperties.Deploy.PublicAccess(null, null, null, " -win-2 ", null).slugSuffix())
                .isEqualTo("-win-2");
    }

    @Test
    void dns_toString_never_prints_the_token() {
        WorkerProperties.Deploy.PublicAccess.Dns dns =
                new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", "cf-secret-123", "zone", "tun");
        assertThat(dns.toString()).doesNotContain("cf-secret-123").contains("zone").contains("tun");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorkerPropertiesDeployTest"`
Expected: 컴파일 실패 — `PublicAccess` 생성자 인자 수 불일치, `slugSuffix()`/`dns()` 없음.

- [ ] **Step 3: 구현** — `WorkerProperties.java`의 `PublicAccess` record를 교체

```java
        /** 공개 배포 주소(Traefik 라우팅) 설정. */
        public record PublicAccess(Boolean enabled, String baseDomain, String network,
                                   // 슬러그 접미사: task-{id}{slugSuffix}. 같은 base-domain을 쓰는 다른 스택과 이름 충돌 회피용(예 "-win").
                                   String slugSuffix,
                                   Dns dns) {
            public PublicAccess {
                if (enabled == null) enabled = false;
                if (baseDomain == null || baseDomain.isBlank()) baseDomain = "micthebick.dev";
                if (network == null || network.isBlank()) network = "netis-deploy";
                slugSuffix = slugSuffix == null ? "" : slugSuffix.trim();
                // DNS 라벨로 안전한 값만 — 잘못된 값은 배포 때가 아니라 부팅 때 드러낸다.
                if (!slugSuffix.matches("(-[a-z0-9]+)*")) {
                    throw new IllegalArgumentException(
                            "deploy.public-access.slug-suffix는 '-소문자영숫자' 형식이어야 한다: '" + slugSuffix + "'");
                }
                if (dns == null) dns = new Dns(null, null, null, null);
            }

            /**
             * 배포별 DNS 레코드 자동 등록. provider=none(기본)이면 사용하지 않는다.
             * cloudflare: 배포마다 {slug}.{baseDomain} CNAME → {tunnelId}.cfargotunnel.com.
             */
            public record Dns(String provider, String apiToken, String zoneId, String tunnelId) {
                public Dns {
                    provider = (provider == null || provider.isBlank())
                            ? "none" : provider.trim().toLowerCase(java.util.Locale.ROOT);
                }

                public boolean cloudflare() {
                    return "cloudflare".equals(provider);
                }

                /** 토큰은 절대 출력하지 않는다(설정 덤프·로그 대비). */
                @Override
                public String toString() {
                    return "Dns[provider=" + provider + ", apiToken="
                            + (apiToken == null || apiToken.isBlank() ? "(없음)" : "•••")
                            + ", zoneId=" + zoneId + ", tunnelId=" + tunnelId + "]";
                }
            }
        }
```

같은 파일 `Deploy` compact 생성자의 기본값 줄을 교체:

```java
            if (publicAccess == null) publicAccess = new PublicAccess(null, null, null, null, null);
```

`application-worker.yml`의 `public-access:` 블록을 교체:

```yaml
      public-access:
        # true 로 켜면 공개 서브도메인 라우팅 활성 (deploy_url=https://task-N{slug-suffix}.{base-domain}).
        # 기본 false = 인프라(Traefik + netis-deploy 네트워크) 없는 환경에서도 안전.
        # start-all.sh 는 인프라 존재를 감지해 자동으로 true 를 주입한다.
        enabled: ${DEPLOY_PUBLIC_ACCESS_ENABLED:false}
        base-domain: ${DEPLOY_PUBLIC_BASE_DOMAIN:micthebick.dev}
        network: netis-deploy
        # 같은 base-domain을 쓰는 다른 스택과 이름이 겹치지 않게 붙이는 접미사 (Windows 스택: -win).
        slug-suffix: ${DEPLOY_PUBLIC_SLUG_SUFFIX:}
        # 배포별 DNS 자동 등록. none(기본) = 와일드카드 DNS에 기댄다(맥 스택).
        # cloudflare = 배포 때 CNAME 생성, 중지/GC 때 삭제 (api-token: micthebick.dev Zone DNS Edit).
        dns:
          provider: ${DEPLOY_PUBLIC_DNS_PROVIDER:none}
          api-token: ${CLOUDFLARE_API_TOKEN:}
          zone-id: ${CLOUDFLARE_ZONE_ID:}
          tunnel-id: ${CLOUDFLARE_TUNNEL_ID:}
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorkerPropertiesDeployTest" --tests "com.hamonsoft.netismaker.workerdaemon.DeployReconcileJobTest"`
Expected: PASS (reconcile 테스트는 `Deploy`에 `null` publicAccess를 넘기므로 회귀 확인용).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java src/main/resources/application-worker.yml src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerPropertiesDeployTest.java
git commit -m "feat(worker): 공개 배포 슬러그 접미사·DNS 자동 등록 설정

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `PublicRoute` — 접미사 슬러그·호스트명·역파싱

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicRoute.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicRouteTest.java`

**Interfaces:**
- Produces (기존 3개 시그니처는 suffix `""`로 위임, 동작 불변):
  - `static String slug(long taskId, String suffix)` → `task-{id}{suffix}`
  - `static String hostname(long taskId, String baseDomain, String suffix)` → `{slug}.{baseDomain}`
  - `static String publicUrl(long taskId, String baseDomain, String suffix)` → `https://{hostname}`
  - `static List<String> dockerLabels(long taskId, int containerPort, String baseDomain, String suffix)`
  - `static java.util.OptionalLong taskIdOf(String hostname, String baseDomain, String suffix)`

- [ ] **Step 1: 실패하는 테스트 추가** — `PublicRouteTest.java` 클래스 끝(마지막 `}` 앞)에 추가, import에 `java.util.OptionalLong` 추가

```java
    @Test
    void suffix_is_appended_to_slug_host_and_url() {
        assertThat(PublicRoute.slug(7, "-win")).isEqualTo("task-7-win");
        assertThat(PublicRoute.hostname(7, "micthebick.dev", "-win")).isEqualTo("task-7-win.micthebick.dev");
        assertThat(PublicRoute.publicUrl(7, "micthebick.dev", "-win")).isEqualTo("https://task-7-win.micthebick.dev");
    }

    @Test
    void dockerLabels_with_suffix_use_suffixed_router_service_and_host() {
        assertThat(PublicRoute.dockerLabels(7, 8080, "micthebick.dev", "-win")).containsExactly(
                "traefik.enable=true",
                "traefik.http.routers.task-7-win.rule=Host(`task-7-win.micthebick.dev`)",
                "traefik.http.services.task-7-win.loadbalancer.server.port=8080");
    }

    @Test
    void empty_or_null_suffix_matches_legacy_output() {
        assertThat(PublicRoute.dockerLabels(7, 8080, "micthebick.dev", ""))
                .isEqualTo(PublicRoute.dockerLabels(7, 8080, "micthebick.dev"));
        assertThat(PublicRoute.publicUrl(7, "micthebick.dev", null)).isEqualTo("https://task-7.micthebick.dev");
    }

    @Test
    void taskIdOf_parses_only_matching_hostnames() {
        assertThat(PublicRoute.taskIdOf("task-42-win.micthebick.dev", "micthebick.dev", "-win"))
                .isEqualTo(OptionalLong.of(42));
        assertThat(PublicRoute.taskIdOf("TASK-42-WIN.micthebick.dev", "micthebick.dev", "-win"))
                .isEqualTo(OptionalLong.of(42));                                     // DNS는 대소문자 무시
        assertThat(PublicRoute.taskIdOf("task-42.micthebick.dev", "micthebick.dev", "-win")).isEmpty(); // 맥 스택 이름
        assertThat(PublicRoute.taskIdOf("app-win.micthebick.dev", "micthebick.dev", "-win")).isEmpty();
        assertThat(PublicRoute.taskIdOf("task-42-win.other.dev", "micthebick.dev", "-win")).isEmpty();
        assertThat(PublicRoute.taskIdOf("task-42-winx.micthebick.dev", "micthebick.dev", "-win")).isEmpty();
        assertThat(PublicRoute.taskIdOf(null, "micthebick.dev", "-win")).isEmpty();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.PublicRouteTest"`
Expected: 컴파일 실패 — `slug(long,String)`, `hostname`, `taskIdOf` 등 없음.

- [ ] **Step 3: 구현** — `PublicRoute.java` 전체 교체

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공개 배포 라우팅(Traefik) 슬러그·라벨·URL 생성. 순수 함수 — docker 불필요, 단위테스트 대상.
 *
 * 라우터/서비스/서브도메인은 모두 같은 슬러그 task-{id}{suffix}를 쓴다.
 * suffix는 같은 base-domain을 쓰는 다른 스택과의 이름 충돌 회피용(예 "-win"). 빈 값이면 기존과 동일.
 */
public final class PublicRoute {

    private PublicRoute() {}

    /** Traefik 라우터/서비스명 + 서브도메인 공용 슬러그. */
    public static String slug(long taskId) {
        return slug(taskId, "");
    }

    public static String slug(long taskId, String suffix) {
        return "task-" + taskId + (suffix == null ? "" : suffix);
    }

    /** 공개 호스트명 {slug}.{baseDomain} — DNS 레코드 이름이자 Traefik Host 규칙. */
    public static String hostname(long taskId, String baseDomain, String suffix) {
        return slug(taskId, suffix) + "." + baseDomain;
    }

    /** https 공개 URL. */
    public static String publicUrl(long taskId, String baseDomain) {
        return publicUrl(taskId, baseDomain, "");
    }

    public static String publicUrl(long taskId, String baseDomain, String suffix) {
        return "https://" + hostname(taskId, baseDomain, suffix);
    }

    /**
     * docker run 에 부착할 Traefik 라벨 3종 (key=value).
     * Host 규칙의 백틱은 ProcessRunner가 셸 미경유 exec라 리터럴 안전.
     */
    public static List<String> dockerLabels(long taskId, int containerPort, String baseDomain) {
        return dockerLabels(taskId, containerPort, baseDomain, "");
    }

    public static List<String> dockerLabels(long taskId, int containerPort, String baseDomain, String suffix) {
        String s = slug(taskId, suffix);
        return List.of(
                "traefik.enable=true",
                "traefik.http.routers." + s + ".rule=Host(`" + hostname(taskId, baseDomain, suffix) + "`)",
                "traefik.http.services." + s + ".loadbalancer.server.port=" + containerPort);
    }

    /**
     * 호스트명 → task id 역파싱. 형식(task-{숫자}{suffix}.{baseDomain})이 정확히 맞을 때만 값을 준다 —
     * DNS 고아 정리가 다른 레코드(auth-win, 맥 스택 task-N 등)를 건드리지 않게.
     */
    public static OptionalLong taskIdOf(String hostname, String baseDomain, String suffix) {
        if (hostname == null || baseDomain == null) return OptionalLong.empty();
        String sfx = suffix == null ? "" : suffix;
        Matcher m = Pattern.compile("task-(\\d{1,18})" + Pattern.quote(sfx) + "\\." + Pattern.quote(baseDomain))
                .matcher(hostname.trim().toLowerCase(Locale.ROOT));
        return m.matches() ? OptionalLong.of(Long.parseLong(m.group(1))) : OptionalLong.empty();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.PublicRouteTest"`
Expected: PASS (기존 4건 + 신규 4건).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicRoute.java src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicRouteTest.java
git commit -m "feat(worker): 공개 배포 슬러그 접미사와 호스트명 역파싱

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `LocalDockerTarget` — run 인자·URL에 접미사 반영

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java:78-94` (`buildRunArgs`), `:123-140` (deploy의 run 조립·echo), `:216-218` (URL)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTargetRunArgsTest.java`

**Interfaces:**
- Consumes: `PublicRoute.dockerLabels(long,int,String,String)`, `PublicRoute.publicUrl(long,String,String)` (Task 2), `PublicAccess.slugSuffix()` (Task 1)
- Produces: `static List<String> LocalDockerTarget.buildRunArgs(DeploySpec spec, int hostPort, boolean publicMode, String network, String baseDomain, String slugSuffix)`

- [ ] **Step 1: 테스트 수정·추가** — 기존 두 호출에 마지막 인자 `""` 추가, 새 테스트 추가

기존:
```java
        List<String> args = LocalDockerTarget.buildRunArgs(spec(), 19000, false, "netis-deploy", "micthebick.dev");
```
→
```java
        List<String> args = LocalDockerTarget.buildRunArgs(spec(), 19000, false, "netis-deploy", "micthebick.dev", "");
```
기존:
```java
        List<String> args = LocalDockerTarget.buildRunArgs(spec(), 19042, true, "netis-deploy", "micthebick.dev");
```
→
```java
        List<String> args = LocalDockerTarget.buildRunArgs(spec(), 19042, true, "netis-deploy", "micthebick.dev", "");
```
클래스 끝에 추가:
```java
    @Test
    void public_mode_with_suffix_uses_suffixed_slug_in_traefik_labels() {
        List<String> args = LocalDockerTarget.buildRunArgs(spec(), 19042, true, "netis-deploy", "micthebick.dev", "-win");
        assertThat(args).containsSequence("--network", "netis-deploy");
        assertThat(args).contains("traefik.http.routers.task-7-win.rule=Host(`task-7-win.micthebick.dev`)");
        assertThat(args).contains("traefik.http.services.task-7-win.loadbalancer.server.port=8080");
        assertThat(args).containsSequence("--name", "netis-task-7");   // 컨테이너 이름은 suffix와 무관
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.LocalDockerTargetRunArgsTest"`
Expected: 컴파일 실패 — 6인자 `buildRunArgs` 없음.

- [ ] **Step 3: 구현**

`buildRunArgs` 시그니처와 라벨 줄:
```java
    static List<String> buildRunArgs(DeployTarget.DeploySpec spec, int hostPort,
                                     boolean publicMode, String network, String baseDomain,
                                     String slugSuffix) {
```
```java
            for (String label : PublicRoute.dockerLabels(spec.taskId(), spec.containerPort(), baseDomain, slugSuffix)) {
```

`deploy()`의 4단계 조립부(`boolean publicMode = …`부터 `runEcho.append(' ').append(spec.imageName());`까지)를 교체:
```java
        boolean publicMode = cfg.publicAccess().enabled();
        String pubNetwork = cfg.publicAccess().network();
        String pubBaseDomain = cfg.publicAccess().baseDomain();
        String pubSuffix = cfg.publicAccess().slugSuffix();
        List<String> run = buildRunArgs(spec, hostPort, publicMode, pubNetwork, pubBaseDomain, pubSuffix);

        // run 명령 echo는 env 시크릿 값이 로그/SSE 스트림에 노출되지 않도록 -e 값을 마스킹한다.
        // (정책: 주입 env 값은 출력하지 않고 키만 노출 — UI 마스킹과 일관). 실행 커맨드 run은 실제 값 유지.
        StringBuilder runEcho = new StringBuilder(DOCKER + " run -d --name " + spec.containerName()
                + " -p " + hostPort + ":" + spec.containerPort());
        spec.labels().forEach((k, v) -> runEcho.append(" --label ").append(k).append('=').append(v));
        spec.env().forEach((k, v) -> runEcho.append(" -e ").append(k).append("=•••"));
        if (publicMode) {
            runEcho.append(" --network ").append(pubNetwork);
            for (String label : PublicRoute.dockerLabels(spec.taskId(), spec.containerPort(), pubBaseDomain, pubSuffix))
                runEcho.append(" --label ").append(label);
        }
        runEcho.append(' ').append(spec.imageName());
```

URL 생성부:
```java
        String url = publicMode
                ? PublicRoute.publicUrl(spec.taskId(), pubBaseDomain, pubSuffix)
                : "http://" + cfg.publicHost() + ":" + hostPort;
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.LocalDockerTargetRunArgsTest" --tests "com.hamonsoft.netismaker.workerdaemon.deploy.LocalDockerTargetRemoteDaemonTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTargetRunArgsTest.java
git commit -m "feat(worker): 배포 Traefik 라벨·공개 URL에 슬러그 접미사 반영

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `PublicDnsRegistrar` + `CloudflareDnsRegistrar`

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicDnsRegistrar.java`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/CloudflareDnsRegistrar.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/CloudflareDnsRegistrarTest.java`

**Interfaces:**
- Consumes: `WorkerProperties.Deploy.PublicAccess`, `PublicAccess.Dns` (Task 1)
- Produces:
  - `interface PublicDnsRegistrar { void upsert(String hostname) throws Exception; void delete(String hostname) throws Exception; java.util.Set<String> listOwned() throws Exception; }`
  - `public static CloudflareDnsRegistrar CloudflareDnsRegistrar.fromConfig(PublicAccess pa, String workerId)` — 검증 실패 시 `IllegalStateException`
  - 패키지 전용 `static CloudflareDnsRegistrar fromConfig(PublicAccess pa, String workerId, String apiBase)`, 생성자 `CloudflareDnsRegistrar(String apiBase, String token, String zoneId, String tunnelId, String workerId)`

- [ ] **Step 1: 실패하는 테스트 작성** — `CloudflareDnsRegistrarTest.java` 생성

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cloudflare DNS REST를 흉내 내는 인메모리 가짜 서버로 upsert/delete/listOwned와 토큰 비노출을 검증. */
class CloudflareDnsRegistrarTest {

    private static final String TOKEN = "cf-secret-TOKEN-123";
    private static final String PREFIX = "/client/v4/zones/zone-1/dns_records";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();          // "METHOD path?query"
    private final List<String> authHeaders = new ArrayList<>();
    private final Map<String, Map<String, Object>> records = new LinkedHashMap<>();   // id → record
    private int nextId = 1;
    private int failStatus = 0;                                   // 0 = 정상 응답

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String rawQuery = ex.getRequestURI().getRawQuery();
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                    + (rawQuery == null ? "" : "?" + rawQuery));
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            Object resp;
            if (failStatus != 0) {
                status = failStatus;
                resp = Map.of("success", false, "result", List.of(),
                        "errors", List.of(Map.of("code", 10000, "message", "Authentication error")));
            } else {
                resp = handle(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), query(rawQuery), body);
            }
            byte[] bytes = JSON.writeValueAsBytes(resp);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private Object handle(String method, String path, Map<String, String> q, String body) throws IOException {
        String id = path.length() > PREFIX.length() ? path.substring(PREFIX.length() + 1) : null;
        switch (method) {
            case "GET" -> {
                List<Map<String, Object>> hits = records.values().stream()
                        .filter(r -> !q.containsKey("name.exact") || q.get("name.exact").equals(r.get("name")))
                        .filter(r -> !q.containsKey("comment.exact") || q.get("comment.exact").equals(r.get("comment")))
                        .toList();
                int per = Integer.parseInt(q.getOrDefault("per_page", "100"));
                int page = Integer.parseInt(q.getOrDefault("page", "1"));
                int totalPages = Math.max(1, (hits.size() + per - 1) / per);
                List<Map<String, Object>> slice = hits.subList(
                        Math.min((page - 1) * per, hits.size()), Math.min(page * per, hits.size()));
                return Map.of("success", true, "errors", List.of(), "result", slice,
                        "result_info", Map.of("page", page, "per_page", per, "total_pages", totalPages));
            }
            case "POST" -> {
                Map<String, Object> r = new LinkedHashMap<>(JSON.readValue(body, Map.class));
                String nid = "rec-" + nextId++;
                r.put("id", nid);
                records.put(nid, r);
                return Map.of("success", true, "errors", List.of(), "result", r);
            }
            case "PUT" -> {
                Map<String, Object> r = new LinkedHashMap<>(JSON.readValue(body, Map.class));
                r.put("id", id);
                records.put(id, r);
                return Map.of("success", true, "errors", List.of(), "result", r);
            }
            case "DELETE" -> {
                records.remove(id);
                return Map.of("success", true, "errors", List.of(), "result", Map.of("id", id));
            }
            default -> throw new IllegalStateException(method);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void seed(String name, String content, String comment) {
        Map<String, Object> r = new LinkedHashMap<>();
        String id = "rec-" + nextId++;
        r.put("id", id);
        r.put("type", "CNAME");
        r.put("name", name);
        r.put("content", content);
        r.put("comment", comment);
        records.put(id, r);
    }

    private String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4";
    }

    private CloudflareDnsRegistrar registrar() {
        return new CloudflareDnsRegistrar(apiBase(), TOKEN, "zone-1", "tun-1", "win-worker-1");
    }

    @Test
    void upsert_creates_proxied_cname_to_tunnel_with_owner_comment() throws Exception {
        registrar().upsert("task-7-win.micthebick.dev");

        assertThat(records).hasSize(1);
        Map<String, Object> r = records.values().iterator().next();
        assertThat(r).containsEntry("type", "CNAME")
                .containsEntry("name", "task-7-win.micthebick.dev")
                .containsEntry("content", "tun-1.cfargotunnel.com")
                .containsEntry("proxied", true)
                .containsEntry("ttl", 1)
                .containsEntry("comment", "netis-maker:win-worker-1");
        assertThat(seen.get(0)).startsWith("GET " + PREFIX + "?").contains("name.exact=task-7-win.micthebick.dev");
        assertThat(seen.get(1)).isEqualTo("POST " + PREFIX);
        assertThat(authHeaders).containsOnly("Bearer " + TOKEN);
    }

    @Test
    void upsert_updates_existing_record_instead_of_duplicating() throws Exception {
        seed("task-7-win.micthebick.dev", "old.example.com", null);

        registrar().upsert("task-7-win.micthebick.dev");

        assertThat(records).hasSize(1);
        assertThat(records.get("rec-1")).containsEntry("content", "tun-1.cfargotunnel.com")
                .containsEntry("comment", "netis-maker:win-worker-1");
        assertThat(seen.get(seen.size() - 1)).isEqualTo("PUT " + PREFIX + "/rec-1");
    }

    @Test
    void delete_removes_record_and_is_noop_when_absent() throws Exception {
        seed("task-7-win.micthebick.dev", "tun-1.cfargotunnel.com", "netis-maker:win-worker-1");
        CloudflareDnsRegistrar reg = registrar();

        reg.delete("task-7-win.micthebick.dev");
        assertThat(records).isEmpty();
        assertThat(seen).contains("DELETE " + PREFIX + "/rec-1");

        seen.clear();
        reg.delete("task-7-win.micthebick.dev");
        assertThat(seen).hasSize(1).allMatch(s -> s.startsWith("GET "));
    }

    @Test
    void listOwned_returns_only_tagged_records_across_pages() throws Exception {
        for (int i = 1; i <= 150; i++) {
            seed("task-" + i + "-win.micthebick.dev", "tun-1.cfargotunnel.com", "netis-maker:win-worker-1");
        }
        seed("manual.micthebick.dev", "tun-1.cfargotunnel.com", null);
        seed("task-999-win.micthebick.dev", "tun-1.cfargotunnel.com", "netis-maker:other-worker");

        Set<String> owned = registrar().listOwned();

        assertThat(owned).hasSize(150)
                .contains("task-1-win.micthebick.dev", "task-150-win.micthebick.dev")
                .doesNotContain("manual.micthebick.dev", "task-999-win.micthebick.dev");
        assertThat(seen).anyMatch(s -> s.contains("page=2"));
        assertThat(seen).allMatch(s -> s.contains("comment.exact=netis-maker%3Awin-worker-1"));
    }

    @Test
    void api_failure_throws_with_cloudflare_message_but_never_the_token() {
        failStatus = 403;

        assertThatThrownBy(() -> registrar().upsert("task-7-win.micthebick.dev"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("Authentication error")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    }

    @Test
    void fromConfig_lists_every_missing_setting() {
        var pa = new WorkerProperties.Deploy.PublicAccess(true, null, null, "-win",
                new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", " ", null, ""));

        assertThatThrownBy(() -> CloudflareDnsRegistrar.fromConfig(pa, "win-worker-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOUDFLARE_API_TOKEN")
                .hasMessageContaining("CLOUDFLARE_ZONE_ID")
                .hasMessageContaining("CLOUDFLARE_TUNNEL_ID");
    }

    @Test
    void fromConfig_requires_public_access_enabled() {
        var pa = new WorkerProperties.Deploy.PublicAccess(false, null, null, "-win",
                new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", TOKEN, "zone-1", "tun-1"));

        assertThatThrownBy(() -> CloudflareDnsRegistrar.fromConfig(pa, "win-worker-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEPLOY_PUBLIC_ACCESS_ENABLED")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    }

    @Test
    void fromConfig_builds_a_working_registrar() throws Exception {
        var pa = new WorkerProperties.Deploy.PublicAccess(true, null, null, "-win",
                new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", TOKEN, "zone-1", "tun-1"));

        CloudflareDnsRegistrar.fromConfig(pa, "win-worker-1", apiBase()).upsert("task-8-win.micthebick.dev");

        assertThat(records.values()).extracting(r -> r.get("name")).containsExactly("task-8-win.micthebick.dev");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.CloudflareDnsRegistrarTest"`
Expected: 컴파일 실패 — `CloudflareDnsRegistrar` 없음.

- [ ] **Step 3: 구현**

`PublicDnsRegistrar.java`:
```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.Set;

/**
 * 공개 배포 호스트명의 DNS 레코드 수명주기. 구현: {@link CloudflareDnsRegistrar}.
 * 호출자는 {@link DnsRegisteringDeployTarget} 하나 — deploy 후 upsert, stop 후 delete, gc 때 listOwned로 고아 정리.
 */
public interface PublicDnsRegistrar {

    /** hostname 레코드를 이 워커의 터널로 만들거나 갱신한다 (멱등). */
    void upsert(String hostname) throws Exception;

    /** hostname 레코드 삭제. 없으면 no-op. */
    void delete(String hostname) throws Exception;

    /** 이 워커가 만든(소유 태그가 붙은) 레코드의 호스트명. */
    Set<String> listOwned() throws Exception;
}
```

`CloudflareDnsRegistrar.java`:
```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cloudflare DNS REST로 배포별 CNAME을 관리한다 (cloudflared CLI 의존 없음).
 *   {apiBase}/zones/{zoneId}/dns_records — CNAME {host} → {tunnelId}.cfargotunnel.com, proxied.
 * 개별 레코드는 같은 zone의 와일드카드(*.micthebick.dev → 맥 터널)보다 우선하므로 맥 스택과 공존한다.
 * comment에 netis-maker:{workerId}를 달아 우리가 만든 레코드만 listOwned로 식별한다.
 * 토큰은 Authorization 헤더에만 싣고 로그·예외 메시지에는 절대 넣지 않는다.
 */
@Slf4j
public class CloudflareDnsRegistrar implements PublicDnsRegistrar {

    static final String DEFAULT_API_BASE = "https://api.cloudflare.com/client/v4";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiBase;
    private final String token;
    private final String zoneId;
    private final String target;
    private final String ownerTag;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    CloudflareDnsRegistrar(String apiBase, String token, String zoneId, String tunnelId, String workerId) {
        this.apiBase = apiBase;
        this.token = token;
        this.zoneId = zoneId;
        this.target = tunnelId + ".cfargotunnel.com";
        this.ownerTag = "netis-maker:" + workerId;
    }

    /** 설정 검증 후 생성. 누락·모순은 IllegalStateException → 워커 기동 실패(배포 때가 아니라 부팅 때 드러냄). */
    public static CloudflareDnsRegistrar fromConfig(WorkerProperties.Deploy.PublicAccess pa, String workerId) {
        return fromConfig(pa, workerId, DEFAULT_API_BASE);
    }

    static CloudflareDnsRegistrar fromConfig(WorkerProperties.Deploy.PublicAccess pa, String workerId,
                                             String apiBase) {
        if (!pa.enabled()) {
            throw new IllegalStateException(
                    "DEPLOY_PUBLIC_DNS_PROVIDER=cloudflare는 DEPLOY_PUBLIC_ACCESS_ENABLED=true일 때만 쓸 수 있다");
        }
        WorkerProperties.Deploy.PublicAccess.Dns dns = pa.dns();
        List<String> missing = new ArrayList<>();
        if (blank(dns.apiToken())) missing.add("CLOUDFLARE_API_TOKEN");
        if (blank(dns.zoneId())) missing.add("CLOUDFLARE_ZONE_ID");
        if (blank(dns.tunnelId())) missing.add("CLOUDFLARE_TUNNEL_ID");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Cloudflare DNS 설정 누락: " + String.join(", ", missing));
        }
        return new CloudflareDnsRegistrar(apiBase, dns.apiToken().trim(), dns.zoneId().trim(),
                dns.tunnelId().trim(), workerId);
    }

    @Override
    public void upsert(String hostname) throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(recordBody(hostname));
        Optional<String> id = findId(hostname);
        if (id.isPresent()) {
            call("PUT", records() + "/" + id.get(), body);
        } else {
            call("POST", records(), body);
        }
        log.info("공개 주소 DNS upsert: {} → {}", hostname, target);
    }

    @Override
    public void delete(String hostname) throws IOException, InterruptedException {
        Optional<String> id = findId(hostname);
        if (id.isEmpty()) return;
        call("DELETE", records() + "/" + id.get(), null);
        log.info("공개 주소 DNS 삭제: {}", hostname);
    }

    @Override
    public Set<String> listOwned() throws IOException, InterruptedException {
        Set<String> out = new LinkedHashSet<>();
        int page = 1;
        int totalPages;
        do {
            JsonNode res = call("GET", records() + "?type=CNAME&per_page=100&page=" + page
                    + "&comment.exact=" + enc(ownerTag), null);
            for (JsonNode r : res.path("result")) out.add(r.path("name").asText());
            totalPages = res.path("result_info").path("total_pages").asInt(1);
            page++;
        } while (page <= totalPages);
        return out;
    }

    private Optional<String> findId(String hostname) throws IOException, InterruptedException {
        JsonNode arr = call("GET", records() + "?type=CNAME&name.exact=" + enc(hostname), null).path("result");
        return arr.isArray() && !arr.isEmpty() ? Optional.of(arr.get(0).path("id").asText()) : Optional.empty();
    }

    private Map<String, Object> recordBody(String hostname) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "CNAME");
        m.put("name", hostname);
        m.put("content", target);
        m.put("proxied", true);
        m.put("ttl", 1);
        m.put("comment", ownerTag);
        return m;
    }

    private String records() {
        return apiBase + "/zones/" + zoneId + "/dns_records";
    }

    private JsonNode call(String method, String url, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json;
        try {
            json = JSON.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
        } catch (IOException e) {
            json = JSON.createObjectNode();
        }
        if (res.statusCode() / 100 != 2 || !json.path("success").asBoolean(false)) {
            throw new IOException("Cloudflare API " + method + " 실패 (HTTP " + res.statusCode() + "): " + errors(json));
        }
        return json;
    }

    /** 응답 errors[]의 code/message만 — 요청(헤더·URL)은 싣지 않는다. */
    private static String errors(JsonNode json) {
        List<String> parts = new ArrayList<>();
        for (JsonNode e : json.path("errors")) {
            parts.add(e.path("code").asText() + " " + e.path("message").asText());
        }
        return parts.isEmpty() ? "(오류 메시지 없음)" : String.join("; ", parts);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.CloudflareDnsRegistrarTest"`
Expected: PASS (8건).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicDnsRegistrar.java src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/CloudflareDnsRegistrar.java src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/CloudflareDnsRegistrarTest.java
git commit -m "feat(worker): Cloudflare REST로 배포별 CNAME 생성·삭제·소유 목록

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `DnsRegisteringDeployTarget` 데코레이터

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DnsRegisteringDeployTarget.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/DnsRegisteringDeployTargetTest.java`

**Interfaces:**
- Consumes: `PublicDnsRegistrar` (Task 4), `PublicRoute.hostname/taskIdOf` (Task 2), `DockerGcPlanner.OWN_PREFIX` (`"netis-task-"`), `DeployFailedException(String message, String log)`
- Produces: `public DnsRegisteringDeployTarget(DeployTarget delegate, PublicDnsRegistrar dns, String baseDomain, String slugSuffix)` implements `DeployTarget`

- [ ] **Step 1: 실패하는 테스트 작성** — `DnsRegisteringDeployTargetTest.java` 생성

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DnsRegisteringDeployTargetTest {

    static class FakeTarget implements DeployTarget {
        final List<String> calls = new ArrayList<>();
        final Map<String, DeployStatus> statuses = new HashMap<>();
        boolean failDeploy;

        @Override
        public DeployResult deploy(DeploySpec spec, Consumer<String> logSink) throws Exception {
            calls.add("deploy " + spec.containerName());
            if (failDeploy) throw new DeployFailedException("docker build 실패 (exit=1)", "build log");
            return new DeployResult("https://task-7-win.micthebick.dev", "cid", 19000, spec.imageName(), "delegate log");
        }

        @Override
        public void stop(String containerName) {
            calls.add("stop " + containerName);
        }

        @Override
        public DeployStatus status(String containerName) {
            return statuses.getOrDefault(containerName, DeployStatus.STOPPED);
        }

        @Override
        public void gc(int orphanGraceMinutes, int keepImagesPerTask, Set<String> protectedContainers) {
            calls.add("gc");
        }
    }

    static class FakeDns implements PublicDnsRegistrar {
        final List<String> calls = new ArrayList<>();
        final Set<String> owned = new LinkedHashSet<>();
        Exception upsertError;
        Exception deleteError;
        Exception listError;

        @Override
        public void upsert(String hostname) throws Exception {
            calls.add("upsert " + hostname);
            if (upsertError != null) throw upsertError;
        }

        @Override
        public void delete(String hostname) throws Exception {
            calls.add("delete " + hostname);
            if (deleteError != null) throw deleteError;
        }

        @Override
        public Set<String> listOwned() throws Exception {
            if (listError != null) throw listError;
            return owned;
        }
    }

    private FakeTarget delegate;
    private FakeDns dns;
    private DnsRegisteringDeployTarget target;
    private final List<String> sink = new ArrayList<>();

    @BeforeEach
    void setUp() {
        delegate = new FakeTarget();
        dns = new FakeDns();
        target = new DnsRegisteringDeployTarget(delegate, dns, "micthebick.dev", "-win");
    }

    private static DeployTarget.DeploySpec spec() {
        return new DeployTarget.DeploySpec(7, Path.of("wt"), "netis-task-7:abc1234", "netis-task-7", 8080,
                Map.of(), Map.of("netis-maker.task", "7"));
    }

    @Test
    void deploy_registers_dns_after_container_is_up() throws Exception {
        DeployTarget.DeployResult r = target.deploy(spec(), sink::add);

        assertThat(delegate.calls).containsExactly("deploy netis-task-7");
        assertThat(dns.calls).containsExactly("upsert task-7-win.micthebick.dev");
        assertThat(r.url()).isEqualTo("https://task-7-win.micthebick.dev");
        assertThat(r.log()).contains("delegate log").contains("[공개 주소 DNS 등록: task-7-win.micthebick.dev]");
        assertThat(sink).contains("[공개 주소 DNS 등록: task-7-win.micthebick.dev]");
    }

    @Test
    void deploy_dns_failure_removes_container_and_fails_deploy() {
        dns.upsertError = new IOException("Cloudflare API POST 실패 (HTTP 403): 10000 Authentication error");

        assertThatThrownBy(() -> target.deploy(spec(), sink::add))
                .isInstanceOf(DeployFailedException.class)
                .hasMessageContaining("공개 주소 DNS 등록 실패")
                .hasMessageContaining("Authentication error")
                .satisfies(e -> assertThat(((DeployFailedException) e).getLog())
                        .contains("delegate log").contains("DNS 등록 실패"));
        assertThat(delegate.calls).containsExactly("deploy netis-task-7", "stop netis-task-7");
    }

    @Test
    void deploy_container_failure_skips_dns() {
        delegate.failDeploy = true;

        assertThatThrownBy(() -> target.deploy(spec(), sink::add))
                .isInstanceOf(DeployFailedException.class)
                .hasMessageContaining("docker build 실패");
        assertThat(dns.calls).isEmpty();
    }

    @Test
    void deploy_accepts_null_log_sink() throws Exception {
        assertThat(target.deploy(spec(), null).url()).isEqualTo("https://task-7-win.micthebick.dev");
    }

    @Test
    void stop_removes_container_then_dns_record() throws Exception {
        target.stop("netis-task-7");

        assertThat(delegate.calls).containsExactly("stop netis-task-7");
        assertThat(dns.calls).containsExactly("delete task-7-win.micthebick.dev");
    }

    @Test
    void stop_swallows_dns_delete_failure() throws Exception {
        dns.deleteError = new IOException("Cloudflare API DELETE 실패 (HTTP 500): (오류 메시지 없음)");

        target.stop("netis-task-7");   // 예외 없음 — 남은 레코드는 GC가 회수

        assertThat(delegate.calls).containsExactly("stop netis-task-7");
    }

    @Test
    void stop_skips_dns_for_foreign_container_names() throws Exception {
        target.stop("traefik");

        assertThat(dns.calls).isEmpty();
    }

    @Test
    void status_is_delegated() {
        delegate.statuses.put("netis-task-7", DeployTarget.DeployStatus.RUNNING);

        assertThat(target.status("netis-task-7")).isEqualTo(DeployTarget.DeployStatus.RUNNING);
    }

    @Test
    void gc_deletes_only_unprotected_records_whose_container_is_stopped() {
        dns.owned.addAll(List.of(
                "task-1-win.micthebick.dev",   // 보호(배포완료)
                "task-2-win.micthebick.dev",   // 비보호 + 컨테이너 없음 → 삭제
                "task-3-win.micthebick.dev",   // 비보호지만 RUNNING — 배포 직후 보고 전(경합) → 보존
                "task-5-win.micthebick.dev",   // 비보호지만 UNKNOWN(데몬 불통) → 보존(fail-closed)
                "task-4.micthebick.dev",       // 접미사 불일치(맥 스택 이름) → 무시
                "manual.micthebick.dev"));     // 형식 불일치 → 무시
        delegate.statuses.put("netis-task-3", DeployTarget.DeployStatus.RUNNING);
        delegate.statuses.put("netis-task-5", DeployTarget.DeployStatus.UNKNOWN);

        target.gc(60, 1, Set.of("netis-task-1"));

        assertThat(delegate.calls).containsExactly("gc");
        assertThat(dns.calls).containsExactly("delete task-2-win.micthebick.dev");
    }

    @Test
    void gc_skips_dns_sweep_when_listing_fails() {
        dns.listError = new IOException("Cloudflare API GET 실패 (HTTP 500): (오류 메시지 없음)");

        target.gc(60, 1, Set.of());

        assertThat(delegate.calls).containsExactly("gc");
        assertThat(dns.calls).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.DnsRegisteringDeployTargetTest"`
Expected: 컴파일 실패 — `DnsRegisteringDeployTarget` 없음.

- [ ] **Step 3: 구현** — `DnsRegisteringDeployTarget.java` 생성

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import lombok.extern.slf4j.Slf4j;

import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공개 배포 DNS 수명주기를 배포 타깃에 얹는 데코레이터 (provider=cloudflare일 때만 등록 — PublicDnsConfiguration).
 *
 *  deploy: 컨테이너가 헬스체크를 통과한 뒤 DNS upsert. 실패하면 컨테이너를 내리고 배포실패로 던진다 —
 *          열리지 않는 URL을 배포완료로 기록하지 않기 위해. upsert는 멱등이라 재배포로 복구된다.
 *  stop:   컨테이너 제거 뒤 DNS delete. 실패는 경고만(남은 레코드는 gc가 회수).
 *  gc:     컨테이너 GC 뒤, 이 워커가 만든 레코드 중 보호 목록에 없고 컨테이너가 STOPPED인 것만 삭제.
 *          RUNNING(배포 직후 보고 전 경합)·UNKNOWN(데몬 불통)은 보존한다.
 *
 * DeployService·DockerGcJob·DeployReconcileJob은 DeployTarget만 알므로 이 클래스를 몰라도 된다.
 */
@Slf4j
public class DnsRegisteringDeployTarget implements DeployTarget {

    private static final Pattern OWN_CONTAINER =
            Pattern.compile(Pattern.quote(DockerGcPlanner.OWN_PREFIX) + "(\\d{1,18})");

    private final DeployTarget delegate;
    private final PublicDnsRegistrar dns;
    private final String baseDomain;
    private final String slugSuffix;

    public DnsRegisteringDeployTarget(DeployTarget delegate, PublicDnsRegistrar dns,
                                      String baseDomain, String slugSuffix) {
        this.delegate = delegate;
        this.dns = dns;
        this.baseDomain = baseDomain;
        this.slugSuffix = slugSuffix;
    }

    @Override
    public DeployResult deploy(DeploySpec spec, Consumer<String> logSink) throws Exception {
        Consumer<String> sink = logSink == null ? (s -> {}) : logSink;
        DeployResult r = delegate.deploy(spec, logSink);
        String baseLog = r.log() == null ? "" : r.log();
        String host = PublicRoute.hostname(spec.taskId(), baseDomain, slugSuffix);
        try {
            dns.upsert(host);
        } catch (Exception e) {
            String line = "[공개 주소 DNS 등록 실패: " + host + " — " + e.getMessage() + "]";
            sink.accept(line);
            try {
                delegate.stop(spec.containerName());
            } catch (Exception stopErr) {
                log.warn("DNS 등록 실패 후 컨테이너 정리 실패 ({}): {}", spec.containerName(), stopErr.getMessage());
            }
            throw new DeployFailedException("공개 주소 DNS 등록 실패: " + e.getMessage(),
                    baseLog + "\n" + line + "\n");
        }
        String line = "[공개 주소 DNS 등록: " + host + "]";
        sink.accept(line);
        return new DeployResult(r.url(), r.containerId(), r.hostPort(), r.image(), baseLog + "\n" + line + "\n");
    }

    @Override
    public void stop(String containerName) throws Exception {
        delegate.stop(containerName);
        OptionalLong id = taskIdOfContainer(containerName);
        if (id.isEmpty()) return;
        String host = PublicRoute.hostname(id.getAsLong(), baseDomain, slugSuffix);
        try {
            dns.delete(host);
        } catch (Exception e) {
            log.warn("공개 주소 DNS 삭제 실패 (다음 GC에서 재시도): {} — {}", host, e.getMessage());
        }
    }

    @Override
    public DeployStatus status(String containerName) {
        return delegate.status(containerName);
    }

    @Override
    public void gc(int orphanGraceMinutes, int keepImagesPerTask, Set<String> protectedContainers) {
        delegate.gc(orphanGraceMinutes, keepImagesPerTask, protectedContainers);
        Set<String> owned;
        try {
            owned = dns.listOwned();
        } catch (Exception e) {
            log.warn("공개 주소 DNS 목록 조회 실패 — 이번 주기 DNS 정리 스킵: {}", e.getMessage());
            return;
        }
        int removed = 0;
        for (String host : owned) {
            OptionalLong id = PublicRoute.taskIdOf(host, baseDomain, slugSuffix);
            if (id.isEmpty()) continue;
            String container = DockerGcPlanner.OWN_PREFIX + id.getAsLong();
            if (protectedContainers.contains(container)) continue;
            if (delegate.status(container) != DeployStatus.STOPPED) continue;
            try {
                dns.delete(host);
                removed++;
            } catch (Exception e) {
                log.warn("GC 공개 주소 DNS 삭제 실패: {} — {}", host, e.getMessage());
            }
        }
        if (removed > 0) log.info("GC: 공개 주소 DNS 레코드 {}개 제거", removed);
    }

    private static OptionalLong taskIdOfContainer(String containerName) {
        if (containerName == null) return OptionalLong.empty();
        Matcher m = OWN_CONTAINER.matcher(containerName);
        return m.matches() ? OptionalLong.of(Long.parseLong(m.group(1))) : OptionalLong.empty();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.DnsRegisteringDeployTargetTest"`
Expected: PASS (10건).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DnsRegisteringDeployTarget.java src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/DnsRegisteringDeployTargetTest.java
git commit -m "feat(worker): 배포·중지·GC에 공개 주소 DNS 수명주기를 얹는 데코레이터

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 빈 구성 — `PublicDnsConfiguration`

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicDnsConfiguration.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicDnsConfigurationTest.java`

**Interfaces:**
- Consumes: `LocalDockerTarget(WorkerProperties)` 빈, `WorkerProperties` 빈, `CloudflareDnsRegistrar.fromConfig(PublicAccess, String)`, `DnsRegisteringDeployTarget(...)`
- Produces: `provider=cloudflare`일 때 `@Primary DeployTarget` 빈 이름 `dnsRegisteringDeployTarget`

- [ ] **Step 1: 실패하는 테스트 작성** — `PublicDnsConfigurationTest.java` 생성

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** provider 값에 따라 DeployTarget 주입처가 받는 빈이 바뀌는지 — 맥 스택(none) 회귀 가드 포함. */
class PublicDnsConfigurationTest {

    private static WorkerProperties props(WorkerProperties.Deploy.PublicAccess.Dns dns) {
        var pa = new WorkerProperties.Deploy.PublicAccess(true, "micthebick.dev", null, "-win", dns);
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, pa);
        return new WorkerProperties("win-worker-1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, deploy);
    }

    private ApplicationContextRunner runner(WorkerProperties props) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("worker"))
                .withBean(WorkerProperties.class, () -> props)
                .withBean(LocalDockerTarget.class, () -> new LocalDockerTarget(props))
                .withUserConfiguration(PublicDnsConfiguration.class);
    }

    @Test
    void provider_none_keeps_local_docker_target() {
        runner(props(new WorkerProperties.Deploy.PublicAccess.Dns("none", null, null, null)))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(DeployTarget.class)).isInstanceOf(LocalDockerTarget.class);
                });
    }

    @Test
    void provider_cloudflare_wraps_target_with_dns_decorator() {
        runner(props(new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", "tok", "zone-1", "tun-1")))
                .withPropertyValues("netis-maker.worker.deploy.public-access.dns.provider=cloudflare")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(DeployTarget.class)).isInstanceOf(DnsRegisteringDeployTarget.class);
                });
    }

    @Test
    void provider_cloudflare_with_missing_settings_fails_startup() {
        runner(props(new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", null, null, null)))
                .withPropertyValues("netis-maker.worker.deploy.public-access.dns.provider=cloudflare")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("CLOUDFLARE_API_TOKEN");
                });
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.PublicDnsConfigurationTest"`
Expected: 컴파일 실패 — `PublicDnsConfiguration` 없음.

- [ ] **Step 3: 구현** — `PublicDnsConfiguration.java` 생성

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 배포별 DNS 자동 등록(provider=cloudflare)일 때만 LocalDockerTarget을 DNS 데코레이터로 감싸
 * DeployTarget 주입처(DeployService·DockerGcJob·DeployReconcileJob)에 @Primary로 공급한다.
 * provider=none(기본, 맥 스택)이면 이 빈이 없어 기존 LocalDockerTarget이 그대로 주입된다.
 * 설정 누락은 fromConfig가 예외 → 워커 기동 실패.
 */
@Configuration
@Profile("worker")
public class PublicDnsConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "netis-maker.worker.deploy.public-access.dns", name = "provider",
            havingValue = "cloudflare")
    DeployTarget dnsRegisteringDeployTarget(LocalDockerTarget local, WorkerProperties props) {
        WorkerProperties.Deploy.PublicAccess pa = props.deploy().publicAccess();
        return new DnsRegisteringDeployTarget(local, CloudflareDnsRegistrar.fromConfig(pa, props.id()),
                pa.baseDomain(), pa.slugSuffix());
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.PublicDnsConfigurationTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerDaemonConstructorContractTest"`
Expected: PASS (계약 테스트: 새 `@Configuration`은 단일 생성자라 위반 없음).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicDnsConfiguration.java src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicDnsConfigurationTest.java
git commit -m "feat(worker): provider=cloudflare일 때 DNS 데코레이터를 배포 타깃으로 주입

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 문서 + 전체 회귀

**Files:**
- Modify: `CLAUDE.md` — "원격 Docker 배포 호스트 (`DOCKER_HOST`)" 섹션 마지막 줄

- [ ] **Step 1: CLAUDE.md 수정** — 다음 줄을

```markdown
- 공개 라우팅(`task-N.micthebick.dev`, Traefik)은 이 구성에서 미지원 — 결과는 사내망 `http://<host>:<port>`로만 접근.
```

아래 블록으로 교체:

```markdown
- **공개 주소(`https://task-N-win.micthebick.dev`, 2026-09-23, 스펙 `docs/superpowers/specs/2026-09-23-remote-public-deploy-dns-design.md`)**: 맥 스택이 와일드카드 `*.micthebick.dev`를 쓰므로 Windows 스택은 **배포마다 개별 CNAME을 Cloudflare API로 생성**(개별 레코드가 와일드카드보다 우선)하고 중지·GC 때 지운다. 경로: Cloudflare → 이 PC 터널 `netismaker` ingress `*.micthebick.dev → http://10.1.1.75:18080` → 원격 Traefik(`netis-deploy`) → 컨테이너. `public.env`: `DEPLOY_PUBLIC_ACCESS_ENABLED=true`, `DEPLOY_PUBLIC_SLUG_SUFFIX=-win`, `DEPLOY_PUBLIC_DNS_PROVIDER=cloudflare`, `CLOUDFLARE_API_TOKEN`(micthebick.dev Zone DNS Edit), `CLOUDFLARE_ZONE_ID`, `CLOUDFLARE_TUNNEL_ID`. 코드: `deploy/DnsRegisteringDeployTarget`(provider=cloudflare일 때 `PublicDnsConfiguration`이 `@Primary`로 LocalDockerTarget을 감쌈) + `deploy/CloudflareDnsRegistrar`. DNS 등록 실패 = 배포실패(컨테이너 정리), 삭제 실패 = 경고(GC가 `comment=netis-maker:<WORKER_ID>` 태그 레코드 중 보호 목록 밖 + 컨테이너 STOPPED만 회수). readiness는 여전히 `http://{DEPLOY_PUBLIC_HOST}:{port}`. 원격 Traefik 기동: `DOCKER_HOST=… MSYS_NO_PATHCONV=1 ./scripts/start-traefik.sh`(Git Bash 경로 변환 방지), 원격 방화벽 18080 인바운드(10.1.3.2만).
```

- [ ] **Step 2: 워커 전체 테스트 실행**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.*"`
Expected: 전부 PASS (이 PC엔 Docker Desktop이 없어 Testcontainers 테스트는 skip — 실패 아님).

- [ ] **Step 3: 전체 빌드**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew build`
Expected: BUILD SUCCESSFUL. 실패가 있으면 이번 변경과 무관한지 `git stash` 없이 `main`과 비교해 판별하고 보고.

- [ ] **Step 4: 커밋**

```bash
git add CLAUDE.md
git commit -m "docs: 원격 Docker 배포 공개 주소(배포별 DNS) 운영 안내

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 라이브 적용 + 스모크 (⚠️ 단계마다 사용자 승인)

라이브 터널·원격 PC·DNS를 건드린다. **각 Step 실행 전 사용자에게 무엇을 할지 말하고 승인을 받는다.** 스택 재기동은 도구 셸에서 `start-public.ps1`을 직접 돌리지 말고 반드시 `Start-ScheduledTask -TaskName 'netisMaker-public'`.

- [ ] **Step 1: (사용자 직접) 원격 PC 방화벽** — 10.1.1.75 관리자 PowerShell에서 사용자가 실행:

```powershell
New-NetFirewallRule -DisplayName 'netisMaker Traefik 18080' -Direction Inbound -Protocol TCP -LocalPort 18080 -RemoteAddress 10.1.3.2 -Action Allow
```

- [ ] **Step 2: 원격 Traefik 기동** (Git Bash)

```bash
cd /c/Users/mic/NetisMaker/commanCenter
DOCKER_HOST=ssh://Administrator@10.1.1.75 MSYS_NO_PATHCONV=1 ./scripts/start-traefik.sh
DOCKER_HOST=ssh://Administrator@10.1.1.75 docker ps --filter name=traefik --format '{{.Names}} {{.Status}} {{.Ports}}'
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: nope.micthebick.dev' http://10.1.1.75:18080/
```
Expected: traefik `Up`, `0.0.0.0:18080->80/tcp`; curl `404`(Traefik이 미지 Host에 404 = 도달 OK). 타임아웃이면 Step 1 방화벽 확인.

- [ ] **Step 3: Cloudflare 토큰·Zone ID 준비 (사용자)** — 대시보드 → My Profile → API Tokens → "Edit zone DNS" 템플릿 → Zone Resources: micthebick.dev만. Zone ID는 micthebick.dev 개요 우측. 사용자가 `%USERPROFILE%\netis-maker\public.env` 끝에 직접 추가(토큰을 채팅에 붙이지 않게 안내):

```
# 원격 배포 공개 주소 (배포별 DNS) - 2026-09-23
DEPLOY_PUBLIC_ACCESS_ENABLED=true
DEPLOY_PUBLIC_SLUG_SUFFIX=-win
DEPLOY_PUBLIC_DNS_PROVIDER=cloudflare
CLOUDFLARE_API_TOKEN=<사용자 입력>
CLOUDFLARE_ZONE_ID=<사용자 입력>
CLOUDFLARE_TUNNEL_ID=ea6f0697-17a1-45a4-b47c-92c6b22f5a77
```
확인은 키만: `sed 's/=.*//' ~/netis-maker/public.env | tail -8`.

- [ ] **Step 4: cloudflared 인그레스 추가** — `C:\Users\mic\.cloudflared\config.yml`의 `- service: http_status:404` 바로 위에 삽입:

```yaml
  # netisMaker 원격 배포 (10.1.1.75 Traefik). 배포별 CNAME만 이 터널로 온다 — 맥 와일드카드와 무관.
  - hostname: "*.micthebick.dev"
    service: http://10.1.1.75:18080
```
검증: `/c/Users/mic/bin/cloudflared tunnel ingress validate` → `OK`. `/c/Users/mic/bin/cloudflared tunnel ingress rule https://task-1-win.micthebick.dev` → `http://10.1.1.75:18080` 매칭, `https://app-win.micthebick.dev` → `:3001` 그대로.

- [ ] **Step 5: cloudflared + 워커 재기동** (PowerShell, 각각 별도 호출 — `Remove-Item`과 `taskkill`을 한 호출에 섞지 말 것)

```powershell
Get-Content C:\Users\mic\NetisMaker\commanCenter\.run\cloudflared.pid; Get-Content C:\Users\mic\NetisMaker\commanCenter\.run\worker.pid
```
```powershell
taskkill /PID <cloudflared pid> /T /F; taskkill /PID <worker pid> /T /F
```
```powershell
Remove-Item C:\Users\mic\NetisMaker\commanCenter\.run\cloudflared.pid, C:\Users\mic\NetisMaker\commanCenter\.run\worker.pid
```
```powershell
Start-ScheduledTask -TaskName 'netisMaker-public'
```
대기 후 `.run\autostart.log`에 `[ok] cloudflared`·`[ok] worker ready`, `.run\worker.log`에 `배포 docker 데몬: 원격 DOCKER_HOST 사용` 확인. `[skip] ... already running`이 뜨면 pid 재사용 오판 — 실제 프로세스 명령줄 확인 후 pid 파일 삭제·재실행. `https://auth-win.micthebick.dev`·`https://app-win.micthebick.dev`가 다시 200인지 확인.

- [ ] **Step 6: 스모크 — 배포**
  - 사용자에게 UI(app-win)에서 작업 1(현재 원격에 `netis-task-1` 있음)을 **재배포**해 달라고 요청(또는 사용자가 지정한 작업).
  - 배포 로그에 `[공개 주소 DNS 등록: task-1-win.micthebick.dev]`, deploy_url `https://task-1-win.micthebick.dev`.
  - 외부 도달: `curl -s -o /dev/null -w '%{http_code}\n' https://task-1-win.micthebick.dev/` → `200`(앱 응답 코드). 가능하면 사용자에게 휴대폰 LTE로도 열어 달라고 요청.
  - 맥 스택 무영향: `nslookup task-1.micthebick.dev`가 여전히 맥 터널(와일드카드)로 해석되는지.

- [ ] **Step 7: 스모크 — 중지** — 사용자가 UI에서 배포 중지 → `worker.log`에 `공개 주소 DNS 삭제: task-1-win.micthebick.dev`, 잠시 뒤 `nslookup task-1-win.micthebick.dev`가 와일드카드(맥 터널)로 떨어지거나 해당 레코드가 사라졌는지 확인. 필요하면 재배포로 원상 복구.

- [ ] **Step 8: 결과 기록** — 성공/실패와 실제 출력(HTTP 코드, 로그 줄)을 사용자에게 그대로 보고. 메모리 `windows-worker-pc-environment.md`의 원격 배포 항목에 공개 주소 구성을 한 줄 추가.
