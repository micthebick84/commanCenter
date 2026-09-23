# 원격 Docker 배포의 공개 주소 (배포별 DNS 자동 등록) — 설계

- 작성일: 2026-09-23
- 상태: 설계 확정 (구현 플랜 대기)
- 브랜치: `feature/remote-public-deploy-dns` (← `feature/remote-docker-deploy` PR #51 위. #51 머지 후 main으로 rebase)
- 관련: `2026-06-28-public-deploy-address-design.md`(맥 스택 공개 주소, Traefik 라벨 방식), CLAUDE.md "원격 Docker 배포 호스트"

## 1. 배경 & 목표

Windows 워커(10.1.3.2)는 배포를 원격 PC 10.1.1.75의 Docker 데몬(`DOCKER_HOST=ssh://…`)에서 돌린다. 지금 배포 URL은 `http://10.1.1.75:{port}` — 사내망에서만 열린다.

맥 스택의 공개 주소(`https://task-N.micthebick.dev`)는 **와일드카드 DNS `*.micthebick.dev` → 맥 터널(`netis`)** 에 기대고 있고 맥 스택은 **계속 사용 중**이다. 그래서 Windows 스택은 와일드카드를 쓸 수 없다.

**목표**: Windows 스택 배포를 외부에서 `https://task-{id}-win.micthebick.dev`로 연다. 배포마다 **개별 CNAME을 Cloudflare API로 생성**하고, 배포를 내리거나 GC할 때 삭제한다. 맥 스택 동작은 바꾸지 않는다.

## 2. 범위 & 비목표

**범위**: 슬러그 접미사, base-domain env 오버라이드, Cloudflare DNS 레코드 생성/삭제/고아 정리, 원격 PC의 Traefik, 이 PC cloudflared 인그레스 1줄, `public.env` 키.

**비목표**
- 접근 제어(CF Access 등) — 기존 설계와 동일하게 완전 공개.
- 2단계 서브도메인(`*.win.micthebick.dev`) — 무료 Universal SSL 미커버(ACM 유료).
- 맥 스택 이관/와일드카드 이동.
- `-p` 호스트 포트 발행 제거 — readiness가 계속 `http://{publicHost}:{hostPort}`를 쓴다.
- cloudflared CLI(`tunnel route dns`) 사용 — 삭제가 안 되므로 REST 한 가지로 통일.

## 3. 핵심 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | 주소 = `task-{id}{suffix}.{baseDomain}`, Windows는 suffix `-win` | 1단계 서브도메인 → 무료 인증서. 맥·Windows DB가 달라 같은 task id가 양쪽에 생길 수 있음 → 접미사로 충돌 회피 |
| D2 | 배포마다 **개별 proxied CNAME** → `<tunnelId>.cfargotunnel.com` | Cloudflare는 개별 레코드를 와일드카드보다 우선 → 맥 스택 무영향 |
| D3 | DNS는 **Cloudflare REST API + 토큰**(Zone DNS Edit, micthebick.dev 한정) | 생성·삭제를 한 방식으로. cloudflared CLI 의존 없음 |
| D4 | 레코드 `comment`에 `netis-maker:<WORKER_ID>` 태그 | 우리가 만든 레코드만 식별 → 고아 정리가 다른 레코드를 건드리지 않음 |
| D5 | DNS 등록 실패 = **배포실패** (컨테이너 정리) | 열리지 않는 URL을 성공으로 기록하지 않는다. upsert는 멱등 → 재배포로 복구 |
| D6 | DNS 삭제 실패 = 경고만, GC가 회수 | undeploy는 컨테이너 중지가 본질. 남은 레코드는 Traefik 404일 뿐 |
| D7 | provider 기본 `none` | 맥 스택/로컬 개발은 설정 변경 없이 현행 그대로 |

## 4. 요청 흐름

```
브라우저 → https://task-7-win.micthebick.dev
  → Cloudflare 엣지 (TLS 종단, *.micthebick.dev Universal SSL)
  → DNS: CNAME task-7-win → ea6f0697-17a1-45a4-b47c-92c6b22f5a77.cfargotunnel.com (proxied)
  → 이 PC cloudflared (터널 netismaker) ingress "*.micthebick.dev" → http://10.1.1.75:18080
  → 10.1.1.75 Traefik (:18080 → :80) Host(`task-7-win.micthebick.dev`) → service
  → docker net netis-deploy → 컨테이너 netis-task-7 : {EXPOSE port}
```

- 이 터널에는 DNS가 이 터널을 가리키는 호스트만 도착한다 → 인그레스를 와일드카드로 둬도 맥 트래픽이 섞이지 않는다.
- 인그레스 순서: `auth-win` → `app-win` → `*.micthebick.dev` → `http_status:404`.

## 5. 코드 변경

### 5.1 설정 — `WorkerProperties.Deploy.PublicAccess`

```
public-access:
  enabled:      ${DEPLOY_PUBLIC_ACCESS_ENABLED:false}      # 기존
  base-domain:  ${DEPLOY_PUBLIC_BASE_DOMAIN:micthebick.dev} # 하드코딩 → env
  network:      netis-deploy                                # 기존
  slug-suffix:  ${DEPLOY_PUBLIC_SLUG_SUFFIX:}               # 신규, 기본 빈 값
  dns:
    provider:   ${DEPLOY_PUBLIC_DNS_PROVIDER:none}          # none | cloudflare
    api-token:  ${CLOUDFLARE_API_TOKEN:}
    zone-id:    ${CLOUDFLARE_ZONE_ID:}
    tunnel-id:  ${CLOUDFLARE_TUNNEL_ID:}
```

- `slug-suffix`는 DNS 라벨로 안전해야 한다: `^(-[a-z0-9]+)*$` 외에는 기동 실패(설정 오류를 배포 때가 아니라 부팅 때 드러냄).
- `provider=cloudflare`인데 token/zone/tunnel 중 하나라도 비었거나 `enabled=false`면 기동 실패.
- `provider`는 `none|cloudflare`만 허용한다 — 그 외 값(오타 포함)은 조용히 `none`으로 묵살하지 않고 기동 실패로 드러낸다.
- `provider=cloudflare`면 `slug-suffix`가 비어 있어도 기동 실패 — 접미사 없이는 같은 zone의 다른 스택(맥 와일드카드)과 이름이 겹친다.
- `base-domain`은 defaulting 뒤 소문자로 정규화한다 — 호스트명을 소문자로 비교하는 `taskIdOf`와 어긋나지 않게.

### 5.2 `PublicRoute`

- `slug(taskId, suffix)` → `task-{id}{suffix}`. `publicUrl`, `dockerLabels`, 호스트명(`hostname(taskId, baseDomain, suffix)`)이 같은 슬러그를 쓴다. 역파싱은 `taskIdOf(hostname, baseDomain, suffix)`.
- 기존 시그니처(suffix 없음)는 suffix `""`로 위임 — 맥 스택 결과 동일.

### 5.3 신규 `workerdaemon/deploy/PublicDnsRegistrar`

```java
interface PublicDnsRegistrar {
    void upsert(String hostname) throws Exception;   // 멱등
    void delete(String hostname) throws Exception;   // 없으면 no-op
    Set<String> listOwned() throws Exception;        // comment 태그가 붙은 호스트명
}
```

- 구현은 `CloudflareDnsRegistrar` 하나. provider `none`이면 registrar 빈 자체를 만들지 않는다(§5.4 데코레이터 미등록 → 현행 경로 그대로).
- `CloudflareDnsRegistrar` — `java.net.http.HttpClient`, `Authorization: Bearer <token>`.
  - upsert: `GET /zones/{zone}/dns_records?type=CNAME&name.exact={host}` → 없으면 `POST`, 있으면 `PUT` (content=`{tunnelId}.cfargotunnel.com`, proxied=true, ttl=1, comment=`netis-maker:{workerId}`). 서버가 `name.exact` 필터를 무시/오해석해도 괜찮게 클라이언트가 응답의 `name`을 다시 대조한다(delete도 동일한 조회를 재사용).
  - listOwned: `GET …?type=CNAME&comment.exact=netis-maker:{workerId}&per_page=100` 페이지 순회 — 여기서도 서버 필터를 신뢰하지 않고 응답의 `comment`을 클라이언트에서 재대조한다.
  - 응답 `success=false` / 비 2xx → 예외. 메시지에 **토큰을 넣지 않는다**(요청 헤더는 절대 로그 금지, 응답 본문 errors[].message만).
  - base URL은 생성자 주입(기본 `https://api.cloudflare.com/client/v4`) — 테스트에서 가짜 서버로 교체.
- 필수값 검증은 `CloudflareDnsRegistrar.fromConfig(publicAccess, workerId)` 정적 팩토리에서(§5.1 규칙) — 빈 생성 시 예외 → 워커 기동 실패.

### 5.4 연결 지점 — `DeployTarget` 데코레이터

`DeployService`·`DockerGcJob`·`DeployReconcileJob`은 **바꾸지 않는다.** 대신 신규 `deploy/DnsRegisteringDeployTarget implements DeployTarget`가 `LocalDockerTarget`을 감싼다. `PublicDnsConfiguration`(`@Configuration @Profile("worker")`)이 `provider=cloudflare`일 때만 `@Primary` 빈으로 등록 → `DeployTarget` 주입처가 전부 데코레이터를 받는다.

- **deploy**: `delegate.deploy()` 성공 후 `registrar.upsert(hostname)`. 실패 시 `delegate.stop(containerName)`(실패 무시) 후 `DeployFailedException("공개 주소 DNS 등록 실패: …", 결과 로그 + 실패 줄)` → `DeployService`가 기존대로 배포실패 처리. 성공 시 결과 로그 끝에 `[공개 주소 DNS 등록: task-7-win.micthebick.dev]` 한 줄, logSink에도 같은 줄.
- **stop**: `delegate.stop()` 후 `registrar.delete(hostname)`; DNS 예외는 `log.warn`만. 컨테이너 이름 `netis-task-{id}`에서 id 추출(패턴 불일치면 DNS 단계 생략).
- **status**: 그대로 위임.
- **gc**: `delegate.gc()` 후 `listOwned()` 중 보호 목록(`netis-task-{id}` 컨테이너 이름 집합)에 없고 **컨테이너 상태가 `STOPPED`인** 호스트명만 삭제. `RUNNING`(배포 직후 DB 보고 전 — GC 스레드와의 경합)·`UNKNOWN`(데몬 불통)은 보존. `listOwned()` 실패 시 이번 주기 DNS 정리만 스킵. 보호 목록 조회 실패 시는 `DockerGcJob`이 기존처럼 gc 자체를 호출하지 않는다(fail-closed).
  - 호스트명 → task id 판정은 `PublicRoute.taskIdOf(hostname, baseDomain, suffix)` 역파싱(`task-(\d+){suffix}.{baseDomain}`) — 패턴 불일치 레코드는 건드리지 않는다.

### 5.4.1 알려진 위험

`CLOUDFLARE_API_TOKEN`은 `public.env` → 스택 전 프로세스 env로 상속되어, 워커/인터뷰 서비스가 띄우는 claude 세션도 볼 수 있다. 기존 `GITLAB_TOKEN`·`SPRING_DATASOURCE_PASSWORD`와 같은 수준의 노출이며 토큰 범위를 micthebick.dev Zone DNS Edit로 한정해 피해 반경을 줄인다. 자식 프로세스 env 스크럽은 이 작업 범위 밖.

### 5.5 영향 없음

DB/스키마, 프론트(deployUrl 링크 그대로), PortAllocator, readiness, LocalDockerTarget의 run 인자(라벨이 suffix 슬러그를 쓰는 것 외).

## 6. 인프라 (라이브 변경 — 단계별 사용자 승인 후 실행)

1. **10.1.1.75 Traefik**: 워커 env의 `DOCKER_HOST`로 `MSYS_NO_PATHCONV=1 ./scripts/start-traefik.sh` (네트워크 `netis-deploy` 생성 + `traefik:v3.7` :18080). Docker Desktop(Windows)에서 `/var/run/docker.sock` 마운트 동작 확인.
2. **10.1.1.75 방화벽**: 인바운드 TCP 18080, 원격 주소 10.1.3.2만.
3. **cloudflared** (`~/.cloudflared/config.yml`): 404 캐치올 위에 `- hostname: "*.micthebick.dev"` / `service: http://10.1.1.75:18080` → `cloudflared tunnel ingress validate` → 재기동(auth-win/app-win 수 초 끊김).
4. **`~/netis-maker/public.env`**: `DEPLOY_PUBLIC_ACCESS_ENABLED=true`, `DEPLOY_PUBLIC_SLUG_SUFFIX=-win`, `DEPLOY_PUBLIC_DNS_PROVIDER=cloudflare`, `CLOUDFLARE_API_TOKEN`(사용자 발급: "Edit zone DNS" 템플릿, micthebick.dev 한정), `CLOUDFLARE_ZONE_ID`, `CLOUDFLARE_TUNNEL_ID=ea6f0697-17a1-45a4-b47c-92c6b22f5a77` → 워커만 재기동.

## 7. 에러 처리 & 엣지케이스

| 상황 | 동작 |
|---|---|
| DNS upsert 실패(토큰 만료·권한·네트워크) | 컨테이너 정리 + 배포실패, 사유에 Cloudflare 오류 메시지(토큰 없음) |
| 같은 task 재배포 | upsert가 기존 레코드 PUT → 멱등 |
| undeploy 중 DNS 삭제 실패 | 경고 로그, 배포중지는 성공. 다음 GC가 회수 |
| 워커가 꺼져 있는 동안 컨테이너 소멸 | reconcile이 배포중단됨 처리(기존). 레코드는 보호 목록에 남아 유지 — 재배포 시 재사용 |
| Traefik/원격 PC 다운 | 공개 URL 502/530. 사내 `http://10.1.1.75:{port}` 경로는 무관 |
| 수동으로 만든 레코드 | comment 태그가 없으면 listOwned에 안 잡힘 → GC 대상 아님 |
| 맥 스택 | provider 기본 none, suffix 빈 값 → 현행과 바이트 단위 동일한 라벨/URL |

## 8. 테스트

- `PublicRouteTest`: suffix 유/무 슬러그·Host 라벨·URL, 역파싱(일치/불일치), 기존 케이스 회귀 없음.
- `WorkerProperties` 검증: 잘못된 suffix, cloudflare 필수값 누락 → 예외.
- `CloudflareDnsRegistrarTest`: JDK `HttpServer` 가짜 API로 생성/갱신 분기, 삭제(있음/없음), 페이지 순회 listOwned, `success=false` 예외 메시지에 토큰 미포함.
- `DnsRegisteringDeployTargetTest`(가짜 delegate·registrar): upsert 실패 → delegate.stop + DeployFailedException; stop 시 delete, delete 예외 삼킴; gc가 보호되지 않은 레코드만 삭제, listOwned 실패 시 스킵.
- `PublicDnsConfigurationTest`(`ApplicationContextRunner`): provider none → `DeployTarget`=LocalDockerTarget, cloudflare → 데코레이터.
- 라이브 스모크: 재배포 → 외부망에서 `https://task-N-win.micthebick.dev` 200 → 배포중지 → 레코드 삭제 확인.
