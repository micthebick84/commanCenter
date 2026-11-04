# 종료 산출물 납품 파일(zip 등) 보관 설계

- 날짜: 2026-11-04
- 상태: 사용자 검토 대기. 사용자가 질문 없이 진행을 골라("그냥 진행해") 결정은 모두 작성자 판단이다 — §14에 모아 두었다. 빌더 관점 검토 1회 반영(초안의 "Symphony가 폴더를 tar.gz로 묶기"를 뺐다, §3 기각 대안)
- 범위: netisMaker 워커 데몬·API·프론트. 인터뷰 서비스와 DB 스키마 변경 없음. 강원도교육청 대시보드 레포(`product/netis/web/dashboard/netisdash_kangwonedu`)에 Dockerfile 추가(별도 MR)
- 근거: 종료 산출물 1차 설계 `2026-10-14-close-artifact-delivery-design.md`, 미리 빌드 설계 `2026-10-28-deploy-prebuild-design.md`, 2026-11-04 운영 기록(작업 29 첫 보관 성공, 경북대·강원 요청)

## 1. 문제

종료 산출물 기능(PR #89)은 2026-11-04 처음 켜졌고 작업 29(서울출입국외국인청 대시보드)에서 `netisdash_immigration-task-29-58a2e5c.war`(240MB)를 보관했다. 그런데 기능이 **배포 이미지 안 WAR·JAR 파일 하나**만 다룬다.

같은 날 사용자가 두 레포의 산출물 내려받기를 요청했는데, 둘 다 이 모양에 맞지 않는다.

| 레포 | 실제 납품물 | 지금 보관되는 것 |
|---|---|---|
| 경북대 생활관 대시보드(카탈로그 12, `netisdash_knu`) | 캠퍼스별 war **두 개** — `hm-dash-daegu.war`, `hm-dash-sangju.war`(작업 28 브랜치부터, README "캠퍼스별 빌드"). 두 war는 `js/dash/knu/knu.campus.conf.js` 한 파일만 다르다 | 배포 이미지의 `ROOT.war` = 대구 하나. 시험 배포는 컨테이너 하나라 한 캠퍼스만 띄운다 |
| 강원도교육청 대시보드(카탈로그 11, `netisdash_kangwonedu`) | **현장 설치 묶음** `netis-dash-deploy-<커밋>.zip`(레포 `deploy/INSTALL.md` §2·§4·§5): 풀면 `netis-dash-deploy-<커밋>/` 안에 Node 22 리눅스 런타임 tar.xz, `DASH_BASE_PATH=/dash`로 빌드한 Next standalone tar.gz, `install.sh`(실행 권한 필요)·`uninstall.sh`·`conf/`·`sql/`·`BASE_PATH`·`SHA256SUMS`. 현장 절차는 `unzip netis-dash-deploy-<커밋>.zip && cd … && ./install.sh`이고 서버에 `unzip`이 있어야 한다(§2) | 없음 — Next.js 레포라 WAR·JAR가 없다. 시험 배포 이미지는 루트 경로(`basePath` 없음) 빌드라 현장 묶음과 서로 바꿔 쓸 수 없다 |

강원 묶음은 리눅스에서 만드는 것이 권장이다(INSTALL §5 — Windows 빌드는 sharp Windows 바이너리와 `.next/node_modules`의 Windows junction 때문에 `pack_standalone.py`가 필요한데, 이 워커 PC의 `python`은 Store 스텁이라 실행되지 않는다). zip으로 줄 때는 `install.sh` 실행 권한(755)이 유닉스 속성으로 들어가야 한다(§5 마지막 줄).

## 2. 목표·비목표·피해야 할 것

목표
- 산출물이 **zip·tar.gz 같은 납품 파일**이어도 지금과 같은 화면·1회용 주소로 내려받는다.
- 경북대: 종료 때 대구·상주 war 두 개가 든 zip을 받는다.
- 강원: 종료 때 INSTALL §4 그대로 `unzip` → `./install.sh` 할 수 있는 현장 묶음 zip(실행 권한 보존)을 받는다.
- 단일 WAR·JAR 레포(작업 29 방식)는 설정·동작·파일 이름이 지금과 같다.

비목표
- 메일 발송(지금 꺼져 있음 — `MAIL_*` 미설정, `close_mail_status=DISABLED`). 이 설계와 무관하게 나중에 켠다.
- 강원 시험 배포의 로그인(10.1.4.8 NETIS 7.0의 시험용 SSO 클라이언트 `dash-test`·평문 중계). SSO 돌아올 주소가 작업마다 바뀌는 `task-N-win` 도메인에 묶여 있어 미리 만들 수 없다 — 지금처럼 배포할 때 운영자가 맞추고 종료 때 정리한다(볼트 노트 `작업27 시험 배포 env·7.0 dash-test SSO·평문 중계(10.1.4.8)-2026-11-03`).
- Symphony가 여러 파일·폴더를 묶어 주는 것. **레포가 납품 파일 하나를 만든다.**
- 작업 하나에 산출물 여러 개 따로 보관. 언제나 파일 하나다.
- 배포 이미지 없이 산출물 만들기(종료 때 워커가 다시 빌드 등). 1차 설계의 "등록자가 확인한 배포 이미지에서 꺼낸다"를 유지한다.
- 이미 종료된 작업의 산출물 다시 받기, 우리은행(카탈로그 14) 등 다른 레포 적용.

피해야 할 것(돌아가도 실패로 본다)
- 시험 배포가 띄우는 내용이 바뀌는 것(경북대는 계속 대구 `ROOT.war`, 강원은 계속 루트 경로 앱).
- 강원 묶음 안 `install.sh`가 실행 권한을 잃는 것(현장에서 `./install.sh`가 안 됨).
- 기존 단일 WAR 보관 레포의 이미지·파일 이름·경로 판정이 바뀌는 것.

## 3. 결정

| # | 항목 | 결정 |
|---|---|---|
| 1 | 산출물 출처 | 그대로 **배포 이미지** |
| 2 | 납품 파일 규약 | 이미지 안 **`/opt/netis-artifact/`에 파일 하나**. Symphony는 그 파일을 **그대로** 보관한다(다시 묶거나 풀지 않음) |
| 3 | 경로 자동 판정 | 카탈로그 경로가 비면 `/opt/netis-artifact/` 바로 아래 일반 파일을 **먼저** 본다 — 1개면 그것, 2개 이상이면 실패, 0개(폴더 없음 포함)면 지금의 WAR·JAR 자동 검색 |
| 4 | 허용 확장자 | `.war` `.jar`에 `.zip` `.tar.gz` `.tgz` 추가(대소문자 무시). 보관 이름은 원본 확장자를 쓴다 |
| 5 | 미리 빌드 레포의 납품 파일 | 미리 빌드 명령이 deploy worktree의 **`.netis-artifact/`**에 납품 파일 하나를 남기면 워커가 `.netis-prebuild/artifact/`로 옮기고, **생성한** Dockerfile 끝에 `COPY .netis-prebuild/artifact/ /opt/netis-artifact/`를 붙인다(로케일 줄처럼 결정적) |
| 6 | Docker 안에서 빌드하는 레포 | 레포가 **자기 Dockerfile**로 `/opt/netis-artifact/`에 파일을 둔다. 강원은 레포에 Dockerfile을 커밋한다(§10.2) |
| 7 | 커밋 값 전달 | 워커가 모든 `docker build`에 `--build-arg NETIS_GIT_SHA=<head_sha 전체>`를 넘긴다 |
| 8 | 크기 상한 | 그대로 `CLOSE_ARTIFACT_MAX_SIZE_MB`(기본 1024). 경북대 zip ≈ 530MB(war 둘, 무압축 보관) |

기각한 대안과 이유

- **Symphony가 이미지 안 폴더를 tar.gz로 묶기**(초안): 여러 파일을 레포 손대지 않고 받을 수 있지만, 워커에 바이너리 스트림 실행기·tar 처리가 새로 들고, 강원 현장 절차(INSTALL §4 `unzip`, §2 `unzip` 필수)와 형식이 달라 INSTALL까지 고쳐야 한다. 두 레포 모두 파일 하나를 스스로 만들 수 있음을 확인해(§10) 뺐다.
- **종료 때 워커가 산출물 명령을 다시 실행**(카탈로그에 산출물 명령·glob): 강원 묶음은 리눅스 빌드가 필요해 Windows 워커에서 만들기 어렵고, 배포해서 확인한 것과 다른 빌드를 넘기게 된다.
- **배포 때 미리 빌드 결과를 바로 API에 올림**: 경북대에는 가장 빠르지만 강원(Docker 안 빌드)에는 못 쓰고, 종료 전 상태에서 받는 업로드 경로·sha별 보관·정리가 API에 새로 생긴다. 출처가 두 갈래가 된다.
- **경로 규약을 설정값으로**: 미리 빌드가 붙이는 COPY 줄·자동 판정·레포 Dockerfile이 모두 같은 경로를 써야 해서 상수가 낫다.

## 4. 이미지 안 납품 파일 규약

- 폴더: `/opt/netis-artifact/` (상수, 워커 코드 한 곳에 정의).
- 그 폴더 **바로 아래**에 일반 파일 **하나**. 확장자는 §3-4 목록. 하위 폴더·링크는 보지 않는다.
- 파일 이름은 자유(예: `knu-wars.zip`, `netis-dash-deploy-1a2b3c4.zip`) — 보관 이름은 Symphony 규칙으로 바뀐다(§5.2). zip 안의 최상위 폴더 이름은 레포가 정한 대로 남는다.
- 앱은 이 폴더를 읽지 않는다. 이미지 크기만 늘어난다(§13).

## 5. 워커: 산출물 꺼내기 (`ArtifactPublisher`)

### 5.1 경로 정하기

```
카탈로그 경로(closeArtifact.path) 있음
  └ 그 파일. 확장자가 §3-4 목록이 아니면 실패          ← 지금과 같고 목록만 넓어짐
카탈로그 경로 없음
  1) docker run --rm --entrypoint find <이미지> /opt/netis-artifact -mindepth 1 -maxdepth 1 -type f
       → 1개: 그 파일(확장자가 목록 밖이면 실패)
       → 2개 이상: 실패(후보 나열)
       → 0개(폴더 없음·find 없음 포함): 다음으로
  2) 지금의 WAR·JAR 검색(CLOSE_ARTIFACT_SEARCH_DIRS, -maxdepth 2, 정확히 1개) — 동작·문구 그대로
```

1)을 2)보다 먼저 보는 이유: 경북대 이미지에는 `webapps/ROOT.war`(대구)와 `/opt/netis-artifact/knu-wars.zip`(대구+상주)이 함께 있다. 1)은 2)와 같은 방식(임시 컨테이너 `find`, `/opt/netis-artifact/`로 시작하는 줄만)으로 하되, `find`가 0이 아닌 코드로 끝나고 출력이 "폴더 없음"(`No such file or directory`)이나 "find 없음"(`executable file not found`)이 아니면 **종료실패**(`산출물: 납품 폴더 확인 실패 — … — 데몬 상태를 확인하고 재시도`)한다. 데몬·ssh 일시 오류를 "납품 파일 없음"으로 보면 2)가 `ROOT.war`를 조용히 보관해 되돌릴 수 없이 작업종료되기 때문이다(최종 검토 반영, 2026-11-04 원격 데몬에서 busybox·GNU `find`와 없는 실행 파일의 실제 문구 확인).

꺼내기(`docker create` → `docker cp <상자>:<경로> <로컬 파일>` → `docker rm -f`)·sha256·업로드·작업 폴더 정리는 지금과 같다.

### 5.2 이름

`{레포 마지막 조각}-task-{id}-{sha7}.{확장자}` — 지금 규칙에 확장자만 넓힌다. 확장자는 원본 파일의 것(소문자, `tar.gz`는 두 조각 그대로).

예: `netisdash_knu-task-28-f4000f8.zip`, `netisdash_kangwonedu-task-32-1a2b3c4.zip`, 기존 `netisdash_immigration-task-29-58a2e5c.war`.

## 6. 워커: 미리 빌드 납품 파일 (`DeployPrebuilder`·`DeployService`)

1. 미리 빌드 명령이 성공하고 배포용 산출물(정확히 1개)을 `.netis-prebuild/`로 옮긴 **뒤**, deploy worktree 루트의 `.netis-artifact/`를 본다.
   - 폴더가 없으면 아무것도 안 한다(지금과 같음).
   - 있으면 바로 아래 일반 파일이 **정확히 1개**이고 확장자가 §3-4 목록이어야 한다. 그 파일을 `.netis-prebuild/artifact/<같은 이름>`으로 복사하고 로그 `[미리 빌드 납품] <이름> (X MB) → .netis-prebuild/artifact/`. 0개·2개 이상·확장자 밖이면 **배포실패** `미리 빌드 납품 파일은 .netis-artifact/ 바로 아래 하나여야 함(…)` — 카탈로그 명령이 잘못된 것이라 배포 때 바로 알린다.
   - `.netis-artifact/`는 deploy worktree에만 생긴다(배포는 커밋하지 않는다). 지금 `.dockerignore`(`*` + `!.netis-prebuild/**`)가 이미 `.netis-prebuild/artifact/`를 빌드 컨텍스트에 넣는다.
2. **Dockerfile을 생성했을 때만** 생성·재생성·로케일 붙이기가 끝난 뒤 파일 끝에 두 줄을 붙인다(마지막 `FROM` 뒤 = 최종 단계).
   ```
   # 종료 산출물 납품 파일 (Symphony 워커가 붙임)
   COPY .netis-prebuild/artifact/ /opt/netis-artifact/
   ```
   로그 `[납품 파일] COPY .netis-prebuild/artifact/ → /opt/netis-artifact/ 추가`.
3. 생성 프롬프트의 미리 빌드 절에 한 줄: "`.netis-prebuild/artifact/`는 워커가 COPY를 붙이니 Dockerfile에서 다루지 마세요."
4. **레포 Dockerfile**이면 손대지 않는다. `.netis-prebuild/artifact/`를 스테이징했는데 Dockerfile 본문에 그 경로가 없으면 지금의 미리 빌드 경고처럼 `[경고] Dockerfile이 .netis-prebuild/artifact/를 쓰지 않음 — 납품 파일이 이미지에 들어가지 않음` 한 줄.

## 7. 워커: 빌드 인자 `NETIS_GIT_SHA`

- `LocalDockerTarget`의 `docker build`에 `--build-arg NETIS_GIT_SHA=<head_sha>`를 넣는다(`DeploySpec`에 값 추가). head_sha가 없으면(`latest` 이미지) 넣지 않는다.
- 쓰는 곳: 레포 Dockerfile이 `ARG NETIS_GIT_SHA`로 묶음 이름에 커밋을 넣는다(강원 §10.2). deploy worktree의 `.git`은 파일(worktree 링크)이라 이미지 빌드 안에서 커밋을 알 길이 없다.
- 이 인자를 쓰지 않는 Dockerfile은 구형 빌더가 `[Warning] One or more build-args [NETIS_GIT_SHA] were not consumed`를 남기고 정상 빌드할 것으로 본다 — 구현 때 원격 데몬(Engine 29.1.3, 구형 빌더)에서 실제 출력·종료 코드를 확인한다.

## 8. API

- `ArtifactStore.validName`: 이름 규칙 `[A-Za-z0-9._-]{1,200}`, 점으로 시작 금지는 그대로, 끝이 `.war` `.jar` `.zip` `.tar.gz` `.tgz`(소문자) 중 하나.
- `CloseArtifactService`의 400 문구를 같은 목록으로.
- 저장·해시·멱등·상한·내려받기(`application/octet-stream`, `Content-Disposition` 원래 이름)는 그대로. 메일 본문·노트 줄은 이름·크기만 쓰므로 그대로.
- DB 변경 없음(`artifact_name VARCHAR(255)`, `artifact_path VARCHAR(500)`).

## 9. 프론트

- 레포 카탈로그(`pages/admin/repo-catalog.vue`)
  - 토글 라벨: `종료 때 산출물 보관 (WAR·JAR·zip, 등록자 메일)`.
  - 경로 칸 placeholder: `비우면 자동: /opt/netis-artifact/의 파일 하나 → 없으면 webapps·/app의 WAR·JAR 하나`.
- 작업 상세의 산출물 줄·내려받기 버튼은 그대로(이름에 `.zip`이 보일 뿐).

## 10. 레포별 적용

### 10.1 경북대 생활관 (카탈로그 12, 미리 빌드)

지금 값(2026-11-04): `mvn -B -o -Daether.enhancedLocalRepository.trackingFilename=_none_ -Pdaegu clean package -DskipTests`, 산출물 `hm-dash/target/*.war`, 도구 `jdk8-maven`, 산출물 보관 켬·경로 비움.

바꿀 값(워커 반영 뒤):

| 칸 | 값 |
|---|---|
| 명령 | `mvn -B -o -Daether.enhancedLocalRepository.trackingFilename=_none_ -Pdaegu clean package -DskipTests && mvn -B -o -Daether.enhancedLocalRepository.trackingFilename=_none_ -Psangju package -DskipTests && mkdir .netis-artifact-src .netis-artifact && copy /y hm-dash\target\hm-dash*.war .netis-artifact-src\ && tar -a -cf .netis-artifact\knu-wars.zip --options zip:compression=store -C .netis-artifact-src .` |
| 배포 산출물 | `hm-dash/target/hm-dash{,-daegu}.war` |
| 산출물 경로 | 비움(자동 → `/opt/netis-artifact/knu-wars.zip`) |

2026-11-04 이 PC에서 확인한 것
- 작업 28 브랜치 대구→상주 연속 빌드 79초 성공(war 각 266MB). `main`은 `-Pdaegu`·`-Psangju`가 없는 프로필이라 경고만 내고 `hm-dash.war`를 만든다. `maven-enforcer-plugin:3.0.0`은 Central에서 받아 `~/.m2`에 있다.
- Java glob `hm-dash{,-daegu}.war`는 `hm-dash.war`·`hm-dash-daegu.war`만 맞고 `hm-dash-sangju.war`는 안 맞는다(배포는 계속 대구 하나).
- 뒷부분(`mkdir … && copy … && tar -a -cf …zip --options zip:compression=store -C … .`)을 워커와 같은 `cmd.exe /d /s /c "…"`로 가짜 war 두 개에 돌려 종료 코드 0, zip 안 `./hm-dash-daegu.war`·`./hm-dash-sangju.war` 확인. Windows 기본 `C:\WINDOWS\system32\tar.exe`(bsdtar 3.8.9). `main` 모양(war 하나)이면 zip 안에 `hm-dash.war` 하나.
- 전체 명령(빌드 두 번 + 묶기)을 한 번에 돈 것은 아니다 — 구현 뒤 첫 배포가 그 확인이다. 무압축 보관(`store`)은 war가 이미 압축 파일이라 다시 압축해도 거의 줄지 않아서다.

결과: 시험 배포는 대구 `ROOT.war`(지금과 같음), 종료 산출물 `netisdash_knu-task-28-<sha7>.zip` = 대구·상주 war.

### 10.2 강원도교육청 (카탈로그 11, Docker 안 빌드)

레포에 **Dockerfile·`.dockerignore`를 커밋**한다. 지금은 배포 때마다 Claude가 생성한다. 시작점은 작업 27 배포 로그의 생성 Dockerfile(`node:22-alpine` deps → builder → runner, `EXPOSE 3300`, `CMD ["node", "server.js"]`, 헬스체크 `/` → 307 통과).

단계(이름은 빌더 판단):

1. deps: `npm ci`(지금과 같음).
2. app: `DASH_BASE_PATH`를 비운 루트 빌드 — **시험 배포용, 지금 동작 그대로**.
3. bundle: **glibc 리눅스 이미지**(예: `node:22-bookworm-slim` — 현장 서버의 Node 런타임이 glibc `linux-x64`이고, busybox tar에는 `--transform`이 없다)에서
   - `DASH_BASE_PATH=/dash npm run build`, standalone에 `public/`·`.next/static` 복사, `netis-dash/` 이름의 최상위 폴더로 `netis-dash-<sha7>.tar.gz`(INSTALL §5와 같은 내용).
   - Node 22 `linux-x64` `.tar.xz`를 nodejs.org에서 받고 같은 버전의 `SHASUMS256.txt`로 대조. 버전은 ARG로 고정(지금 최신 `v22.23.4` = INSTALL §2의 파일), `install.sh`가 `node-v22.*-linux-x64.tar.xz`를 찾는다.
   - `deploy/install.sh`·`uninstall.sh`·`INSTALL.md`·`conf/*`·`sql/*`, `db/2026-10-27-dash-ext-conf.sql`(→ `sql/`), `BASE_PATH`(`/dash`), `SHA256SUMS`(node tar.xz·앱 tar.gz)로 `netis-dash-deploy-<sha7>/` 폴더를 만들고 `install.sh`·`uninstall.sh`는 755.
   - Info-ZIP `zip`(apt)으로 `netis-dash-deploy-<sha7>.zip`을 만든다(유닉스 권한이 zip 속성에 들어간다 — INSTALL §5 마지막 줄).
   - `<sha7>` = `ARG NETIS_GIT_SHA`의 앞 7자, 비면 `dev`.
4. runner: 지금 runner와 같고, 끝에 `COPY --from=bundle /out/netis-dash-deploy-*.zip /opt/netis-artifact/` → 이미지 안 `/opt/netis-artifact/netis-dash-deploy-<sha7>.zip` 하나.

- 10.1.1.75 Docker 컨테이너(`node:22-alpine`, 이미 있는 이미지)에서 `https://nodejs.org/dist/index.json`·`/dist/v22.23.4/SHASUMS256.txt`·`http://deb.debian.org/debian/dists/bookworm/Release` 모두 접속 확인(2026-11-04).
- 카탈로그 11: 산출물 보관 켬, 경로 비움(자동 → `/opt/netis-artifact/`). 미리 빌드는 끔 그대로.
- 결과: 종료 산출물 `netisdash_kangwonedu-task-N-<sha7>.zip` → `unzip` 하면 `netis-dash-deploy-<sha7>/install.sh`(실행 권한 있음). INSTALL §4 절차 그대로(바깥 zip 파일 이름만 Symphony 규칙).
- 빌드가 두 번(루트·`/dash`)이라 배포 빌드 시간이 늘어난다. build 상한 30분·`배포중` 판정 70분 안이어야 한다(구현 때 작업 27 빌드 시간과 비교).

**강원 레포 반영 방법(작성자 판단)**
1. 레포 밖에서 먼저 확인: GitLab에서 받은 `main`에 Dockerfile을 두고 이 PC에서 `DOCKER_HOST=ssh://Administrator@10.1.1.75`로 직접 `docker build --build-arg NETIS_GIT_SHA=…` → 이미지에서 zip을 꺼내 리눅스(컨테이너)에서 `unzip` → `sha256sum -c SHA256SUMS`·`install.sh` 755 확인 → 루트 앱 컨테이너 `/` 307 확인. 이 시험 이미지는 확인 뒤 지운다.
2. 강원 레포에 브랜치를 올리고 `main` 대상 MR을 연다 — **푸시·MR 열기는 그때 사용자 승인을 받는다**(외부 레포 변경). 병합은 레포 담당(사용자)이 한다.
3. 카탈로그 11 산출물 보관은 **MR 병합 뒤에** 켠다. 그 전에 켜면 Dockerfile 없는 브랜치의 강원 작업이 종료 때 산출물을 못 찾아 종료실패한다('산출물 없이 마무리'로만 끝남). 2026-11-04 기준 진행 중인 강원 작업은 없다(작업 27은 작업종료).

### 10.3 기존 단일 WAR 레포

카탈로그 5(제2철도)·13(출입국)은 바꾸지 않는다. 이미지에 `/opt/netis-artifact/`가 없으므로 지금과 같은 `ROOT.war` 보관.

## 11. 오류 처리

| 상황 | 결과 |
|---|---|
| 카탈로그 경로의 확장자가 목록 밖 | 종료실패 `산출물: .war·.jar·.zip·.tar.gz·.tgz만 보관합니다: <경로>` |
| 자동 판정에서 `/opt/netis-artifact/`에 파일 2개 이상 | 종료실패 `산출물: /opt/netis-artifact/에 파일이 여러 개입니다 — 하나만 두거나 카탈로그에 경로를 지정하세요(후보: …)` |
| 자동 판정한 파일의 확장자가 목록 밖 | 종료실패(위 확장자 문구) |
| 미리 빌드의 `.netis-artifact/`가 비었거나 2개 이상·확장자 밖 | 배포실패 `미리 빌드 납품 파일은 .netis-artifact/ 바로 아래 하나여야 함(…)` |
| 레포 Dockerfile이 `.netis-prebuild/artifact/`를 안 씀 | 경고만 |
| 납품 파일이 상한(1024MB)을 넘음 | 지금과 같이 API 413 → 종료실패, 관리자 재시도 또는 '산출물 없이 마무리' |
| 구 API에 새 확장자 업로드 | 400 → 종료실패. 배포 순서(§12)로 피한다 |
| 강원 bundle 단계 실패(nodejs.org·apt 막힘 등) | `docker build` 실패 → **배포실패**(§14-5) |

## 12. 배포 순서

1. API(새 확장자 받기) — 재기동만, 마이그레이션 없음.
2. 워커(이 PC `win-worker-1`만 — 배포·종료를 받는 유일한 워커). 진행 중인 배포·종료가 없을 때. 원격 워커(10.1.1.75·Mac, `WORKER_KINDS=IMPLEMENTATION,DESIGN`)는 이 코드 경로를 타지 않지만 jar는 다음 갱신 때 맞춘다.
3. 프론트(`.output` 재빌드) — 문구만.
4. 카탈로그 12 명령·배포 산출물 값 교체(§10.1). **작업 28은 이 뒤에 (재)배포하고 종료 승인한다** — 그 전에 종료하면 지금 이미지의 `ROOT.war`(대구) 하나만 보관된다.
5. 강원 레포 Dockerfile 확인·MR·병합(§10.2) → 카탈로그 11 산출물 보관 켜기.

## 13. 남은 위험

- **원격 이미지 크기**: 경북대 이미지가 zip(약 530MB)만큼 커진다. GC는 작업별 최신 이미지 1개(`DEPLOY_GC_KEEP_IMAGES=1`)라 누적은 작업 수에 비례.
- **꺼내기 시간**: 530MB를 ssh로 받는다. 작업 29(240MB)는 승인→작업종료 1분 40초(병합·노트 포함). 10분 상한 안이지만 회선이 느리면 `CLOSE_ARTIFACT_COPY_TIMEOUT`을 올린다.
- **강원 bundle 단계가 배포를 막을 수 있음**: 같은 Dockerfile이라 묶음 만들기가 실패하면 시험 배포도 실패한다.
- **경북대 명령이 Windows `cmd` 전용**: 미리 빌드를 다른 OS 워커로 옮기면 명령을 고쳐야 한다(카탈로그 값이므로 코드 변경 없음).
- **바깥 파일 이름**: 내려받은 파일 이름은 `netisdash_kangwonedu-task-N-<sha7>.zip`이라 INSTALL §4의 `netis-dash-deploy-<커밋>.zip`과 다르다. 풀면 나오는 폴더 이름은 같다.

## 14. 작성자 판단 목록 (사용자 확인 필요)

1. Symphony는 묶지 않고, **레포가 납품 파일 하나**를 이미지 `/opt/netis-artifact/`에 둔다(초안의 tar.gz 묶기는 뺌).
2. 경로가 비면 `/opt/netis-artifact/`를 WAR 검색보다 **먼저** 본다.
3. 경북대 산출물 = 대구·상주 war를 담은 **zip 하나**(무압축 보관), 시험 배포는 대구 그대로.
4. 강원 산출물 = INSTALL §4와 같은 **현장 묶음 zip**, 레포에 Dockerfile을 커밋해 Docker 안(리눅스)에서 만든다.
5. 강원 bundle 단계 실패 시 배포도 실패(종료 때 "산출물 없음"보다 배포 때 바로 아는 편이 낫다).
6. 모든 `docker build`에 `NETIS_GIT_SHA` 빌드 인자를 넘긴다.
7. 미리 빌드 납품 파일이 0개·2개 이상이면 배포실패.
8. 강원 레포 반영은 작성자가 시험 빌드 → MR(푸시 전 사용자 승인) → 사용자 병합 → 카탈로그 11 켜기 순서.
9. 메일·강원 시험 배포 SSO는 범위 밖.

## 15. 빌더에게 맡기는 것

- §5.1의 1)·2)를 `docker run` 한 번으로 합칠지, 테스트 seam 모양.
- 강원 Dockerfile의 단계 이름·Node 버전 고정 방식·`.dockerignore` 내용(`node_modules`·`.next`·`.git` 등 제외)·`zip` 설치 방식.
- 경북대 명령의 `cmd` 문법 세부(§10.1의 값이 첫 배포에서 돌지 확인한 뒤 확정).

## 16. 테스트

- 워커 단위
  - `ArtifactPublisher`: 경로 판정 표(§5.1) — 카탈로그 경로의 새 확장자·잘못된 확장자, 자동 판정 순서(`/opt/netis-artifact/` 1개면 그것, 0개면 WAR 검색, 2개 이상 실패), 이름(§5.2 — `.zip`·`.tar.gz` 복합 확장자·대문자 확장자).
  - `DeployPrebuilder`: `.netis-artifact/` 없음·1개·0개·2개·확장자 밖·링크, 복사 위치.
  - `DeployService`: 생성 Dockerfile에만 COPY 두 줄(로케일 줄 뒤), 레포 Dockerfile 경고, 프롬프트 한 줄.
  - `LocalDockerTarget`: `--build-arg NETIS_GIT_SHA` 유무(head_sha 없음이면 없음).
- API 단위: `ArtifactStore.validName` 표(`.zip`·`.tar.gz`·`.tgz` 허용, `.gz`·`.tar`·`.tar.gzx`·`.zipx` 거부), `CloseArtifactService` 400 문구.
- 프론트: 카탈로그 라벨·placeholder(기존 repo-catalog spec 갱신).
- 실제 확인(구현 뒤, 사용자와)
  - `NETIS_GIT_SHA`를 안 쓰는 기존 레포(예: 출입국) 재배포가 경고만 내고 성공.
  - 경북대 작업 28 재배포 → 종료 → zip 안 war 두 개·크기.
  - 강원: §10.2의 레포 밖 시험, 병합 뒤 첫 강원 작업의 종료 zip을 리눅스에서 풀어 `sha256sum -c SHA256SUMS` 통과·`install.sh` 755.
