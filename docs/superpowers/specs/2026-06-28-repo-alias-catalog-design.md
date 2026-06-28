# 레포 한글 별칭 카탈로그 — 설계 (Repo Alias Catalog)

- 작성일: 2026-06-28
- 상태: 설계 확정 (구현 플랜 대기)
- 브랜치: `feat/repo-alias-catalog`
- 관련: 후속 Feature 2(공개 배포 주소)는 별도 스펙으로 진행 — `2026-06-XX-public-deploy-address-design.md`(예정)

## 1. 배경 & 목표

작업/대화형 분석 등록 폼은 현재 **자유 입력**으로 GitHub 레포를 받는다(`frontend/pages/tasks/index.vue`의 `q-input` "GitHub 레포", `owner/repo` 또는 전체 URL 허용 → `normalizeRepo` 정규화 → `/api/repos/branches`로 브랜치 조회). 사용자가 매번 URL/슬러그를 외워 입력해야 하고, 오타·비공개 레포 혼선이 생긴다.

**목표**: 등록 시 URL을 입력하는 대신, 관리자가 큐레이션한 **한글 별칭** 목록에서 레포를 **선택**한다. 예) `https://github.com/micthebick84/netis7.0.git` → 별칭 `Netis7.0`. 이를 위해 (Git URL ↔ 한글 별칭) 매핑을 관리자가 만들고 관리하는 **레포 카탈로그**를 도입한다.

이 설계는 기존 **MCP 카탈로그**(`com.mcp_catalog` + `/api/admin/mcp-catalog` CRUD + `admin/mcp-catalog.vue` + 작업 등록 시 스냅샷 박제) 패턴을 거의 1:1로 따른다.

## 2. 범위 & 비목표

**범위**: 레포 카탈로그 테이블 + admin CRUD API/페이지 + 작업/인터뷰 등록 폼의 별칭 선택 전환 + Task 스냅샷.

**비목표 (YAGNI)**:
- 비-GitHub 호스트(GitLab/사내 git)의 **end-to-end 동작**. 스키마는 멀티호스트 대비(전체 URL 정식 저장)하되, 구현/배포 파이프라인은 여전히 GitHub `owner/repo` + `gh` CLI 전제이므로 비-GitHub 레포는 **저장은 허용·작업 등록은 차단**.
- GitHub API 자동 열거(org/유저 레포 자동 디스커버리). 출처는 관리자 수동 CRUD로 확정.
- 일반 사용자의 카탈로그 편집. 관리(CRUD)는 ROLE_ADMIN 전용(MCP 카탈로그와 동일).
- 자유 입력(직접 URL) 비상 통로. 등록 폼은 카탈로그 선택만 허용.

## 3. 핵심 결정 (브레인스토밍 합의)

| # | 결정 | 근거 |
|---|---|---|
| D1 | 출처 = **관리자 수동 카탈로그(CRUD)** | 사용자 의도("URL+Alias 매핑 생성"), 비공개 레포 동작, MCP 카탈로그 패턴 재사용 |
| D2 | 등록 폼 = **카탈로그 선택만**, 자유입력 제거 | 단일 운영자 시스템이라 사전 등록 부담 적음, 가장 깔끔 |
| D3 | 정식 식별자 = **전체 Git URL**, owner/repo는 파생 저장 | 멀티호스트 대비(미래 마이그레이션 회피) |
| D4 | Task 연결 = **스냅샷(git_url + repo_alias) + nullable FK** | MCP 스냅샷 철학과 일관, 이력/표시 안정, 파이프라인 무변경 |

## 4. 데이터 모델

### 4.1 신규 테이블 `com.repo_catalog`

```sql
CREATE TABLE IF NOT EXISTS com.repo_catalog (
    id              BIGSERIAL PRIMARY KEY,
    alias           VARCHAR(100) NOT NULL UNIQUE,   -- 한글 표시명. 예: 'Netis7.0'
    git_url         TEXT         NOT NULL UNIQUE,    -- 정식 식별자(전체 Git URL)
    host            VARCHAR(30)  NOT NULL DEFAULT 'github',  -- URL 파생: github | other
    owner_repo      VARCHAR(255),                   -- GitHub URL 파생 owner/repo. 비-GitHub면 NULL
    default_branch  VARCHAR(255),                   -- (선택) 비우면 등록 폼이 ls-remote 기본 사용
    description     TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    created_by      VARCHAR(20)  REFERENCES com."user"(user_id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_repo_catalog_enabled ON com.repo_catalog(enabled) WHERE enabled = true;
```

- `git_url` 정식 저장, `owner_repo`/`host`는 **업서트 시 1회 파싱**해 컬럼에 박제(런타임 재파싱 없음).
- `alias`·`git_url` 둘 다 UNIQUE — 별칭은 사람 키, URL은 중복 레포 방지.

### 4.2 Task 스냅샷 컬럼 추가

```sql
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS git_url         TEXT,
    ADD COLUMN IF NOT EXISTS repo_alias      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS repo_catalog_id BIGINT REFERENCES com.repo_catalog(id);
```

- `Task.githubRepo`(owner/repo)는 **그대로 유지** → `GitRepoCache`/`GitOpsService`/deploy 등 모든 파이프라인 읽기 사이트 **무변경**.
- `git_url`·`repo_alias`는 등록 시점 스냅샷(카탈로그가 이후 변경/삭제돼도 이력 안정). `repo_catalog_id`는 편의용 nullable FK(소스 오브 트루스는 스냅샷).
- 기존 작업 row는 세 컬럼 NULL → UI가 `githubRepo`로 폴백 표시.

## 5. API (MCP 카탈로그 컨트롤러 동형, `@Profile("api")`)

```
GET    /api/repo-catalog               isAuthenticated   활성(enabled) 항목만 — 등록 폼 드롭다운용
GET    /api/admin/repo-catalog         ROLE_ADMIN        전체
POST   /api/admin/repo-catalog         ROLE_ADMIN        생성 (URL 정규화 + owner/repo·host 파생)
PUT    /api/admin/repo-catalog/{id}    ROLE_ADMIN        수정
DELETE /api/admin/repo-catalog/{id}    ROLE_ADMIN        삭제
POST   /api/admin/repo-catalog/{id}/check  ROLE_ADMIN    라이브 git ls-remote 도달성 확인 (v1 포함)
```

- `RepoCatalogService`: 업서트 시 URL 정규화 + `owner/repo`·`host` 파생을 담당(서버측 권위 로직). 허용 입력형: `https://github.com/o/r`, `https://github.com/o/r.git`, `git@github.com:o/r.git`, `o/r`. 프론트 `normalizeRepo` 로직을 서버로 포팅.
- `/check`: 별칭 등록 시 PAT로 실제 접근 가능한지 즉시 검증(죽은 URL을 가리키는 별칭 사고 예방). 기존 `GitRefService.listBranches`(ls-remote) 재사용 가능.
- DTO: `RepoCatalogDto.View`(id, alias, gitUrl, host, ownerRepo, defaultBranch, description, enabled, createdAt, updatedAt) + `RepoCatalogDto.UpsertRequest`(alias[필수], gitUrl[필수], defaultBranch?, description?, enabled).

### 5.1 등록 계약 변경

- `TaskCreateRequest`·`CreateInterviewRequest`: `githubRepo`(자유 문자열, `@Pattern owner/repo`) → **`repoCatalogId`(Long, 필수)**. `githubBranch`는 유지.
- 백엔드(`TaskService`/`InterviewService` register)가 `repoCatalogId` → 카탈로그 조회 → `owner/repo`·`git_url`·`alias` 파생 후 Task/Interview 빌드 + 스냅샷 저장.
- 검증: 항목이 없음/비활성/삭제 → 400/409; `host != github`(owner_repo null) → 400 "현재 GitHub 레포만 분석/구현 가능".

## 6. 프론트엔드

- **신규 `frontend/pages/admin/repo-catalog.vue`** — `admin/mcp-catalog.vue` 미러: 항목 테이블 + 추가/수정 다이얼로그(별칭·Git URL·설명·활성) + 삭제 + "연결 확인"(`/check`) 버튼 + 빈 상태.
- **`frontend/layouts/default.vue`** 네비 탭: `<q-route-tab v-if="auth.isAdmin" to="/admin/repo-catalog" label="레포 카탈로그" />`.
- **`frontend/pages/tasks/index.vue` 등록 폼**:
  - 자유 입력 `q-input`(GitHub 레포) + `normalizeRepo`/`REPO_RE`/자유입력 `repoStatus` 검증 제거.
  - **별칭 `q-select`** 추가(`/api/repo-catalog`). 선택 시 해당 항목 `ownerRepo`로 **기존 `loadBranches()` 그대로 호출** → 브랜치 드롭다운 채움(브랜치 로직·`/api/repos/branches` 무변경). `default_branch`가 있으면 선택값 프리필.
  - 제출 시 `repoCatalogId` 전송(인터뷰 시작·작업 등록 양쪽).
  - 카탈로그 비었으면 "등록된 레포 없음 — 관리자 문의" 빈 상태(MCP 빈 상태 톤).
- **작업 목록 "레포" 컬럼** → `repoAlias` 표시(폴백 `githubRepo`). `TaskResponse`에 `repoAlias` 추가. 작업 상세도 동일.

## 7. 마이그레이션 & 하위호환

- **V14 마이그레이션**: `com.repo_catalog` 생성 + `com.task` 3컬럼 추가(§4). 데이터 마이그레이션 불필요(기존 작업은 NULL → 폴백).
- (선택) 시드 1건: 별칭 `Netis7.0` → `https://github.com/micthebick84/netis7.0.git`. 첫 실행 시 폼이 비지 않도록. 마이그레이션 또는 admin 수동.
- Flyway 규칙(프로젝트): 신규 컬럼은 nullable 또는 `NOT NULL DEFAULT`로 기존 row 안전(여기선 nullable).

## 8. 테스트 전략

- **백엔드 단위**: `RepoCatalogService` — URL 파싱(github/비github, 4개 입력형 → owner/repo·host), unique·중복 거부, enabled 필터. 등록 해석 — `repoCatalogId` → 스냅샷, 비활성/삭제/비github 거부 경로.
- **백엔드 컨트롤러/ACL**: admin vs 일반 사용자 접근 표면(기존 `InterviewSecuritySurfaceTest` 패턴), DTO 검증(alias 필수, gitUrl 형식).
- **프론트 Vitest**: 등록 폼(드롭다운이 카탈로그로 채워짐 · 선택 시 브랜치 로드 · 제출 페이로드에 `repoCatalogId`), admin CRUD 하니스(mcp-catalog 테스트 미러).
- 통합 테스트(Testcontainers)는 CI 전용(로컬 docker-java 비호환 이슈) — 단위/슬라이스 위주.

## 9. 엣지 케이스 & 에러 처리

- 제출 순간 항목이 비활성/삭제됨 → 400/409 + 명확한 메시지, 폼은 드롭다운 재로딩.
- 비-GitHub URL: 카탈로그 저장은 허용(`host='other'`, owner_repo NULL)되 작업 등록은 차단("현재 GitHub 전용").
- URL 정규화: 다양한 입력형을 정식 형태로 수렴 후 `owner/repo` 파생. 파싱 불가 시 업서트 400.
- 카탈로그 빈 상태(첫 실행/전부 비활성): 폼·admin 모두 빈 상태 안내.
- 별칭/URL UNIQUE 충돌 → 업서트 409 + 메시지.

## 10. 미해결 / 후속

- **Feature 2 (공개 배포 주소)**는 본 스펙 범위 밖 — 별도 설계로 진행. 현재 배포 URL은 `http://{publicHost}:{hostPort}`(publicHost 기본 localhost)이며, 외부 접속을 위해 per-deploy 서브도메인 + Cloudflare Tunnel wildcard + 로컬 리버스 프록시 방향을 검토 예정.
- `/check` 라이브 검증은 v1에 **포함**한다(§5). 구현 플랜에서 부담이 크면 마지막 태스크로 분리하거나 후속 PR로 미룰 수 있는 독립 항목.
