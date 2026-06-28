# 공개 배포 주소 — 설계 (Public Deploy Address)

- 작성일: 2026-06-28
- 상태: 설계 확정 (구현 플랜 대기)
- 브랜치: `feat/public-deploy-address`
- 관련: 선행 Feature 1(레포 한글 별칭 카탈로그) `2026-06-28-repo-alias-catalog-design.md`, 인프라 `~/.cloudflared/config.yml`(named tunnel `netis`, auth/app.micthebick.dev 노출)

## 1. 배경 & 목표

배포 파이프라인(`DeployService` → `LocalDockerTarget`)은 완성된 작업의 앱을 도커 컨테이너로 띄우고 **`http://{publicHost}:{hostPort}`** URL을 만든다(`LocalDockerTarget.java:143`, 기본 `publicHost=localhost`, 포트 19000–19099). 이 URL은 운영자 macOS 로컬에서만 열리고, 외부에서 데모를 공유할 수 없다.

**목표**: 배포된 앱을 외부에서 바로 열 수 있는 **공개 주소**로 노출한다. 예) `https://task-7.micthebick.dev`. 기존 Cloudflare Tunnel(auth/app.micthebick.dev) 인프라를 확장해 **per-deploy 서브도메인**으로 라우팅한다.

## 2. 범위 & 비목표

**범위**: 일회성 공개 인프라(와일드카드 DNS + cloudflared ingress + 로컬 Traefik 리버스 프록시) + 배포 시 컨테이너에 Traefik 라우팅 라벨 부착 + 배포 URL을 공개 https 주소로 전환 + 운영 스크립트/상태 점검.

**비목표 (YAGNI)**:
- **접근 제어**. 배포 앱은 **완전 공개**(URL 아는 누구나 접속). CF Access/OAuth 게이팅은 비목표 — 데모/공유 용도.
- **추측 불가 슬러그**. 슬러그는 `task-{id}` 그대로(추측 가능). 열거 차단은 비목표.
- **2단계 서브도메인**(`*.deploy.micthebick.dev`). 무료 Universal SSL 미커버(ACM 유료)라 채택 안 함. → §3 D2.
- **경로 기반 라우팅**(`deploy.micthebick.dev/t/7/`). 임의 앱이 서브패스에서 절대경로 에셋이 깨지므로 채택 안 함. → §3 D2.
- **host-port 발행 제거 / PortAllocator 폐지**. 로컬 직접 접근 유지 + 변경폭 최소화를 위해 `-p` 발행은 **유지**(향후 단순화 여지로만 남김). → §3 D4.
- **다중 도메인/멀티 워커 라우팅 조정**. 단일 운영자 macOS 1대 전제 그대로.

## 3. 핵심 결정 (브레인스토밍 합의)

| # | 결정 | 근거 |
|---|---|---|
| D1 | 노출 = **완전 공개** (CF Access/OAuth 없음) | 데모 공유 목적, 가장 단순 |
| D2 | URL = **1단계 서브도메인 `task-{id}.micthebick.dev`** | 무료 `*.micthebick.dev` Universal SSL 커버(2단계는 ACM 유료); 앱이 루트(`/`)에서 동작 → 임의 AI 생성 앱 호환 |
| D3 | 라우팅 = **Traefik (Docker provider + 라벨)** + 공유 네트워크 `netis-deploy` | 라벨 자동 디스커버리 → undeploy/GC 시 라우트 자동 정리(상태 동기화 버그 없음). 이미 쓰는 `netis-maker.task` 라벨 패턴의 자연스러운 확장 |
| D4 | host-port `-p` 발행 **유지** | 로컬 `localhost:{hostPort}` 직접 접근 + PortAllocator 무변경 → 저위험 증분 |
| D5 | 와일드카드 `*.micthebick.dev` ingress는 cloudflared에서 auth/app **명시 규칙 아래**, catch-all 404 **위에** 배치 | 명시 규칙이 먼저 매칭 → auth/app 무영향 |

### Cloudflare Universal SSL 근거 (D2)
무료 Universal SSL은 **apex + 1단계 와일드카드 `*.micthebick.dev`만** 커버한다. `task-7.micthebick.dev`는 1단계라 무료 커버. `task-7.deploy.micthebick.dev`(2단계)는 미커버 → Advanced Certificate Manager(유료) 필요. 출처: developers.cloudflare.com/ssl/edge-certificates/universal-ssl/limitations/

## 4. 아키텍처 & 데이터 흐름

```
브라우저 → https://task-7.micthebick.dev
  → Cloudflare edge        (TLS 종단, *.micthebick.dev Universal SSL 무료)
  → cloudflared 터널        (ingress: *.micthebick.dev → http://localhost:18080)
  → Traefik (:18080)        (router rule Host(`task-7.micthebick.dev`) → service task-7)
  → docker net netis-deploy → 컨테이너 netis-task-7 : {EXPOSE port}
```

- TLS는 Cloudflare edge가 종단. CF→cloudflared 구간은 터널 암호화. Traefik↔컨테이너는 로컬 도커 네트워크 평문 HTTP(문제 없음).
- cloudflared는 원본 Host 헤더를 그대로 Traefik에 전달 → Traefik이 Host로 라우팅. 배포 앱은 `Host: task-7.micthebick.dev`를 받음(대부분 앱 무관).
- auth.micthebick.dev / app.micthebick.dev는 ingress 상단 명시 규칙으로 먼저 매칭 → 와일드카드 영향 없음.

## 5. 일회성 인프라 (라이브 터널·DNS 변경 — 구현 시 사용자 승인 후 실행)

> ⚠️ 이 절은 **라이브 공개 터널과 DNS를 건드린다.** cloudflared 재기동은 auth/app 터널을 순간 끊는다. 구현 단계에서 각 단계를 사용자 승인 후 실행한다.

1. **공유 도커 네트워크**: `docker network create netis-deploy` (멱등).
2. **Traefik 상시 기동** (도커 컨테이너):
   - `--providers.docker --providers.docker.exposedByDefault=false`
   - web entrypoint `:18080` (호스트 바인드), 도커 소켓 마운트(`/var/run/docker.sock:ro`), 네트워크 `netis-deploy` 합류.
   - `scripts/start-traefik.sh` 로 기동, `scripts/stop-all.sh`/`status.sh`에 편입.
3. **cloudflared ingress 추가** (`~/.cloudflared/config.yml`): catch-all 404 **위에**, auth/app **아래**에 →
   ```yaml
   - hostname: "*.micthebick.dev"
     service: http://localhost:18080
   ```
   cloudflared 1회 재기동(LaunchAgent 재시작). `cloudflared tunnel ingress validate` 로 사전 검증.
4. **와일드카드 DNS**: `*.micthebick.dev` proxied CNAME → `<tunnel-id>.cfargotunnel.com` (현재 tunnel id `d1418766-bfc3-4179-a29b-79b2e1d63319`).
   - `cloudflared tunnel route dns`가 와일드카드를 거부하면 Cloudflare 대시보드/ API로 생성.

→ 위를 묶는 `scripts/setup-public-deploy.sh`(1회 실행, 네트워크 생성 + Traefik 기동 + ingress 스니펫·DNS 안내 출력). 멱등하게 작성해 재실행 안전.

## 6. 코드 변경 (netisMaker)

### 6.1 설정 — `WorkerProperties.Deploy` (`workerdaemon/WorkerProperties.java:35-69`)
공개 모드 설정 추가 (기존 `publicHost`/`portRange`/`defaultContainerPort` 유지):
```
deploy.public.enabled         (default false)   # 공개 모드 토글
deploy.public.base-domain      (default micthebick.dev)
deploy.public.network          (default netis-deploy)
```
`application-worker.yml`에 기본값 추가. 공개 모드 off면 현행 동작(`http://localhost:{hostPort}`) 그대로.

### 6.2 라우팅 헬퍼 — 신규 `workerdaemon/deploy/PublicRoute.java` (순수·테스트 가능)
- `static List<String> dockerLabels(long taskId, int containerPort, String baseDomain)` →
  - `traefik.enable=true`
  - `traefik.http.routers.task-{id}.rule=Host(`task-{id}.{baseDomain}`)`
  - `traefik.http.services.task-{id}.loadbalancer.server.port={containerPort}`
- `static String publicUrl(long taskId, String baseDomain)` → `https://task-{id}.{baseDomain}`
- `static String routerName(long taskId)` → `task-{id}` (DNS/Traefik-safe; 라우터·서비스·서브도메인 동일 슬러그)

### 6.3 배포 실행 — `LocalDockerTarget.deploy()` (`deploy/LocalDockerTarget.java:41-147`)
공개 모드일 때 `docker run`에 추가(기존 `-p {hostPort}:{containerPort}` + `netis-maker.task` 라벨 그대로):
- `--network {deploy.public.network}`
- `PublicRoute.dockerLabels(...)` 각각 `--label` 로 전개
- **URL 생성(`:143`)**: 공개 모드 → `PublicRoute.publicUrl(taskId, baseDomain)`, 아니면 현행 `http://{publicHost}:{hostPort}`.
- 백틱(`)이 들어간 Host 라벨 값은 `ProcessRunner`가 셸 미경유 exec(arg 배열)이라 리터럴로 안전 전달.

### 6.4 영향 없음(무변경)
- **DB/스키마**: `com.task.deploy_url`(VARCHAR 500)에 공개 https URL 저장. 신규 컬럼 없음. `deploy_host_port`는 로컬용으로 계속 저장·표시.
- **프론트**: `tasks/[id].vue:459`의 deployUrl 링크가 그대로 공개 주소를 가리킴. 변경 없음(원하면 "공개 주소" 라벨 문구만 미세 조정 — 선택).
- **undeploy/redeploy/GC**: §7 참조 — 코드 변경 없음.
- **PortAllocator / Health check**: 무변경(health check는 로컬 `localhost:{hostPort}`로 계속).

## 7. 라이프사이클

- **deploy**: 컨테이너가 `netis-deploy` 네트워크에 Traefik 라벨과 함께 뜸 → Traefik이 도커 이벤트로 자동 라우트 생성. 추가 등록 호출 없음.
- **undeploy**: 기존 `docker rm -f netis-task-{id}` → 컨테이너·라벨 소멸 → **Traefik 라우트 자동 제거. 코드/리로드 0.**
- **redeploy**: old `rm -f` + new run(동일 슬러그 라벨) → Traefik 라우트 갱신.
- **GC** (`DockerGcJob` / `LocalDockerTarget.gc`): `netis-maker.task` 라벨 기준 그대로. 죽은 컨테이너 라우트는 Traefik이 자동 소멸.

## 8. 에러 처리 & 엣지케이스

| 상황 | 동작 |
|---|---|
| Traefik 다운 | 공개 URL만 502. 로컬 `localhost:{hostPort}` 접근·배포 파이프라인은 정상. `status.sh`가 Traefik LISTEN/health 감시 |
| cloudflared 재기동 중 | auth/app 포함 모든 터널 순간 끊김(수초) — 일회성 인프라 셋업 시에만 발생 |
| 앱이 `0.0.0.0` 미바인드 / EXPOSE 누락 | 기존과 동일(EXPOSE 파싱·`defaultContainerPort` 폴백 재사용). Traefik service port = 파싱된 컨테이너 포트 |
| 공개 모드 off (로컬 개발) | `deploy.public.enabled=false` → 현행 `http://localhost:{hostPort}` 그대로. 회귀 없음 |
| 같은 task 재배포 동시성 | task당 컨테이너 1개(`netis-task-{id}`), 라우터/서비스명도 task별 유일 → 충돌 없음 |

## 9. 테스트 전략

- **단위** (로컬 green 목표):
  - `PublicRouteTest`: 라벨 3종 정확성(Host 규칙·service port·enable), 슬러그 DNS-safe, https URL 형식.
  - `LocalDockerTarget` 인자 생성: 공개 모드 on → `--network`/Traefik 라벨/공개 URL 포함, off → 현행 인자/로컬 URL(기존 테스트 회귀 없음). 도커 미실행 환경에서도 인자 빌더만 검증하도록 분리.
- **통합/E2E** (CI 게이팅 또는 수동): Traefik+터널 거친 실제 도달성 → 도커·터널 필요라 `RUN_TESTCONTAINERS` 패턴으로 CI 한정, 또는 구현 후 외부에서 `https://task-N.micthebick.dev` 수동 스모크.

## 10. 구현 순서(요약, 상세는 플랜에서)

1. `PublicRoute` 헬퍼 + 단위테스트 (TDD).
2. `WorkerProperties.Deploy.public` 설정 + `application-worker.yml` 기본값.
3. `LocalDockerTarget.deploy()` 공개 모드 분기(라벨/네트워크/URL) + 인자 빌더 테스트.
4. 인프라 스크립트(`setup-public-deploy.sh`, `start-traefik.sh`) + `status.sh`/`stop-all.sh` 편입.
5. (승인 후) 라이브 인프라 적용: 네트워크·Traefik·cloudflared ingress·와일드카드 DNS.
6. 수동 스모크: 배포 → 외부에서 공개 URL 접속 확인.
