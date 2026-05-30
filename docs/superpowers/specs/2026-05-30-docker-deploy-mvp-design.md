# netisMaker — Docker 배포 (MVP, 워커 로컬) 설계

- 작성일: 2026-05-30
- 상태: 승인됨 (구현 플랜 작성 전 단계)
- 범위: PR생성 이후 단계로 "Docker 배포"를 추가. 워커 로컬 Docker 데몬에 빌드·실행. 나중에 별도 배포 호스트로 확장 가능한 추상화 포함.

## 1. 배경 / 문제

현재 파이프라인은 `PR_CREATED("PR생성")`에서 종료된다 (`TaskStatus.java`). PR을 만든 뒤 그 결과물을
실제로 빌드·실행해 확인할 수단이 없다. 운영자가 PR 미리보기 환경을 워커 머신에서 즉시 띄울 수 있게 한다.

핵심 사실: 이 환경의 다중 워커(mac-worker-1/2)는 **같은 macOS = 같은 Docker 데몬**을 공유한다.
따라서 어느 워커가 배포/중지 작업을 claim하든 동일한 Docker를 보므로 컨테이너 stop/replace가 일관되게
동작한다. (멀티 머신 환경이라면 배포 작업을 특정 워커에 pin하거나 원격 타깃을 써야 한다 — 원격 타깃 단계에서 해결.)

## 2. 결정 사항 (확정)

| 결정 | 선택 |
|---|---|
| 배포 트리거 | admin이 "배포" 버튼 클릭 (approve와 동일한 명시적 게이트) |
| Dockerfile 없을 때 | repo 루트 Dockerfile 우선, 없으면 `claude -p`로 프로젝트 타입 감지 후 생성 |
| 포트 | 지정 범위(`19000-19099`)에서 자동 할당 |
| 호스트 추상화 | `DeployTarget` 인터페이스 + `LocalDockerTarget` 1개 구현 (원격은 나중에 같은 인터페이스로) |

## 3. 상태머신 확장

```
PR_CREATED ──(admin "배포")──→ 배포대기 → (워커 claim) → 배포중 ──┬─→ 배포완료
                                                                  └─→ 배포실패
배포완료/배포실패 ──(admin "재배포")──→ 배포대기   (기존 컨테이너 stop+rm 후 교체)
배포완료/배포실패 ──(admin "중지")──→ 배포중지대기 → (워커) docker stop+rm → PR_CREATED
```

`TaskStatus` enum에 추가 (DB값은 한글):

| enum | dbValue |
|---|---|
| `DEPLOY_PENDING` | `배포대기` |
| `DEPLOYING` | `배포중` |
| `DEPLOYED` | `배포완료` |
| `DEPLOY_FAILED` | `배포실패` |
| `UNDEPLOY_PENDING` | `배포중지대기` |

상태 전이 규칙(서버 검증):
- `PR_CREATED → DEPLOY_PENDING`: admin `/deploy`
- `DEPLOYED|DEPLOY_FAILED → DEPLOY_PENDING`: admin `/redeploy`
- `DEPLOYED|DEPLOY_FAILED → UNDEPLOY_PENDING`: admin `/undeploy`
- `DEPLOY_PENDING → DEPLOYING`: 워커 claim (kind=DEPLOY)
- `UNDEPLOY_PENDING → DEPLOYING`: 워커 claim (kind=UNDEPLOY) — in-flight 상태는 DEPLOY와 공유, kind로 구분
- `DEPLOYING → DEPLOYED | DEPLOY_FAILED`: DEPLOY 결과 보고
- `DEPLOYING → PR_CREATED | DEPLOY_FAILED`: UNDEPLOY 결과 보고 (성공 시 PR_CREATED 복귀)

즉 in-flight 상태는 `DEPLOYING` 하나로 통일하고 작업 종류는 `kind`(DEPLOY/UNDEPLOY)로 구분한다.
`recordResult`는 현재 상태가 `DEPLOYING`일 때 `DEPLOYED`/`DEPLOY_FAILED`/`PR_CREATED`를 허용한다.

## 4. 데이터 모델 (Flyway `V7__deploy_pipeline.sql`)

`com.task`에 컬럼 추가 (모두 nullable, 기존 row 안전):

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `deploy_url` | VARCHAR(500) | 접속 URL 예: `http://<worker-host>:<port>` |
| `deploy_container_id` | VARCHAR(100) | docker 컨테이너 ID(또는 이름 `netis-task-{id}`) |
| `deploy_host_port` | INT | 할당된 호스트 포트 |
| `deploy_image` | VARCHAR(200) | 빌드된 이미지 태그 `netis-task-{id}:{shortsha}` |
| `deployed_at` | TIMESTAMPTZ | 배포완료 시각 |
| `deploy_log` | TEXT | build/run stdout tail (실패 디버깅) |

`com.task_queue_stats` 뷰: 배포 카운터 추가 (`deploy_pending`, `deploying`, `deployed`, `deploy_failed`).
`CREATE OR REPLACE VIEW`는 컬럼 변경을 거부하므로 **DROP + CREATE** (V6 패턴 그대로). `avg_duration_ms`
계산은 V6 그대로 유지.

## 5. 워커 흐름 (`WorkerMainLoop`)

`WorkerTaskResponse.Kind`에 `DEPLOY`, `UNDEPLOY` 추가. `pollAndProcess`에서 kind 분기 확장.

### 5.1 DEPLOY
1. **체크아웃**: PR head 브랜치(`task.headBranch`)를 worktree로 체크아웃 (`deploy-{id}`).
   - 기존 `WorktreeService`/`GitRepoCache` 재사용. base가 아니라 head 브랜치 기준.
2. **Dockerfile 결정**:
   - worktree 루트에 `Dockerfile` 있으면 사용.
   - 없으면 `claude -p`로 프로젝트 타입 감지 후 worktree에 `Dockerfile` 생성. (권한 우회 모드, worktree 격리)
3. **`DeployTarget.deploy(spec)`** 호출 → `DeployResult{url, containerId, hostPort, image}`.
4. **결과 보고**: `DEPLOYED`(메타 동봉) 또는 `DEPLOY_FAILED`(failureReason + deployLog).

### 5.2 UNDEPLOY
1. `DeployTarget.stop("netis-task-{id}")` (stop + rm, 없으면 무시).
2. 결과 보고: `PR_CREATED` 복귀 + deploy_* 컬럼 클리어.

예외/타임아웃은 분석/구현과 동일한 `safePost...Failure` 패턴으로 `DEPLOY_FAILED` 보고.

## 6. 호스트 추상화 (`DeployTarget`)

```java
public interface DeployTarget {
    DeployResult deploy(DeploySpec spec) throws Exception; // build + run
    void          stop(String containerName) throws Exception; // stop + rm (idempotent)
    DeployStatus  status(String containerName); // RUNNING/STOPPED/UNKNOWN (MVP: 보고용)
}

public record DeploySpec(
        java.nio.file.Path contextDir,  // worktree (Dockerfile 포함)
        String imageName,                // netis-task-{id}:{shortsha}
        String containerName,            // netis-task-{id}
        int    hostPort,
        int    containerPort,
        java.util.Map<String,String> env,
        java.util.Map<String,String> labels
) {}

public record DeployResult(String url, String containerId, int hostPort, String image) {}
public enum DeployStatus { RUNNING, STOPPED, UNKNOWN }
```

- **`LocalDockerTarget`** (MVP, 유일 구현):
  - `docker build -t {imageName} {contextDir}`
  - 기존 `containerName` 있으면 `docker rm -f` (교체)
  - `docker run -d --name {containerName} -p {hostPort}:{containerPort} --label netis-maker.task={id} {env...} {imageName}`
  - url = `http://{deploy.public-host}:{hostPort}` (`public-host` 설정, 기본 `localhost`)
- **확장(미구현)**: `RemoteSshDockerTarget`(scp context → 원격 docker), `RegistryDeployTarget`(registry push → 원격 pull/run).
  설정 `netis-maker.worker.deploy.target=local|remote-ssh`로 Spring이 주입 선택. `WorkerMainLoop`은
  docker를 직접 호출하지 않고 주입된 `DeployTarget`만 사용 → 워커 로직 변경 없이 전환.

## 7. 포트 할당

- 설정 `netis-maker.worker.deploy.port-range: 19000-19099`.
- `LocalDockerTarget`: 범위 내에서 (a) docker 게시 포트 점유 + (b) OS bind 테스트로 빈 포트 탐색, 첫 번째 채택.
- 컨테이너 내부 포트: worktree Dockerfile `EXPOSE` 파싱, 없으면 설정 기본값 `netis-maker.worker.deploy.default-container-port: 8080`.
- 채택 포트는 `task.deploy_host_port`에 저장.

## 8. API (api 프로파일)

| 엔드포인트 | 권한 | 전이 |
|---|---|---|
| `POST /tasks/{id}/deploy` | admin | PR_CREATED → 배포대기 |
| `POST /tasks/{id}/redeploy` | admin | 배포완료/배포실패 → 배포대기 |
| `POST /tasks/{id}/undeploy` | admin | 배포완료/배포실패 → 배포중지대기 |

- approve와 동일하게 `TaskController`/`TaskService`에 추가. 상태 검증은 서버에서.
- 워커 claim: `findClaimableForUpdateSkipLocked` 쿼리에 `배포대기`, `배포중지대기` 추가.
  claim 시 `배포대기 → 배포중`(kind=DEPLOY), `배포중지대기 → 배포중`(kind=UNDEPLOY).
  `WorkerService.claimNextTask`와 `recordResult`에 배포 단계 검증/저장 분기 추가.
- 워커 결과 DTO `WorkerResultRequest`에 배포 메타 필드 추가(deployUrl/containerId/hostPort/image/deployLog) 또는
  기존 필드 재사용 — 플랜에서 확정.

## 9. 프론트엔드 (`frontend/pages/tasks/[id].vue`)

- PR생성 상태 task: **"배포" 버튼** 노출.
- 배포완료 상태: **접속 URL 링크**(새 탭) + **"재배포"** / **"중지"** 버튼 + 포트/이미지/배포시각 표시.
- 배포실패 상태: 사유 + deploy_log 펼침 + "재배포" 버튼.
- `QueueStatsBar.vue`: 배포 카운터 카드 4개 추가 (배포대기/배포중/배포완료/배포실패).

## 10. 설정 (`application-worker.yml`)

```yaml
netis-maker:
  worker:
    deploy:
      target: local                 # local | remote-ssh (미구현)
      port-range: 19000-19099
      default-container-port: 8080
      public-host: localhost        # url 조립용 (워커 머신의 외부 접근 호스트)
      build-timeout: 600s
```

## 11. 스코프 (MVP 경계)

**포함**: 배포/재배포/중지, Dockerfile 자동생성, 포트 자동할당, DeployTarget 추상화 + LocalDocker,
배포 메타 저장, 프론트 버튼/URL/카운터.

**제외 (원격 호스트 단계로)**: 헬스체크 폴링, 컨테이너 로그 스트리밍, HTTPS/도메인 라우팅,
이미지 registry push, 멀티 머신 컨테이너 추적(공유 Docker 데몬 가정), 자동 만료/GC.

## 12. 컴포넌트 경계 요약

| 컴포넌트 | 책임 | 의존 |
|---|---|---|
| `TaskStatus` (enum) | 배포 상태 5개 추가 | — |
| `V7__deploy_pipeline.sql` | task 컬럼 + 뷰 카운터 | V6 |
| `DeployTarget` (interface) | 호스트 비종속 배포 계약 | — |
| `LocalDockerTarget` | 로컬 docker build/run/stop | ProcessRunner, deploy 설정 |
| `DeployService` (워커) | Dockerfile 결정 + 포트 할당 + DeployTarget 호출 오케스트레이션 | WorktreeService, DeployTarget, ClaudeExecAdapter |
| `WorkerMainLoop` | kind=DEPLOY/UNDEPLOY 분기 | DeployService |
| `TaskService`/`TaskController` (api) | deploy/redeploy/undeploy 전이 | TaskRepository |
| `WorkerService` (api) | claim/result 배포 분기 | repositories |
| `tasks/[id].vue`, `QueueStatsBar.vue` | 버튼/URL/카운터 UI | API |
