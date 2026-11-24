# 배포 DB 연결 고르기 · 배포 설정 덮어쓰기 — 설계

- 작성: 2026-11-24
- 상태: 초안 — 사용자 승인 전
- 계기: 작업 #54(Netis6.7, `[SITE-TICKET]`) 배포 때 DB 접속정보가 어느 기준으로 들어가는지 확인하다 나온 요청

## 1. 왜

지금 배포 창(`frontend/components/tasks/DeployEnvDialog.vue`)은 `KEY=VALUE` 행을 받아 `docker run -e`로 넣는다(`DeployService` → `LocalDockerTarget.buildRunArgs`). 이 방식에는 네 가지 문제가 있다.

1. **값 입력이 길고 틀리기 쉽다.** DB를 바꾸려면 `jdbc:log4jdbc:mysql://192.0.2.10:53306/netis?useSSL=false&…` 전체를 넣어야 하고, 접두사는 WAR 안 드라이버와 맞아야 한다.
2. **변수 이름이 늘 뜨지 않는다.** 이름은 구현 단계가 남긴 목록(`task.env_template`)이나 같은 레포 직전 배포의 env가 있을 때만 고정으로 뜬다. 지금까지 배포한 카탈로그 13곳 중 목록이 있는 곳은 3곳(4·8·11)뿐이다.
3. **값을 넣어도 적용된다는 보장이 없다.** Netis 6.x WAR 앱은 `WEB-INF/classes/config/jdbc_maria.properties`만 읽는다(`dataAccessContext.xml`의 `PropertiesFactoryBean` → `DbConfig`, env·시스템 속성을 보지 않음). `JDBC_URL` 같은 이름과 그 값을 설정 파일에 반영하는 장치는 배포 때 Claude가 Dockerfile을 생성하면서 그때그때 만든다 — 생성 지시문(`application-worker.yml` `dockerfile-prompt-template`)에는 "외부 DB/서비스 의존은 환경변수로 주입받도록 두고"뿐이다. 작업 #49는 3·5차 생성본에는 장치가 있었고 4차에는 빠져 WAR 기본값(`192.0.2.20:53306`, 배포 호스트에서 접속 불가)으로 기동이 실패했다.
4. **비밀번호가 `task.env_vars`에 평문으로 저장된다**(배포 창 안내문에도 그렇게 적혀 있다).

한편 레포마다 DB 연결(`com.db_connection`, 비밀번호는 `DbSecretCipher` AES-GCM 암호문)이 이미 등록돼 있다 — 예: 카탈로그 7(Netis6.7)에 "개발 DB(latest)" 공용, "latest DB 서버"·"현장 maria DB"·"현장 Oracle DB" 개인(admin 소유).

**사용자 요청(2026-11-24)**: 배포 창에서 변수 이름은 자동으로 떠 있고 값만 넣게 — DB는 `192.0.2.10:53306/netis`처럼 핵심만. 검토한 세 방안(§6) 중 **C(등록된 DB 연결 고르기 + Symphony가 적용까지 책임)**를 골랐다.

## 2. 목표

- **G1** 레포 카탈로그에 레포별 "배포 설정"을 정의하면, 그 레포 작업의 배포 창에 정의한 변수·DB 자리가 항상 고정으로 뜬다.
- **G2** DB 자리는 등록된 DB 연결을 드롭다운에서 고르면 URL·계정·비밀번호가 채워진다(직접 입력 없음).
- **G3** 일반 변수는 값 형식(`…{값}…`)이 정의돼 있으면 핵심 값만 입력한다.
- **G4** 설정 파일만 읽는 WAR 앱에도 고른 값이 **매 배포 같은 방식으로** 적용된다 — 생성 Dockerfile(Claude)이 아니라 워커가 적용한다.
- **G5** DB 비밀번호는 작업(`com.task`)에 저장하지 않는다.

## 3. 비목표

- 사이트명(DB 코드값 `WEB_SITE_NAME`, `AppGlobal.setFieldValue`가 `app.web.sitename`을 덮음) 바꾸기. 고른 DB 안의 값이 그대로 쓰인다 — latest DB(지금 `<사이트코드>`)로 붙이면 현장 한정 화면은 계속 안 보인다.
- DB 스키마·코드 데이터 적용(예: MR의 `docs/sql/<site>/SITE-TICKET_code.sql`).
- Tomcat이 아닌 런타임의 **설정 파일** 덮어쓰기. Node·Spring Boot처럼 env를 읽는 앱은 env 대상(§7.1)으로 충분하다.
- 레포에 **자체 Dockerfile이 있는** 경우의 설정 파일 덮어쓰기(v1은 명확히 실패시킨다, §7.5).
- 앱이 실제로 그 DB에 붙었는지 별도 확인. 지금 헬스체크(생존 + HTTP readiness + 기동 실패 흔적) 그대로.
- 질문·인터뷰·구현 단계 DB 기능. 작업에 고른 DB 연결(`task.db_connection_ids`)을 배포 기본값으로 쓰지 않는다.
- 일반 비밀 변수(`secret=true` env 행)의 평문 저장 개선 — DB 비밀번호만 범위.

## 4. 반목표 (기술적으로 돌아가도 이렇게 되면 실패)

- DB를 골랐는데 조용히 WAR 기본값으로 뜨는 것.
- DB 비밀번호가 이미지 레이어·종료 납품 파일·배포 로그·앱 로그·`task` 행에 남는 것.
- 배포 설정을 정의하지 않은 레포의 배포 창·배포 동작이 바뀌는 것.

## 5. 제약 (조사로 확인한 사실)

- **형식은 DB 종류가 아니라 레포·빌드 프로필에 묶인다.** `-Pdev` 빌드는 `jdbc.driverClassName=net.sf.log4jdbc.DriverSpy`라 URL이 `jdbc:log4jdbc:mysql://`여야 하고, `deploy3306` 프로필은 `jdbc:mysql://`이다. 6.8 배포는 `JDBC_DRIVER_CLASS_NAME`까지 넣었다. Netis7.0(Spring Boot)은 `SPRING_DATASOURCE_*`·`DB_HOST`/`DB_PORT` env, 강원(Next.js)은 `DASH_DB_URL` env를 쓴다. → 화면에 형식 하나를 고정할 수 없고 레포마다 정의해야 한다.
- **배포 창을 제출하는 시점에는 Dockerfile이 없다.** 생성은 워커가 배포 중에 한다.
- **종료 산출물은 배포 이미지에서 꺼낸다**(`ArtifactPublisher` — `netis-task-{id}:{sha7}` 이미지를 `docker create` + `docker cp`). 이미지 안 WAR나 이미지 레이어에 시험 DB 설정을 넣으면 납품 파일로 나간다.
- **배포는 관리자만 한다**(`POST /api/tasks/{id}/deploy|redeploy`, `GET …/deploy-env` 모두 `ROLE_ADMIN`). 수정 회차·기준 브랜치 반영 회차는 사람이 창을 열지 않고 저장된 env로 자동 재배포한다(`WorkerService` — "저장된 env 그대로 자동 재배포").
- **생성 Dockerfile의 런타임은 주로 공식 `tomcat` 7**(7.0.86~7.0.109), #18은 8.5(레포 Dockerfile). #49 이미지가 쓴 장치: 전역 `$CATALINA_HOME/conf/context.xml`에 `<Loader className="org.apache.catalina.loader.VirtualWebappLoader" virtualClasspath="/opt/netis/override" searchVirtualFirst="true"/>` + 엔트리포인트가 env로 `/opt/netis/override/config/jdbc_maria.properties`를 씀 — WAR는 그대로 두고 classpath 앞에 끼운다. Tomcat 8+에는 `VirtualWebappLoader`가 없고 `<Resources><PreResources className="org.apache.catalina.webresources.DirResourceSet" …/></Resources>`가 같은 일을 한다.
- **DB 연결 선택 규칙이 이미 있다**: `DbConnectionService.selectableChips(repoCatalogId, ownerId)` = 그 카탈로그의 enabled 연결 중 공용(REPO) 또는 본인 소유. 복호화는 `DbSecretCipher`(키가 없으면 기능만 꺼짐), claim 때 복호화하는 선례는 `resolveForClaim`/`DbConnectionRef`(인터뷰·구현 DB MCP).
- 배포 호스트(10.1.1.75) 컨테이너에서 2026-11-24 접속 확인: `192.0.2.10:53306`·`192.0.2.10:1521`·`other-db.example.invalid:53306` 성공, `192.0.2.20:53306` 실패.

## 6. 방안 (검토와 선택)

| | 내용 | 결과 |
|---|---|---|
| A | 카탈로그에 변수 이름·형식만 정의, 짧은 값을 배포 직전에 조립. 적용은 생성 Dockerfile에 맡김 | 입력은 편해지지만 적용 보장 없음(#49 4차 같은 일 반복), 비밀번호 직접 입력·평문 |
| B | A + 워커가 설정 파일 덮어쓰기를 직접 붙임 | 매 배포 같은 결과. 비밀번호는 여전히 입력·평문 |
| **C** | **B + DB는 등록된 DB 연결 중 고르기** | **선택(2026-11-24 사용자).** 입력 없음, 비밀번호 저장 안 함. 가장 큼 |

뺀 것: 레포마다 Dockerfile을 커밋하는 방법(#18 방식) — 대상 레포를 바꿔야 하고 레포마다 따로 해야 한다. 화면에 형식 하나를 고정하는 방법 — §5의 이유로 맞지 않는다.

## 7. 동작 (2026-11-24 사용자 승인: "이대로 써 줘")

### 7.1 레포 카탈로그 "배포 설정" (관리자 입력)

레포마다 선택 항목. 비어 있으면 그 레포는 지금 동작 그대로(§7.6).

- **DB 자리**(0개 이상, 보통 1개): 이름(예: `주 DB`), 받을 DB 종류(`MARIADB`·`MYSQL`·`POSTGRESQL`·`ORACLE` 중 하나 이상), 넣을 곳(대상) 하나.
  - **설정 파일 대상**: WAR 안 경로(예: `WEB-INF/classes/config/jdbc_maria.properties`)와 항목별 값 형식 — 예: `jdbc.url` = `jdbc:log4jdbc:mysql://{host}:{port}/{database}?useSSL=false&allowMultiQueries=true&useUnicode=true&characterEncoding=utf8&autoReconnect=true&noAccessToProcedureBodies=true`, `jdbc.username` = `{username}`, `jdbc.password` = `{password}`.
  - **환경변수 대상**: env 이름별 값 형식 — 예: `SPRING_DATASOURCE_URL` = `jdbc:postgresql://{host}:{port}/{database}`, `SPRING_DATASOURCE_USERNAME` = `{username}`, `SPRING_DATASOURCE_PASSWORD` = `{password}`.
  - 자리표시자는 `{host}` `{port}` `{database}` `{username}` `{password}`만. 값은 그대로 들어간다. URL 안에 계정을 넣는 형식은 `{username:url}`·`{password:url}`(퍼센트 인코딩)을 쓴다 — 예: 강원 `DASH_DB_URL` = `postgresql://{username:url}:{password:url}@{host}:{port}/{database}`(작업 #27 값이 이 모양). 비밀번호에 `@ : / # %`가 있어도 URL이 깨지지 않게 하고, JDBC 항목(`jdbc.password`)처럼 따로 넣는 값은 인코딩하지 않기 위해서다.
- **일반 변수**(0개 이상): 이름·설명·비밀 여부·필수 여부·값 형식(선택 — `{값}`을 정확히 한 번 포함, 예: `https://{값}.micthebick.dev`)·대상(env 이름, 또는 설정 파일 경로 + 항목).

### 7.2 배포 창

- 배포 설정이 있으면 창 위쪽에 **DB** 묶음: 자리마다 드롭다운.
  - 목록 = 그 레포의 공용 연결 + 배포하는 관리자 본인 연결 중 DB 종류가 맞는 것, 그리고 맨 위 "고르지 않음(WAR 기본값)". 54번(카탈로그 7, 주 DB = MariaDB)이면 "개발 DB(latest)", "latest DB 서버", "현장 maria DB".
  - 고른 연결의 `host:port/database`와 공용·개인 표시를 함께 보인다. 비밀번호는 보이지 않는다.
  - **기본 선택**: 이 작업에 저장된 선택 → 없으면 같은 레포 직전 배포의 선택 → 없으면 "고르지 않음". 작업에 고른 DB 연결(`db_connection_ids`)은 쓰지 않는다.
  - **"고르지 않음"도 저장한다.** 관리자가 창을 제출하면 자리마다 고른 연결 또는 "고르지 않음"이 저장되고, 저장된 자리가 하나라도 있으면 직전 배포를 보지 않는다(env 미리 채움과 같은 규칙). WAR 기본값으로 돌던 작업의 재배포 창이 다른 작업의 DB를 미리 골라 두어 조용히 DB가 바뀌는 일을 막는다.
- 아래쪽 **변수**: 정의된 변수는 이름 고정. 값 형식이 있으면 입력칸 앞뒤에 고정 부분을 글자로 보이고(예: `https://` [ 입력 ] `.micthebick.dev`) 가운데만 입력한다.
- "변수 추가"(자유 행)는 지금처럼 남는다.
- **이름이 겹치는 행**: 이 작업에 저장된 행이나 구현 단계 목록(`env_template`)의 키가 배포 설정의 env 이름(일반 변수 이름, DB 환경변수 대상 이름)과 같으면 따로 보이지 않고 배포 설정 행 하나로 합쳐진다 — 변수면 저장값이 그 변수의 값으로 들어가고, DB 대상 이름(예: 평문으로 저장된 `SPRING_DATASOURCE_PASSWORD`)이면 숨긴다. DB 환경변수 대상 이름을 자유 행으로 새로 넣으면 저장 때 거부한다(변수 이름은 화면이 같은 이름의 행으로 보내므로 요청 바디에서 구별할 수 없어 받는다 — 같은 이름이면 그 변수의 값이 된다). 설정 파일 대상 때문에 쓸모없어진 env 행(예: Netis6.7의 `JDBC_URL`)은 앱이 읽지 않아 해가 없으므로 자유 행으로 그대로 둔다 — 지울지는 관리자 몫.

### 7.3 저장·재배포

- 작업에는 **DB 연결 번호만** 저장한다(비밀번호 없음). 변수는 **입력한 짧은 값**을 저장하고 배포할 때 조립한다 — 재배포 창에 짧은 값이 다시 보이게. 값이 이미 형식의 앞뒤 고정 부분을 가지고 있으면(예전 전체 값) 다시 감싸지 않고 그대로 쓴다.
- 재배포·수정 회차 자동 재배포·기준 브랜치 반영 자동 재배포도 저장된 선택을 그대로 쓴다.
- **조립 순서**: 저장된 env 행 → 배포 설정 변수 → DB 대상 순으로 덮는다. 같은 이름이면 배포 설정 쪽이 이긴다 — 창을 열지 않는 자동 재배포에서 예전에 저장된 같은 이름의 행(평문 비밀번호 등)이 남아 있어도 실패하지 않고 고른 DB 값이 들어간다.
- 이미 저장된 평문 비밀번호 행을 자동으로 지우지는 않는다(G5는 새로 저장하지 않는다는 뜻). 이름이 겹쳐 숨겨진 행은 관리자가 다음에 창을 제출할 때 저장 목록에서 빠진다.

### 7.4 적용 (워커가 직접)

- **환경변수 대상**: 조립한 값을 `-e`로 넣는다(지금과 같은 경로).
- **설정 파일 대상**: 미리 빌드한 WAR에서 원래 파일을 꺼내 정한 항목만 바꾼 사본을 만들고, 컨테이너가 시작될 때 Tomcat이 그 사본을 WAR 것보다 먼저 읽게 한다(#49 이미지의 방식, Tomcat 7과 8+를 각각 처리). **WAR·이미지는 건드리지 않는다** — 납품 파일에 시험 DB 설정이 들어가지 않고, 비밀번호가 이미지 레이어에 남지 않는다.

### 7.5 적용할 수 없으면 배포 실패

DB를 골랐거나 설정 파일 대상 변수에 값이 있는데 적용할 수 없으면 **배포를 실패시키고 이유를 남긴다**: 미리 빌드가 꺼짐 / 산출물이 WAR가 아님 / 레포에 자체 Dockerfile이 있음 / 최종 런타임이 Tomcat 이미지가 아님 / WAR에 그 파일이 없음 / 연결이 삭제·비활성·복호화 불가 / DB 종류가 자리와 안 맞음. 조용히 WAR 기본값으로 뜨는 것을 막기 위해서다(§4).

### 7.6 배포 설정이 없는 레포

배포 창·저장·워커 동작이 지금과 같다(템플릿 + 직전 배포 미리 채움, `-e` 주입).

## 8. 데이터 (작성자 판단)

마이그레이션 V50(병합 직전 `origin/main`의 가장 큰 번호를 다시 본다):

- `repo_catalog.deploy_config JSONB NULL` — null = 배포 설정 없음.
- `task.deploy_db_selection JSONB NOT NULL DEFAULT '[]'` — `[{"slot":"main","connectionId":43}]`. "고르지 않음"은 `{"slot":"main","connectionId":null}`로 남는다. `[]` = 한 번도 고르지 않음(직전 배포 미리 채움 대상).
- `task.env_vars`는 그대로. 배포 설정 변수의 값도 여기에 **입력한 짧은 값**으로 들어간다.

`deploy_config` 예 — 카탈로그 7(Netis6.7, `-Pdev`):

```json
{
  "databases": [{
    "slot": "main",
    "label": "주 DB",
    "dbTypes": ["MARIADB", "MYSQL"],
    "target": {
      "kind": "FILE",
      "path": "WEB-INF/classes/config/jdbc_maria.properties",
      "entries": {
        "jdbc.url": "jdbc:log4jdbc:mysql://{host}:{port}/{database}?useSSL=false&allowMultiQueries=true&useUnicode=true&characterEncoding=utf8&autoReconnect=true&noAccessToProcedureBodies=true",
        "jdbc.username": "{username}",
        "jdbc.password": "{password}"
      }
    }
  }],
  "variables": []
}
```

환경변수 대상 예 — Netis7.0: `"target": {"kind": "ENV", "entries": {"SPRING_DATASOURCE_URL": "jdbc:postgresql://{host}:{port}/{database}", "SPRING_DATASOURCE_USERNAME": "{username}", "SPRING_DATASOURCE_PASSWORD": "{password}"}}`.

일반 변수 항목: `{"key","description","secret","required","format"(선택),"target"(선택 — 없으면 같은 이름의 env; FILE이면 {"kind":"FILE","path","entry"})}`.

## 9. API

- **카탈로그 수정**: `deployConfig` 검증 — 자리 이름 규칙·중복, `dbTypes`는 `DbType` 값, DB 대상 값의 자리표시자는 §7.1의 다섯 개만, 변수 `format`은 `{값}` 정확히 한 번, FILE `path`는 `WEB-INF/classes/` 아래 상대 경로·`..` 금지·`.properties`로 끝남(§10의 Tomcat 7 방식이 classpath만 덮을 수 있으므로), ENV 이름 규칙, **같은 env 이름·같은 파일 항목을 두 곳에서 정의 금지**. 부분 바디 규칙은 기존 카탈로그 칸과 같게(null = 유지, 빈 값 = 비움).
- **`GET /api/tasks/{id}/deploy-env` 확장**: 기존 `rows`에 더해 `databases: [{slot, label, dbTypes, targetSummary, selected: {connectionId, from: SAVED|PREVIOUS}|null, choices: [{id, name, dbType, scope, host, port, database}]}]`와 변수 행의 `format`. `choices` = `selectableChips(repo, 요청 관리자)` 중 종류가 맞는 것 + (저장된 선택이 그 목록에 없으면 — 다른 관리자의 개인 연결 등 — 그 하나). 이 응답에 비밀번호·형식 조립 결과는 싣지 않는다.
- **`POST …/deploy|redeploy` 바디**: `envVars`(기존) + `dbSelection: [{slot, connectionId|null}]`. `dbSelection` 키가 없으면(구 화면) 저장된 선택 유지, 있으면 그대로 저장(`connectionId: null` = "고르지 않음"으로 저장). 검증: 자리가 카탈로그에 있음, 연결이 같은 `repo_catalog_id`·enabled·종류 일치, 그리고 (공용 또는 요청 관리자 소유 또는 이 작업에 이미 저장된 선택과 같음). `envVars`에 DB 환경변수 대상 이름과 같은 행이 있으면 400(§7.2 이름이 겹치는 행). 변수 이름과 같은 행은 그 변수의 값으로 받는다.
- **직전 배포 미리 채움**: 배포 설정이 있는 레포는 (a) DB 선택을 같은 레포 직전 배포에서 가져오고(현재 관리자가 고를 수 없는 연결이면 가져오지 않음), (b) 변수 값을 가져오며, (c) **다른 작업의 자유 행(설정에 없는 키)은 가져오지 않는다**(작성자 판단 — 예: #49의 `JDBC_URL`이 카탈로그 7 작업 창에 다시 뜨지 않게). 이 작업에 저장된 자유 행은 그대로.
- **claim(배포대기)**: `WorkerTaskResponse`에 조립이 끝난 `deployOverrides` — `env: [{key, value, secret}]`, `files: [{path, entries: {k: v}}]`, `summary`(로그용 — 자리·연결 이름·종류·host:port/db, 비밀 없음), `error`(적용 불가 사유 — 연결 삭제·비활성·복호화 실패 등). 복호화는 claim 때 API에서(`resolveForClaim` 선례). `toString`·로그에 비밀번호를 남기지 않는다.
- **구버전 워커 막기**: 새 워커는 `supports=…,deployOverrides`를 보낸다. 없는 워커에는 배포 설정이 있는 레포의 배포대기와 DB 선택이 저장된 배포대기를 넘기지 않는다(`TaskRepository.findClaimableAmong` 패턴 — 변수 조립도 새 워커 몫이라 설정이 있으면 전부). 배포는 이 PC 워커만 받지만 같은 규칙을 지킨다.
- **claim 준비는 예외를 던지지 않는다**: 복호화·조립은 claim 커밋 뒤에 하므로, 어떤 실패도 `error`(배포실패)로 바꾼다 — 던지면 작업이 배포중에 갇힌다. 저장된 선택의 자리가 설정에 없거나 설정이 사라졌는데 고른 연결이 남아 있어도 `error`다(조용히 WAR 기본값으로 뜨지 않게).
- **앱 로그 가리기**: `SecretMasker`가 고른 연결의 비밀번호(API가 복호화)와 비밀 변수 값도 가린다.

## 10. 워커

`DeployService.deploy`:

1. claim의 `deployOverrides.error`가 있으면 바로 배포실패(그 문구).
2. `env`를 `spec.env()`에 합친다(충돌은 API 검증이 막는다).
3. `files`가 있으면:
   - 미리 빌드 산출물(`Staged`)이 `.war`가 아니면 실패. 레포에 Dockerfile이 있으면 실패(v1).
   - WAR에서 `path` 원본을 읽고(없으면 실패) 원본 **끝에** `# Symphony 배포 덮어쓰기` 줄과 `key=value`를 덧붙인다 — `java.util.Properties`는 같은 키의 마지막 값을 쓰므로 주석·다른 키·기존 줄을 건드릴 필요가 없다. 값은 Properties 규칙으로 이스케이프하고 비ASCII는 `\uXXXX`로(파일 인코딩과 무관하게).
   - 사본은 **빌드 컨텍스트 밖** 워커 임시 폴더에 `override/WEB-INF/classes/…` 모양으로 쓴다.
   - Dockerfile 생성 프롬프트에 코드 상수 규칙을 더한다(`DOCKERFILE_NO_BUILD_RULE`처럼 템플릿을 바꿔도 빠지지 않게): 최종 런타임은 공식 `tomcat` 이미지, 설정 덮어쓰기 장치(엔트리포인트 스크립트·`context.xml`의 Loader/Resources)를 만들지 말 것.
   - 생성(재생성 포함)이 끝난 뒤 최종 단계 `FROM`이 `tomcat:<major>`인지 확인하고, Dockerfile 끝에 `RUN mkdir -p /opt/netis-override/WEB-INF/classes`와 전역 `conf/context.xml` 수정을 붙인다 — 7이면 `VirtualWebappLoader`(`virtualClasspath=/opt/netis-override/WEB-INF/classes`, `searchVirtualFirst=true`), 8 이상이면 `PreResources`(`DirResourceSet`, `base=/opt/netis-override/WEB-INF/classes`, `webAppMount=/WEB-INF/classes`). Tomcat이 아니면 실패. (`appendJavaLocale`과 같은 자리)
4. `LocalDockerTarget`: `files`가 있으면 `docker run -d` 대신 `docker create`(같은 인자) → `docker cp <임시>/override/. <컨테이너>:/opt/netis-override/` → `docker start`. 임시 폴더는 cp 뒤 바로 지운다. 컨테이너 사용자가 읽을 수 있는 권한으로 둔다. 헬스체크·`docker update --restart`는 그대로(같은 컨테이너라 재시작해도 파일이 남는다).
5. 배포 로그: `[DB 연결] 주 DB = 현장 maria DB (MARIADB 192.0.2.10:53306/netis) → WEB-INF/classes/config/jdbc_maria.properties 3항목` — 값·비밀번호는 쓰지 않는다. `-e` 에코는 지금처럼 값을 가린다.

## 11. 화면

- **배포 창**(`DeployEnvDialog.vue`, 판정은 `composables/deployEnvForm.ts`): §7.2. 배포 설정이 없으면 지금 화면 그대로.
- **레포 카탈로그 관리**(`pages/admin/repo-catalog.vue`): "배포 설정" 묶음 — DB 자리·변수 편집 폼, "다른 레포의 배포 설정 복사"(6.x 레포가 많아 같은 정의를 반복 입력하지 않게).
- **작업 상세**: 배포 정보에 고른 DB 연결 이름 칩.
- `frontend/scripts/hydration-smoke.mjs`의 `deploy-env`·카탈로그 목 갱신.

## 12. 보안

- DB 비밀번호는 claim 응답(워커 API 키, 사내망)으로만 워커에 가고, `task` 행·배포 로그·이미지에는 없다.
- 컨테이너 쓰기 레이어에는 파일로 있다 — `docker exec`·`docker cp`로 읽힌다. 지금 `-e` 값이 `docker inspect`로 보이는 것과 같은 수준이다. 환경변수 대상은 여전히 `-e`라 `docker inspect`에 보인다.
- 고를 수 있는 연결은 공용 + 배포하는 관리자 본인 것으로 한정한다. 다른 관리자의 개인 연결은 이미 그 작업에 저장된 선택일 때만 유지된다(볼 수 있는 건 이름·host:port/db뿐).

## 13. 배포 순서

API(V50 — 카탈로그에 배포 설정이 없으면 동작 변화 없음) → 이 PC 워커(진행 중 배포가 없을 때, claim URL에 `deployOverrides` 확인) → 프론트(`.output` 재빌드) → 관리 화면에서 카탈로그 7(Netis6.7)부터 배포 설정 입력 → 작업 #54를 "현장 maria DB"로 시험 배포. 원격 워커 2대는 배포를 받지 않아 그대로.

## 14. 시험

- **구현 전 확인(먼저)**: 10.1.1.75에서 `tomcat:7.0.109-jdk8-openjdk`와 `tomcat:8.5-jre8-temurin`에 netis_v6.7 `-Pdev` WAR + §10 방식(create → cp → start)으로 띄워 ① 앱이 사본의 DB(`192.0.2.10` 또는 latest)로 붙는지 ② 이미지 안 WAR가 그대로인지 ③ 원격 `DOCKER_HOST`에서 `docker cp`가 되는지 ④ 컨테이너 사용자(예: `netis`)가 사본을 읽는지 본다. 결과에 따라 §10의 Tomcat 설정 방식을 고친다.
- 단위: 카탈로그 설정 검증, 값 조립(자리표시자·`{값}`·이미 전체 형식인 값), Properties 덧붙이기·이스케이프, `FROM tomcat` 판별·붙일 블록, create/cp/start 인자, §7.5 실패 사유, claim 가드, 마스킹, 미리 채움 규칙(§9).
- 라이브: #54를 현장 maria DB로 배포 → 배포 로그 `[DB 연결]` 줄, 컨테이너 로그의 연결 주소, 공개 주소 로그인 화면.

**사전 확인 결과(2026-11-24, 10.1.1.75)** — `netis-task-49:61940e8`의 WAR(`-Pdev`, 원본 `192.0.2.20`)를 `tomcat:7.0.109-jdk8-openjdk`와 `tomcat:8.5-jre8-temurin`에 넣고 `USER netis`(uid 1001, root 아님)로 띄움. 사본 = 원본 끝에 `jdbc.url`(latest)·`jdbc.username=symphony_spike`·`jdbc.password=x`를 덧붙인 것. `docker create` → (PowerShell, Windows `docker.exe`, `C:\…\ovr\.`) `docker cp … <ctr>:/opt/netis-override/` → `docker start`.
- 두 버전 모두 `Access denied for user 'symphony_spike'@'10.1.1.75'` 3회 — 사본을 WAR 것보다 먼저 읽었다. `192.0.2.20`·`Connection refused` 0회.
- Tomcat 7 `VirtualWebappLoader`(`virtualClasspath=/opt/netis-override/WEB-INF/classes`, `searchVirtualFirst=true`)와 8.5 `<Resources><PreResources className="org.apache.catalina.webresources.DirResourceSet" base="/opt/netis-override/WEB-INF/classes" webAppMount="/WEB-INF/classes"/></Resources>`를 `conf/context.xml`의 `</Context>` 앞에 넣는 §10 방식 그대로 동작.
- `docker cp`로 들어간 파일은 소유 `0:0`, 모드 `0755` — root 아닌 사용자가 읽는다(추가 chmod 불필요).
- 이미지 안 `ROOT.war` SHA-256이 원본 이미지와 같다(`5cdbe360…`).
- 같은 DB(`latest`)에 없는 계정으로 로그인 시도 6회 외 접근 없음. 시험 이미지·컨테이너는 지움.

## 15. 빌더에게 남긴 것

- JSON 칸 이름·검증 문구·화면 배치 세부.
- 생성본이 스스로 Loader/Resources를 건드렸을 때 재생성 1회 후 실패할지, 바로 실패할지.
- 사본 파일 권한 처리 방법(Dockerfile에서 폴더 권한 vs cp 뒤 처리).
- "다른 레포 설정 복사" UI 모양.

## 16. 작성자 판단 (사용자 확인용)

1. 고를 수 있는 연결 = 공용 + 배포하는 관리자 본인 것 (+ 이 작업에 이미 저장된 선택).
2. 기본 선택 = 이 작업 저장값 → 같은 레포 직전 배포. 작업에 고른 DB 연결은 쓰지 않음.
3. 배포 설정이 있는 레포는 다른 작업의 자유 env 행을 미리 채우지 않음.
4. 설정 파일 대상은 `WEB-INF/classes/` 아래 `.properties`만, Tomcat 7·8+만, 미리 빌드 WAR 필수.
5. 원본 끝에 덧붙이는 방식(Properties의 마지막 값 우선).
6. 파일 전달은 `docker create → docker cp → docker start`(비밀번호를 env·이미지에 두지 않음).
7. 구버전 워커 claim 가드(`supports=deployOverrides`).
8. "고르지 않음"도 저장하고, 저장된 자리가 있으면 직전 배포를 보지 않음(검토에서 나온 것 — 조용한 DB 변경 방지).
9. 이름이 겹치는 env 행은 배포 설정 행으로 합치고, 조립 때 배포 설정이 이김. 쓸모없어진 `JDBC_URL` 같은 행은 그대로 둠. 예전 평문 비밀번호 행은 자동으로 지우지 않음(검토에서 나온 것).
10. URL 안 계정은 `{username:url}`·`{password:url}`로 퍼센트 인코딩(검토에서 나온 것 — 강원 `DASH_DB_URL`이 계정을 URL에 넣음).
