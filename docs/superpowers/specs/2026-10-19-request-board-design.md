# 요청 게시판(기능·MCP·레포 추가 요청) 설계

- 날짜: 2026-10-19
- 상태: 사용자 검토 대기 (brainstorming: 조사 → 관리자 레포 추가 방식 시안 A/B/C 비교 → "B: 목록에서 클릭만으로 추가 + 후보 없으면 검색" 채택 → 요청 종류 3가지 · 본인 요청만 조회 · 양방향 답글 확정)
- 범위: netisMaker 백엔드(Java, API 프로파일만) + 프론트엔드(Nuxt). 워커·인터뷰 서비스 변경 없음
- 선행 스펙: `2026-09-21-gitlab-support-design.md`(사내 GitLab 1곳 · `GITLAB_BASE_URL`/`GITLAB_TOKEN`)

## 1. 목적

곧 Symphony를 공개 주소로 열면 사용자에게서 "이런 기능이 있으면 좋겠다", "이 MCP 서버를 쓰고 싶다", "이 레포도 작업 대상으로 넣어 달라"는 요청이 들어온다. 지금은 이런 요청을 받을 창구가 없고, 레포·MCP 카탈로그는 관리자 화면에서만 추가할 수 있다.

이 기능은 사용자가 **웹에서 직접 요청을 남기고**, 관리자가 메신저나 메일 없이 **같은 화면에서 확인·처리**하도록 한다. 레포 추가 요청은 그중에서도 가장 자주 오고 처리가 기계적이므로, **드롭다운에서 후보 레포를 한 번 클릭하면 바로 카탈로그에 등록**될 만큼 간편해야 한다.

## 2. 목표 · 비목표 · 반목표

**목표**
- 로그인한 사용자 누구나 요청을 남긴다. 요청 종류는 **기능 추가 / MCP 추가 / REPO 추가** 셋 중에서 고른다.
- 사용자는 **자기 요청만** 본다. 관리자는 모든 요청을 보고 종류·상태로 거른다.
- 요청마다 **답글 스레드**가 있고, 요청자와 관리자 모두 쓸 수 있다.
- 관리자는 상태를 **접수 → 검토중 → 처리완료 / 반려**로 바꾼다. 반려할 때는 사유가 필수다.
- **REPO 추가**는 관리자 목록 각 줄의 "추가 ▾" 드롭 버튼에서 처리한다.
  - 버튼을 열면 요청 내용으로 추측한 사내 GitLab 후보 레포가 보인다.
  - 후보를 클릭하면 레포 카탈로그에 즉시 등록되고, 요청은 처리완료가 된다.
  - 후보가 없거나 틀리면 같은 드롭다운 안의 검색창에서 GitLab 프로젝트를 찾아 클릭한다.
- **기능 추가·MCP 추가**는 관리자가 보고 직접 반영한다. 게시판은 상태와 답글만 관리한다.
- 메일 없이 웹만으로 새 글을 알아챌 수 있게 한다. 관리자 탭에는 미처리 건수 배지를, 목록에는 "새 답글" 표시를 둔다.

**비목표 (이번 범위 밖)**
- MCP 카탈로그 자동 등록. MCP는 이름·URL·transport를 관리자가 판단해야 하므로 기존 MCP 카탈로그 화면에서 직접 추가한다.
- 기능 요청을 Symphony 작업(task)으로 바꾸는 연결.
- 첨부파일, 메일 알림, 실시간 푸시(SSE)나 폴링 갱신. 화면은 들어올 때와 행동한 뒤에만 다시 읽는다.
- 다른 사용자 요청 열람, 공감·투표, 공개 게시판.
- GitHub 레포 후보 검색. 후보와 검색은 사내 GitLab만 다룬다. GitHub 레포 요청은 관리자가 기존 레포 카탈로그 화면에서 직접 등록하고, 요청 상태만 바꾼다.

**반목표 (이렇게 되면 기능은 돌아가도 실패)**
- 레포 추가 한 건을 처리하는 데 폼 입력이나 화면 이동이 필요해지는 것. 기본 경로는 "드롭 버튼 → 클릭" 두 번이다.
- 일반 사용자가 사내 GitLab 프로젝트 목록을 보게 되는 것. GitLab 조회는 관리자 API에서만 하고, 사용자 화면에는 GitLab 이름이 나가지 않는다. 등록이 끝난 뒤 "등록된 레포" 표시만 예외다.
- 사용자가 다른 사람의 요청이나 답글을 볼 수 있는 경로가 생기는 것. 목록, 상세, 답글, 읽음 처리 모두 서버가 소유권을 검사한다.
- GitLab 토큰이 응답, 로그, 예외 메시지에 새는 것.

## 3. 확정된 결정

| 결정 | 내용 | 출처 |
|---|---|---|
| 요청 종류 | 기능 추가(`FEATURE`) · MCP 추가(`MCP`) · REPO 추가(`REPO`) | 사용자 |
| 열람 범위 | 사용자는 본인 요청만, 관리자는 전체 | 사용자 |
| 답글 | 요청자·관리자 양방향 | 사용자 |
| 기능·MCP 처리 | 관리자가 확인 후 직접 반영하고, 게시판은 상태와 답글만 | 사용자 |
| REPO 처리 | 관리자 목록의 "추가 ▾" 드롭 버튼에서 후보를 클릭해 즉시 등록하고, 후보가 없으면 같은 드롭다운에서 검색 | 사용자 (시안 B + 검색) |
| 상태 | 접수 · 검토중 · 처리완료 · 반려 | 설계자 제안, 사용자 확인 |
| 알림 | 관리자 탭의 미처리(접수) 건수 배지 + 목록의 "새 답글" 표시. 메일 없음 | 설계자 제안, 사용자 확인 |
| 등록 기본값 | 별칭은 GitLab 프로젝트 이름, 기본 브랜치는 GitLab `default_branch`. 수정은 기존 레포 카탈로그 화면에서 | 설계자 제안, 사용자 확인 |

## 4. 화면

### 4.1 내비게이션
- 상단 탭(데스크톱)에 **"요청"**을 추가한다. 위치는 "질문" 바로 뒤이고, 로그인 사용자 전원에게 보인다(`frontend/layouts/default.vue`).
- 모바일 하단 바에도 "요청"(아이콘 `campaign` 등)을 같은 위치에 넣는다.
- 관리자에게는 "요청" 탭에 **확인할 건수 배지**를 붙인다(0이면 숨김). 이 값은 레이아웃 마운트 때와 라우트가 바뀔 때 `GET /api/admin/requests/summary`로 다시 읽는다.
  - 확인할 건수 = 접수 상태이거나, 상태와 무관하게 관리자가 아직 읽지 않은 사용자 활동이 있는 요청의 수(중복 없이 셈).
  - 이렇게 하면 처리완료·반려된 요청에 사용자가 "아직 안 돼요"라고 답글을 달아도 관리자가 알아챌 수 있다(설계자 판단, 빌더 점검 반영).

### 4.2 `/requests` — 목록 (사용자·관리자 공용 페이지)
- **사용자**
  - 본인 요청 목록을 최근 활동 순으로 보여 준다.
  - 각 줄에는 종류 칩, 제목, 상태 칩, 마지막 활동 시각, "새 답글" 점을 표시한다.
  - 상단에 "새 요청" 버튼이 있다.
- **관리자**
  - 같은 페이지에서 모든 요청을 보여 준다. 작업 탭의 "내 것만" 토글 관례를 따르되 기본값은 **전체**다.
  - 종류 필터와 상태 필터가 있고, 상태 필터의 기본값은 "접수 + 검토중"이다. 다만 **안 읽은 활동이 있는 요청은 상태 필터와 상관없이 기본 화면에 함께 나온다**("안 읽은 건 포함" 토글, 기본 켬). 그래야 배지 숫자와 화면에 보이는 줄이 맞는다.
  - 각 줄에 요청자 ID를 함께 표시한다.
  - **REPO 종류이고 상태가 접수·검토중인 줄**에는 "추가 ▾" 드롭 버튼이 있다(§6). 관리자 본인이 남긴 요청이어도 같다.
- 데스크톱은 `q-table`, 모바일(`$q.screen.lt.md`)은 카드 목록으로 그린다. 템플릿은 `pages/admin/repo-catalog.vue`(테이블+카드)와 `pages/tasks/index.vue`(필터·토글)다.
- 줄을 누르면 상세 화면으로 간다. 드롭 버튼을 눌렀을 때는 이동하지 않는다(`@click.stop`).

### 4.3 새 요청 다이얼로그
- 입력 항목
  - 종류(라디오 또는 세그먼트, 필수)
  - 제목(필수, 200자)
  - 내용(필수, 마크다운 평문, 10000자)
  - 종류가 REPO일 때만 **"레포 이름 또는 주소"**(선택, 500자). 안내 문구는 "알면 적어 주세요. 몰라도 괜찮아요."
- 셸은 `AdminFormDialog` 패턴을 따른다(모바일 전체화면). 다만 사용자용이므로 컴포넌트 이름이나 위치를 바꿀지는 구현자가 판단한다.

### 4.4 `/requests/[id]` — 상세
- **헤더**: 종류, 제목, 상태, 요청자, 작성 시각
- **본문**: `renderMarkdown`으로 렌더한다.
- **REPO 요청**: 레포 힌트를 표시한다. 처리완료이고 등록된 카탈로그 항목이 있으면 "등록된 레포: <별칭> (<gitUrl>)"을 보여 준다. 항목이 나중에 삭제됐다면 "(카탈로그에서 삭제됨)"으로 표시한다.
- **스레드**: 답글과 시스템 기록을 시간순으로 보여 준다.
  - 관리자 답글에는 "관리자" 칩을 붙인다.
  - 시스템 기록(상태 변경, 레포 등록)은 한 줄 회색 문구로 표시한다.
- **답글 입력창**: 요청자 본인과 관리자에게 보이고, 모든 상태에서 쓸 수 있다. 처리완료나 반려 상태에서 답글이 달려도 상태는 저절로 바뀌지 않는다.
- **요청자 본인 + 접수 상태**: "수정"과 "취소" 버튼이 있다. 취소는 `confirmDialog`로 확인한 뒤 요청을 삭제한다(§5.3).
- **관리자**: 상태 변경 메뉴(검토중 / 처리완료 / 반려 / 접수로 되돌리기)가 있다.
  - 반려를 고르면 사유 입력 다이얼로그가 뜨고, 사유는 필수다.
  - REPO 요청이면 상세 화면에도 같은 "추가 ▾" 드롭 버튼을 둔다(목록과 같은 컴포넌트).
- 상세 화면을 열면 열람자 쪽 읽음 시각을 갱신한다(§5.4).

## 5. 데이터

마이그레이션 **`V30__request_board.sql`**. 번호에 관해서는 §10을 본다.

```sql
-- 요청 게시판 (스펙 2026-10-19)
CREATE TABLE IF NOT EXISTS com.request_post (
    id                      BIGSERIAL PRIMARY KEY,
    kind                    VARCHAR(16)  NOT NULL,            -- FEATURE | MCP | REPO
    title                   VARCHAR(200) NOT NULL,
    content                 TEXT         NOT NULL,
    repo_hint               VARCHAR(500),                     -- REPO일 때만, 선택
    status                  VARCHAR(16)  NOT NULL,            -- 접수 | 검토중 | 처리완료 | 반려
    requester_id            VARCHAR(20)  NOT NULL REFERENCES com."user"(user_id),
    repo_catalog_id         BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL,
    repo_git_url            VARCHAR(1000),                    -- 등록 당시 스냅샷(카탈로그 삭제 후에도 표시)
    last_requester_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_admin_activity_at  TIMESTAMPTZ,
    requester_read_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    admin_read_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_request_post_requester ON com.request_post(requester_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_request_post_status    ON com.request_post(status, updated_at DESC);

CREATE TABLE IF NOT EXISTS com.request_comment (
    id          BIGSERIAL PRIMARY KEY,
    request_id  BIGINT      NOT NULL REFERENCES com.request_post(id) ON DELETE CASCADE,
    kind        VARCHAR(16) NOT NULL,                         -- COMMENT | SYSTEM
    author_id   VARCHAR(20) REFERENCES com."user"(user_id),   -- SYSTEM 기록도 행위자(관리자) id를 남긴다
    author_admin BOOLEAN    NOT NULL DEFAULT false,           -- 작성 시점 관리자 여부 박제
    body        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_request_comment_request ON com.request_comment(request_id, created_at);
```

### 5.1 엔티티와 enum
- `RequestPost`, `RequestComment` 엔티티를 기존 엔티티 관례로 만든다(Lombok, `schema = "com"`, `OffsetDateTime`, 정적 팩토리 `create(...)`).
- `RequestKind` enum은 DB에 이름을 그대로 저장한다(`@Enumerated(STRING)`).
- `RequestStatus`는 `TaskStatus` 패턴을 따른다. Java 이름(`RECEIVED`, `REVIEWING`, `DONE`, `REJECTED`)과 DB 한글 값(접수, 검토중, 처리완료, 반려)을 `@Converter(autoApply = true)`로 연결한다.
- `ddl-auto: validate`이므로 컬럼 타입과 길이를 마이그레이션과 정확히 맞춘다.

### 5.2 상태 전이 (관리자만)
- 접수 → 검토중 | 처리완료 | 반려
- 검토중 → 처리완료 | 반려 | 접수
- 처리완료 → 검토중 (다시 열기)
- 반려 → 검토중 (다시 열기)
- 같은 상태로 바꾸려 하면 아무것도 하지 않는다(no-op). 허용되지 않는 전이는 409로 막는다.
- 모든 전이는 SYSTEM 기록 한 줄을 남긴다. 예: "관리자(admin)가 상태를 검토중으로 변경". 반려라면 "… 반려 — 사유: …".
- 반려 사유는 SYSTEM 기록 본문에 들어간다. 별도 컬럼은 두지 않는다.

### 5.3 수정·취소 (요청자만, 접수 상태에서만)
- **수정**: 제목, 내용, 레포 힌트만 바꿀 수 있고 종류는 바꿀 수 없다. 접수가 아니면 409.
- **취소**: 요청을 **완전히 삭제**하며 답글도 CASCADE로 함께 지워진다. 접수가 아니면 409.
  - 이 방식은 설계자 판단이다. 접수 상태는 관리자가 아직 손대지 않은 상태라 남길 기록이 없다고 본다. 다만 관리자가 이미 답글을 달았을 수 있으므로, 접수 상태라도 **관리자 답글이 하나라도 있으면 취소 대신 409**("관리자 답글이 있는 요청은 취소할 수 없습니다")를 돌려준다.

### 5.4 읽음과 "새 답글"
- 요청자가 답글을 쓰거나, 요청을 만들거나 수정하면 `last_requester_activity_at = now()`
- 관리자가 답글을 쓰거나, 상태를 바꾸거나, 레포를 등록하면 `last_admin_activity_at = now()`
- 상세 조회 시 열람자가 요청자 본인이면 `requester_read_at = now()`, 관리자이면 `admin_read_at = now()`. 관리자가 자기 요청을 보면 두 값을 모두 갱신한다.
  - 관리자는 여러 명이어도 읽음 시각을 하나만 공유한다. 지금 관리자는 사실상 한 명이므로 사람별 읽음 테이블은 두지 않는다(설계자 판단).
- 목록 응답의 `unread` 값
  - 사용자: `last_admin_activity_at > requester_read_at`
  - 관리자: `last_requester_activity_at > COALESCE(admin_read_at, '-infinity')`
  - 관리자가 아직 한 번도 열지 않은 새 요청도 "새 답글" 점으로 표시된다.
- 목록 정렬은 `GREATEST(last_requester_activity_at, COALESCE(last_admin_activity_at, created_at)) DESC`이다. 구현자는 `updated_at`을 이 값으로 유지해도 된다.

## 6. REPO 추가 — 후보 · 검색 · 클릭 등록

### 6.1 GitLab 프로젝트 조회 (신규 `GitLabProjectSearch`, API 프로파일)
- `GET {GITLAB_BASE_URL}/api/v4/projects?search=<q>&simple=true&archived=false&order_by=last_activity_at&per_page=10`, 헤더는 `PRIVATE-TOKEN`이다.
- 프로젝트 하나를 조회할 때는 `GET /api/v4/projects/{URL 인코딩된 전체 경로}`를 쓴다.
- 결과 항목: `id`, `name`, `path_with_namespace`, `http_url_to_repo`, `default_branch`, `description`, `last_activity_at`
- HTTP 클라이언트는 `java.net.http`를 쓰고 타임아웃은 10초다. 기존 `GitLabMrCloser`(워커 쪽)와 같은 방식이다.
- `GITLAB_BASE_URL`이나 `GITLAB_TOKEN`이 비어 있으면 API가 **503 + "사내 GitLab이 설정되지 않았습니다"**를 돌려준다. 이때 프론트 드롭다운은 같은 문구만 보여 준다.
- GitLab 오류(401, 403, 5xx, 타임아웃)는 502로 바꾸고 메시지는 일반 문구로 둔다. 로그에는 상태 코드와 요청 경로만 남기고 응답 본문은 남기지 않는다.
  - `GitRemotes.mask`는 `://user:secret@` 모양만 가리므로 `PRIVATE-TOKEN` 헤더 방식에는 쓸모가 없다. 대신 **토큰 문자열을 예외 메시지·로그 문자열 조립에 아예 쓰지 않는다.** 방어선으로, 이 클라이언트에서 나가는 모든 메시지에 토큰 원문이 들어 있으면 `***`로 치환하는 헬퍼를 하나 둔다(테스트로 가드).

### 6.2 후보 추측
`GET /api/admin/requests/{id}/repo-candidates` → 최대 5개를 돌려준다.

1. `repo_hint`로 프로젝트를 직접 찾을 수 있으면 그 프로젝트를 맨 앞에 둔다.
   - 힌트가 사내 GitLab **전체 URL**이면(`RepoUrlParser.parse(hint, gitlabBaseUrl)`의 host가 `gitlab`) 그 `ownerRepo`(전체 경로)로 단건 조회한다.
   - 힌트가 URL이 아니고 `/`가 들어간 **경로 모양**(`group/project`, `a/b/c`)이면 `RepoUrlParser`를 거치지 않고 그 문자열 그대로 단건 조회를 시도한다. `RepoUrlParser`는 bare `a/b`를 항상 GitHub로, 3단계 이상 경로는 예외로 처리하기 때문이다.
   - 단건 조회가 404면 조용히 건너뛰고 2번으로 간다.
2. 검색어는 `repo_hint`, 제목, 내용에서 뽑는다.
   - 영숫자·`-`·`_`·`.`로 된 토큰 중 3자 이상만 쓴다.
   - 흔한 단어는 뺀다. 예: repo, repository, git, gitlab, http, https, com, the, and.
   - 우선순위는 힌트 → 제목 → 내용 순이고, 최대 3개 검색어로 각각 검색한다.
3. 결과를 프로젝트 id 기준으로 중복 제거하고, 먼저 나온 검색어 순서를 유지한 채 5개로 자른다.
4. 각 후보에 `registered` 값을 붙인다. 정식 URL(`RepoUrlParser`의 canonicalUrl)이 이미 `repo_catalog.git_url`에 있으면 true다.

- 한글만 있는 요청은 후보가 비는 것이 정상이다. 이때는 바로 검색창을 쓴다.
- 후보 결과는 캐시하지 않는다. 드롭다운을 열 때마다 조회한다.

### 6.3 검색
`GET /api/admin/gitlab-projects?search=<q>` (2자 이상, 그 미만은 400) → 최대 10개, `registered` 값 포함.

### 6.4 클릭 등록
`POST /api/admin/requests/{id}/add-repo`, 본문은 `{ "gitlabProjectId": 123 }`

서버는 한 트랜잭션 안에서 다음을 처리한다.
1. 요청이 REPO 종류이고 상태가 접수 또는 검토중인지 확인한다. 아니면 409.
2. GitLab에서 프로젝트를 id로 다시 조회한다. 클라이언트가 보낸 URL이나 이름은 믿지 않는다.
3. `RepoCatalogService.create(...)`를 재사용해 등록한다.
   - `alias`: 프로젝트 `name`. 같은 별칭이 이미 있으면 `path_with_namespace`를 쓰고, 그것도 있으면 409("이미 존재하는 별칭 — 레포 카탈로그 화면에서 등록하세요").
   - `gitUrl`: **`GITLAB_BASE_URL` + `/` + `path_with_namespace` + `.git`**으로 만든다. GitLab이 돌려주는 `http_url_to_repo`는 GitLab의 `external_url` 설정을 따르므로 호스트가 `GITLAB_BASE_URL`과 다를 수 있고, 그러면 `RepoUrlParser`가 `other`로 판정해 작업 등록에 쓸 수 없는 항목이 생긴다(설계자 판단, 빌더 점검 반영). §6.2-4의 `registered` 판정도 같은 방식으로 만든 URL을 정식화해 비교한다.
   - `defaultBranch`: `default_branch`
   - `description`: `"요청 #<id>에서 등록"`
   - `enabled`: true
   - 산출물 설정은 건드리지 않는다.
   - **이미 같은 git URL이 카탈로그에 있으면** 등록은 건너뛰고 그 항목에 연결만 한다. 이 경우도 성공으로 본다. 사용자가 이미 있는 레포를 요청한 것이므로 처리완료로 마무리하는 편이 맞다고 판단했다(설계자 판단).
4. 요청에 `repo_catalog_id`와 `repo_git_url`을 기록하고 상태를 처리완료로 바꾼다. SYSTEM 기록은 "관리자(admin)가 레포 카탈로그에 <별칭> (<path>) 등록 — 처리완료" 형식이다.
5. 응답은 갱신된 요청 View다. 프론트는 성공 알림("<별칭> 등록됨")을 띄우고 목록을 다시 읽는다.

> GitLab 조회(외부 HTTP)는 트랜잭션 **밖에서** 먼저 하고, DB 쓰기만 트랜잭션으로 묶는다. 구현자는 서비스를 나누어도 된다.

### 6.5 드롭다운 UI (`RepoAddDropdown.vue`, 신규)
- `q-btn-dropdown`(라벨 "추가", dense)를 열 때 후보를 조회한다. 조회 중에는 스피너를 보여 준다.
- 위쪽에는 후보 목록을 둔다. 각 줄은 `path_with_namespace`(굵게), 기본 브랜치, 설명 한 줄로 구성된다.
  - `registered`인 후보에는 "등록됨" 칩을 붙인다. 이 후보도 클릭할 수 있고, 클릭하면 §6.4-3의 "연결만" 경로를 탄다.
- 아래쪽에는 검색 입력창을 둔다. 디바운스는 300ms이고, 검색 결과는 같은 형식으로 후보 영역을 대신한다.
- 후보가 0개면 "추측한 후보가 없어요. 검색해 주세요."를 보여 주고 검색창에 포커스한다.
- 항목을 클릭하면 확인 다이얼로그 없이 바로 등록한다. 반목표 "두 번 클릭"을 지키기 위해서다. 등록 중에는 버튼을 잠근다.
- GitLab 미설정(503)이면 드롭다운 안에 안내 문구만 보여 준다.

## 7. API 요약

| 메서드·경로 | 권한 | 설명 |
|---|---|---|
| `GET /api/requests` | 인증 | 본인 요청 목록(`unread` 포함). 관리자가 `all=true`를 주면 전체, `kind`·`status`(다중) 필터 |
| `POST /api/requests` | 인증 | 생성. 상태는 접수 |
| `GET /api/requests/{id}` | 본인 또는 관리자 | 상세 + 스레드. 읽음 갱신 |
| `PUT /api/requests/{id}` | 본인, 접수 상태 | 제목·내용·힌트 수정 |
| `DELETE /api/requests/{id}` | 본인, 접수 상태, 관리자 답글 없음 | 취소(삭제) |
| `POST /api/requests/{id}/comments` | 본인 또는 관리자 | 답글. 본문 1~5000자 |
| `POST /api/admin/requests/{id}/status` | 관리자 | `{status, reason?}`. 반려면 reason 필수 |
| `GET /api/admin/requests/summary` | 관리자 | `{received: n, attention: m}` — 배지는 `attention`(§4.1) |
| `GET /api/admin/requests/{id}/repo-candidates` | 관리자 | §6.2 |
| `GET /api/admin/gitlab-projects?search=` | 관리자 | §6.3 |
| `POST /api/admin/requests/{id}/add-repo` | 관리자 | §6.4 |

- 남의 요청에 접근하면 **404**를 준다. 존재 여부를 숨기기 위해서다.
  - 기존 작업 API는 403(`TaskException.forbidden`)을 쓰므로 관례와 다르다. 이 차이는 의도한 것이고, 게시판은 남의 요청 id를 추측하는 것 자체를 막는다.
- 관리자 판정은 `AuthContext.isAdmin`, 사용자 식별은 `AuthContext.requireUserId`를 쓴다. 관리자 경로는 `@PreAuthorize("hasAuthority('ROLE_ADMIN')")`.
- 사용자용 View에는 `requesterId`, `repoGitUrl`, 상태, 스레드가 들어간다. 사용자 응답에 GitLab 후보 정보는 절대 포함하지 않는다.
- 상한: 사용자 한 명당 열린(접수·검토중) 요청은 20건까지다. 초과하면 429이고, 설정 키는 `app.request-board.max-open-per-user`다. 공개 주소에서 도배를 막기 위한 최소 장치다(설계자 판단).

## 8. 테스트

- **서비스 단위** (Mockito)
  - 상태 전이표 전체
  - 반려 사유 필수
  - 수정·취소 가드(접수만, 관리자 답글 있으면 취소 거부)
  - 읽음/unread 계산
  - 후보 추측: 토큰 추출, 흔한 단어 제외, 힌트 URL 우선, 중복 제거, 5개 상한
  - add-repo
    - 정상 등록
    - 별칭 충돌 시 경로로 대체, 그것도 충돌하면 409
    - 같은 URL이 있으면 연결만
    - REPO가 아니거나 종결 상태면 409
- **GitLab 클라이언트**: `GitLabMrCloserTest`처럼 `com.sun.net.httpserver.HttpServer`로 확인한다.
  - 검색과 단건 조회 파싱
  - 토큰 헤더가 실리는지
  - 미설정이면 503 매핑, 오류면 502 매핑
  - 예외 메시지에 토큰이 없는지
- **통합 테스트**: Testcontainers + `jwt()`를 쓴다. 권한(403) 검사는 `RepoCatalogSecuritySurfaceTest`를 본뜬다.
  - 소유권(404) 검사는 그 방식으로는 안 된다. `AuthContext.requireUserId`가 username → sub → user_id 순으로 읽고 `jwt()` 기본 sub가 `"user"`라서, `user_id` 클레임만 바꾸면 모든 사용자가 같은 사람으로 해석된다. `DbConnectionApiIntegrationTest`처럼 `subject`나 `username` 클레임을 사용자별로 준다.
  - user1이 user2의 요청을 조회, 수정, 답글하면 404
  - 비관리자가 `/api/admin/requests/**`, `/api/admin/gitlab-projects`를 부르면 403
  - 관리자 전체 목록과 필터
  - 흐름: 생성 → 관리자 답글 → 사용자 unread → 상세 열람 후 read
  - V30이 `validate`를 통과하는지(컨텍스트 기동으로 확인)
- **프론트** (vitest)
  - `RepoAddDropdown`: 후보, 빈 후보 → 검색, 등록됨 칩, 클릭 시 POST, 503 문구
  - 목록 페이지에서 관리자와 사용자 분기(`useHydrationSafeAuth`)
  - `test/layout-default.spec.ts`: 탭과 배지 추가
  - `scripts/hydration-smoke.mjs`: 경로 `/requests`, `/requests/1`과 새 API 목 응답 추가

## 9. 기존 코드와의 접점

- `RepoCatalogService.create`는 수정하지 않고 호출만 한다. 중복 별칭/URL 409는 그대로 쓰고, add-repo 쪽이 먼저 조회해서 대체 별칭과 "연결만"을 판정한다.
- `RepoUrlParser`로 GitLab URL을 판정하고 정식 URL을 비교한다.
- GitLab 오류 메시지는 `GitRemotes.mask`가 아니라 토큰 원문 치환 헬퍼로 가린다(§6.1).
- 프론트 공용 도구: `useApi`, `confirmDialog`, `renderMarkdown`, `useHydrationSafeAuth`, `AdminFormDialog` 셸
- `layouts/default.vue`의 탭 두 곳과 배지
- CLAUDE.md의 "자주 보는 코드" 표에 한 줄을 추가한다.

## 10. 배포와 순서

- **마이그레이션 번호**: `V29`는 열린 PR #95(`feature/implementation-mode`, `V29__task_implementation_mode.sql`)가 쓰고 있으므로 이 기능은 **V30**을 쓴다.
  - Flyway `outOfOrder`가 꺼져 있으므로 **PR #95를 먼저 병합해야 한다.**
  - 이 기능이 먼저 나가야 하면 병합 직전에 번호를 V29로 바꾸고, #95를 V30으로 올린다.
- 배포 순서: API(V30) 재기동 → 프론트 `.output` 재빌드. 워커와 인터뷰 서비스는 바꾸지 않는다.
- 라이브 API에는 `GITLAB_BASE_URL`과 `GITLAB_TOKEN`이 이미 설정되어 있다. 이 토큰의 `api` scope로 프로젝트 조회가 된다.

## 11. 구현자에게 맡기는 것

- 컴포넌트와 서비스 분리 방식(예: `RequestBoardService` + `RepoRequestService`), DTO record 이름
- 모바일 하단 바 아이콘, 칩 색상, 문구의 세부 표현
- 목록 페이지네이션. 초기에는 최근 200건 상한만 두어도 된다.
- `updated_at`을 정렬 키로 유지할지, 정렬식을 쿼리에 직접 쓸지
