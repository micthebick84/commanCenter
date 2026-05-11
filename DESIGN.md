# Design: netisMaker — Claude Code 기반 작업 자동 분석 시스템

생성: /office-hours 검토 결과
프로젝트: netisMaker (Hamonsoft / Netis 플랫폼)
상태: **APPROVED** (rev 6 — /plan-eng-review 결정 5건 반영)
승인일: 2026-05-12
리뷰: plan-eng-review (Architecture 3, Code Quality 1, Test scope 1)
모드: Intrapreneurship

---

## 1. Problem Statement

Netis 플랫폼 개발/유지보수 과정에서 기능 추가/버그 수정 요청을 다수 사용자가
모바일/데스크톱 웹에서 등록하면, **운영자 macOS PC(외부망)에서 도는 워커**가
**외부 공개된 사내 백엔드 워커 엔드포인트**에 HTTPS 폴링해서 작업을 받아오고,
Claude Code(구독)로 해당 GitHub 레포를 분석해 **한국어 마크다운 보고서 +
구조화된 subtask 배열**을 산출. 관리자(ROLE_ADMIN)가 결과 검토/승인.

## 2. Scope (V1)

상태 머신:
```
                            ┌──── (분석 실패) ────┐
                            ▼                      │
[작업대기] ──→ [분석중] ──→ [분석완료]            │
    │              │      (+ approved by ADMIN)    │
    ▼              ▼                                │
 [취소됨]      [분석실패] ──(retry by user/admin)──┘
                  │
                  └─→ [작업대기]

soft delete: 모든 상태에서 deleted_at NOT NULL로 숨김
```

V1 포함:
- 다수 사용자 작업 등록, GitHub 레포 주소 직접 입력
- macOS 본인 PC 단일 워커가 외부망 → 외부 공개 워커 엔드포인트(HTTPS) 폴링
- 한국어 분석 보고서 + subtask JSON
- ROLE_ADMIN이 분석 결과 승인
- 본인 작업 취소(작업대기 한정), soft delete (모든 상태)
- 클라이언트 5초 폴링
- 큐 통계 페이지 (모든 사용자), 워커 헬스 페이지 (ADMIN)

V1 비범위: **이메일/알림 전체**, 개발진행중 이후 상태, 자동 코드 생성/PR, GitHub App,
레포 자동완성, 모바일 푸시, 우선순위 큐, 분석중 작업 취소, 다중 워커.

## 3. Cross-Model Perspective

(2차 의견 미실행)

## 4. 핵심 전제 (Premises) — Rev 4

| # | 전제 | 비고 |
|---|---|---|
| P1 | Claude Code **구독형** + **macOS 본인 PC** 단일 워커, 단일 OAuth 세션 | |
| P2 | 다중 GitHub 레포, 단일 PAT, UI 직접 입력 | |
| P3 | 분석 결과 = 한국어 마크다운 + subtask JSON | |
| P4 | 분석 실패 시 `분석실패` 전이 + 재시도 | |
| P5 | ROLE_ADMIN만 approve 가능 | |
| P6 | netis-auth 신규 client_id `netis-maker-spa` (PKCE) | |
| P7 | 클라이언트 5초 폴링 | |
| P8 | 레포 캐시: `~/netis-maker/repos/{owner}/{repo}/` (macOS 홈디렉토리) | |
| P9 | 사용자별 동시 등록(미완료) 5건 제한 | |
| P10 | 백엔드/DB = 사내 서버. 워커 = macOS 본인 PC | |
| P11 | 워커-백엔드 인증: 환경변수 `WORKER_API_KEY` 공유 시크릿 | |
| P12 | **외부망 → 사내망 접근은 백엔드 워커 API 공개**: `/worker/*` 경로만 외부 노출, `/api/*`는 사내 LAN 한정. nginx 리버스 프록시 + HTTPS + X-Worker-API-Key + rate limit | rev5 |
| P13 | 작업 취소: 작업대기 상태, 본인 작업 한정 | |
| P14 | 작업 삭제: soft delete, 본인 또는 ADMIN | |
| P15 | **알림 없음 (V1)**. 사용자/관리자는 UI 폴링으로 상태 확인 | rev4 신규 |
| P16 | macOS 워커 자동 시작: **launchd LaunchAgent** | rev4 신규 |
| P17 | **백엔드/워커 단일 fat jar + Spring Profile 분기** (`--spring.profiles.active=api` vs `worker`) | rev6 (Issue 1A) |
| P18 | **Stale 작업 자동 회수**: `@Scheduled(fixedRate=60000)` 잡이 status='분석중' AND updated_at < (now-5min) AND heartbeat stale 작업을 '작업대기' 복귀 + retry_count++. `max_retry=3` 도달 시 '분석실패'로 직접 전이 | rev6 (Issue 2A) |
| P19 | **분석 프롬프트는 `application-worker.yml`/.env 외부화**. macOS 운영자가 변경 → 워커 재시작으로 적용. 출력은 §11 5섹션 엄격 파싱, 실패 시 '분석실패' + failure_reason에 사유 기록 | rev6 (Issue 3A) |
| P20 | **`com."user"` 엔티티 매핑 안 함**. requester_id는 Long 스칼라. 사용자 이름/이메일/role은 JWT 클레임에서 추출 | rev6 (Issue 4A) |
| P21 | **테스트 커버리지 보일 더 레이크**: 30개 GAP 전부 V1. Spring Boot Test + JUnit 5 + AssertJ + Testcontainers, 프론트는 Vitest + @vue/test-utils. E2E는 OAuth 흐름 1건만 | rev6 (Issue 5A) |

## 5. Approaches Considered

(Approach A 채택. §8 추상화로 B 전환 통로 유지.)

## 6. 시스템 아키텍처 — Rev 5

```
사내 네트워크 (LAN)
═══════════════════════════════════════════════════════════════════
 ┌─────────────────────────┐
 │  Web (Nuxt 3 / Quasar)  │  사내망 접근, netis-auth OAuth, 5초 폴링
 │  - 작업 등록/취소/삭제  │  - 승인 (ROLE_ADMIN)
 │  - 큐 통계 / 워커 헬스  │
 └──────────┬──────────────┘
            │ HTTPS, Bearer JWT (사내 LAN)
            ▼
 ┌─────────────────────────┐         ┌──────────────────────────┐
 │ nginx 리버스 프록시     │         │  PostgreSQL              │
 │ - /api/*  → 사내 LAN만  │         │  - com.task              │
 │ - /worker/* → 외부 공개 │         │  - com.task_analysis     │
 │ - TLS 종단              │         │  - com.task_status_history│
 │ - rate limit (worker)   │         │  - com.worker_heartbeat  │
 │ - (선택) IP 화이트리스트│         └──────────┬───────────────┘
 └──────────┬──────────────┘                    │ JPA
            │                                    │
            ▼                                    ▼
 ┌──────────────────────────────────────────────────┐
 │ netisMaker API (8090, 사내)                       │
 │ - 사용자 API: JWT 검증 (JWKS) + ROLE_ADMIN 인가   │
 │ - 워커 API: SecurityFilter (X-Worker-API-Key 검증)│
 │ - 큐 상태 / 워커 헬스                              │
 └────────────────────────────────────────────────────┘
            ▲
            │ HTTPS (외부 공개 worker.netis-maker.<도메인>)
            │  + X-Worker-API-Key
            │  + (선택) IP 화이트리스트
 ═══════════│════════════════════════════════════════════════════
 외부망 (운영자 macOS)
            │
 ┌──────────┴──────────────────────────────┐
 │ Worker Daemon (Java fat jar)            │
 │ launchd 자동 시작 (KeepAlive=true)      │
 │                                          │
 │ 작업 처리 루프:                          │
 │  1) GET  https://worker.../next-task    │
 │  2) git clone or fetch (캐시)           │
 │  3) `claude -p <prompt>` exec           │
 │  4) 결과 파싱 (§11)                     │
 │  5) POST https://worker.../tasks/{id}/result │
 └──────────┬──────────────────────────────┘
            │ exec
            ▼
 ┌─────────────────────────┐
 │ claude CLI (OAuth 구독) │  사전: claude login
 └─────────────────────────┘

 macOS 파일시스템:
   ~/netis-maker/repos/{owner}/{repo}/        (영구 캐시)
   ~/netis-maker/.env                          (WORKER_API_KEY, GITHUB_PAT, API_BASE_URL)
   ~/netis-maker/worker.jar
   ~/netis-maker/worker.log, worker.err.log
   ~/Library/LaunchAgents/com.hamonsoft.netis-maker.worker.plist
```

**인증 흐름:**
```
사용자: Browser → netis-auth (Authz Code + PKCE) → JWT (ROLE_USER/ROLE_ADMIN)
       Browser → API (Bearer JWT) → JWKS 검증

워커: macOS Worker [VPN] → API (X-Worker-API-Key: <secret>)
     → 별도 SecurityFilter로 검증 (JWT 우회)
```

**권한 매트릭스:**

| 액션 | USER (본인) | USER (남) | ADMIN |
|---|---|---|---|
| 작업 등록 | ✓ | ✓ | ✓ |
| 작업 조회 (본인) | ✓ | ✗ | ✓ (전체) |
| 작업 취소 (작업대기) | ✓ | ✗ | ✓ |
| 작업 삭제 (soft) | ✓ | ✗ | ✓ |
| 분석 결과 조회 | ✓ | ✗ | ✓ |
| 분석 승인 | ✗ | ✗ | ✓ |
| 재시도 (분석실패) | ✓ | ✗ | ✓ |
| 큐 통계 | ✓ | ✓ | ✓ |
| 워커 헬스 | ✗ | ✗ | ✓ |

## 7. 데이터 모델 — Rev 4

```sql
CREATE TABLE com.task (
  id              BIGSERIAL PRIMARY KEY,
  github_repo     VARCHAR(255) NOT NULL,
  github_branch   VARCHAR(255) NOT NULL DEFAULT 'main',
  title           VARCHAR(500) NOT NULL,
  description     TEXT NOT NULL,
  status          VARCHAR(30) NOT NULL DEFAULT '작업대기',
                  -- 작업대기 | 분석중 | 분석완료 | 분석실패 | 취소됨
  requester_id    BIGINT NOT NULL REFERENCES com."user"(id),
  retry_count     INT NOT NULL DEFAULT 0,
  max_retry       INT NOT NULL DEFAULT 3,        -- rev6: P18 한도
  worker_id       VARCHAR(50),                   -- rev6: stale 회수 시 식별
  claimed_at      TIMESTAMPTZ,                   -- rev6: stale 판정 기준
  failure_reason  TEXT,
  deleted_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_pending ON com.task(created_at)
  WHERE status = '작업대기' AND deleted_at IS NULL;
CREATE INDEX idx_task_requester ON com.task(requester_id, status)
  WHERE deleted_at IS NULL;

CREATE TABLE com.task_analysis (
  task_id         BIGINT PRIMARY KEY REFERENCES com.task(id) ON DELETE CASCADE,
  markdown_result TEXT NOT NULL,
  subtasks_json   JSONB NOT NULL,
  claude_log      TEXT,
  duration_ms     BIGINT,
  approved        BOOLEAN NOT NULL DEFAULT false,
  approved_by     BIGINT REFERENCES com."user"(id),
  approved_at     TIMESTAMPTZ,
  completed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE com.task_status_history (
  id              BIGSERIAL PRIMARY KEY,
  task_id         BIGINT NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
  from_status     VARCHAR(30),
  to_status       VARCHAR(30) NOT NULL,
  actor_type      VARCHAR(20) NOT NULL,         -- 'user' | 'worker' | 'system'
  actor_id        VARCHAR(100),
  reason          TEXT,
  at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE com.worker_heartbeat (
  worker_id         VARCHAR(50) PRIMARY KEY,
  last_seen_at      TIMESTAMPTZ NOT NULL,
  hostname          VARCHAR(100),
  version           VARCHAR(50),
  claude_session_ok BOOLEAN,
  vpn_status        VARCHAR(20)                 -- 워커가 자체 보고 (선택)
);

CREATE OR REPLACE VIEW com.task_queue_stats AS
SELECT
  COUNT(*) FILTER (WHERE status = '작업대기' AND deleted_at IS NULL)         AS pending,
  COUNT(*) FILTER (WHERE status = '분석중'   AND deleted_at IS NULL)         AS in_progress,
  COUNT(*) FILTER (WHERE status = '분석완료' AND deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM com.task_analysis a
                                    WHERE a.task_id = com.task.id AND a.approved)) AS awaiting_approval,
  COUNT(*) FILTER (WHERE status = '분석실패' AND deleted_at IS NULL)         AS failed,
  AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) * 1000)
    FILTER (WHERE status IN ('분석완료', '분석실패')) AS avg_duration_ms
FROM com.task;
```

(rev3 대비 `task_notification` 테이블 삭제)

## 8. 분석 호출 추상화

`CodeAnalyzer` 인터페이스 + Spring `@Profile` (subscription | api-key).

## 9. 검증 필수 항목 (Pre-V1 스파이크)

1. **`claude -p` 헤드리스 한국어 출력 일관성** (1순위)
2. **분석 1건 평균 소요시간 측정**
3. **세션 만료/갱신 동작**
4. **레포 크기 한계 / 컨텍스트 전략**
5. **macOS 외부망 → 외부 공개 워커 엔드포인트 HTTPS 도달 검증** (2순위, 5분 소요)
6. **nginx 리버스 프록시 경로 분리 검증**: `/worker/*`는 외부 200/401, `/api/*`는
   외부에서 차단(403/connection refused)되는지 확인

(rev3의 SMTP 검증 항목 삭제)

## 10. 외부 의존성 / 신규 결정 — Rev 4

| 항목 | 결정 |
|---|---|
| 백엔드/DB 위치 | 사내 서버 |
| 백엔드 포트 | 8090 |
| 프론트엔드 포트 | 3001 |
| 워커 위치 | 운영자 macOS 본인 PC, 외부망 |
| 외부망 ↔ 사내망 | **백엔드 워커 API 공개** (nginx 리버스 프록시, `/worker/*`만 외부, HTTPS) |
| 외부 공개 호스트명 | `worker.netis-maker.<사내-도메인>` (§15 확정 필요) |
| TLS 인증서 | Let's Encrypt 또는 사내 CA |
| Rate limit (nginx) | per-IP 분당 100건 (워커 폴링 정상치 ~24건/분 대비 여유) |
| IP 화이트리스트 | 운영자 macOS 공인 IP가 정적이면 적용 권장. 동적이면 미적용 |
| 워커 자동 시작 | **launchd LaunchAgent** (macOS) |
| netis-auth 신규 client | `netis-maker-spa` (PKCE, public) |
| 인가 권한 키 | `ROLE_ADMIN` |
| GitHub 인증 | 환경변수 `GITHUB_PAT` |
| 워커 ↔ 백엔드 인증 | 환경변수 `WORKER_API_KEY` 공유 시크릿 |
| Claude CLI | macOS 설치 + `claude login` 사전 완료 |
| 레포 캐시 디렉토리 | `~/netis-maker/repos/{owner}/{repo}/` |
| 폴링 (워커 → 백엔드) | 5초 next-task, 10초 heartbeat |
| 폴링 (브라우저 → 백엔드) | 5초 |
| 사용자당 동시 등록 | 5건 |
| 알림 | **없음 (V1)** — UI 폴링으로 갈음 |
| 워커 다운 감지 | 큐 통계 페이지 + 워커 헬스 페이지에서 시각화 (ADMIN) |

## 11. 분석 프롬프트 템플릿 (한국어) — Rev 6

**저장 위치**: `application-worker.yml` 또는 `~/netis-maker/.env`의 `WORKER_PROMPT_TEMPLATE`. macOS 운영자가 변경 → `launchctl unload && launchctl load`로 적용. 코드 재배포 불필요.

```yaml
# application-worker.yml 예시
netis-maker:
  worker:
    prompt-template: |
      당신은 코드베이스 분석 어시스턴트입니다. 모든 출력은 반드시 한국어로 작성하세요.

      레포: {github_repo}
      브랜치: {github_branch} (현재 커밋: {commit_sha})
      요청자 작업 제목: {title}
      요청 상세:
      {description}

      아래 형식의 마크다운 보고서를 정확히 이 섹션 구조로 출력하세요.
      섹션 제목과 번호는 변경하지 마세요 (파서가 사용합니다).

      ## 1. 요구사항 요약
      ## 2. 영향 범위
      ## 3. 접근 방법
      ## 4. Subtask 분해
      ## 5. 위험 요소 및 미해결 질문
```

**엄격 파싱 규칙** (`PromptResultParser.java`):
1. 5개 섹션 헤딩 `## 1.`, `## 2.`, `## 3.`, `## 4.`, `## 5.` 모두 정확히 존재해야 함
2. `## 4. Subtask 분해`의 각 항목은 `- **제목**: ...` 형식 (불릿 정규식 매치)
3. 1~3 중 하나라도 누락 → ParseException("section_missing") → status='분석실패', failure_reason='파싱 실패: <섹션 번호> 누락'
4. 4번 섹션이 있지만 subtask 0개 → 경고 로그 + subtasks_json=[] (실패는 아님, 분석완료)
5. 출력이 한국어가 아닌 비율이 50%+ → 경고만 (실패는 아님 — 한국어 강제는 best-effort)

## 12. API 표면 (V1)

**사용자/관리자 API** (JWT 필수):
```
POST   /api/tasks                       작업 등록
                                        body: { github_repo, github_branch?, title, description }
                                        429 if 본인 미완료 작업 ≥ 5
GET    /api/tasks                       목록 (status?, mine?, paging)
GET    /api/tasks/{id}                  단건 + analysis (본인 또는 ADMIN)
POST   /api/tasks/{id}/cancel           취소 (본인, 작업대기만)
DELETE /api/tasks/{id}                  soft delete (본인 또는 ADMIN)
POST   /api/tasks/{id}/approve          ADMIN만
POST   /api/tasks/{id}/retry            분석실패 → 작업대기 (본인 또는 ADMIN)
GET    /api/queue/stats                 큐 통계 (모든 로그인 사용자)
GET    /api/workers/health              워커 상태 (ADMIN)
GET    /api/me                          로그인 사용자 + role
```

**워커 API** (X-Worker-API-Key 필수):
```
POST   /worker/heartbeat                {worker_id, hostname, version, claude_session_ok, vpn_status?}
GET    /worker/next-task                대기 작업 1건 atomic claim (FOR UPDATE SKIP LOCKED)
                                        없으면 204
POST   /worker/tasks/{id}/result        body: { status: '분석완료'|'분석실패',
                                                markdown_result?, subtasks_json?,
                                                claude_log?, duration_ms?, failure_reason? }
```

(rev3 대비: 워커 결과 업로드 후 알림 트리거 없음)

## 13. 성공 기준 — Rev 4 (우선순위 정리)

**V1 필수 (Must):**
- [ ] 사용자가 임의 GitHub 주소 입력해서 작업 등록 가능
- [ ] PAT 접근 불가 레포 → 명시적 에러
- [ ] 평균 분석 시간 ≤ 7분 (§9 #2로 확정)
- [ ] ROLE_ADMIN만 approve 동작
- [ ] netis-auth OAuth 로그인/로그아웃 정상
- [ ] 워커가 macOS에서 launchd로 자동 시작/재시작
- [ ] 워커가 외부 공개 워커 엔드포인트(HTTPS)에 도달, 네트워크 단절 시 자연 재시도
- [ ] nginx에서 `/api/*` 외부 차단, `/worker/*`만 외부 통과
- [ ] 워커 API 키 회전(rotation) 절차 문서화
- [ ] 큐 통계 페이지 동작
- [ ] 워커 헬스 페이지 (ADMIN) — 마지막 heartbeat 시각, 세션 상태

**V1 권장 (Should):**
- [ ] 본인 작업 취소(작업대기 한정)
- [ ] soft delete (본인/ADMIN)
- [ ] 분석실패 재시도

**V1 측정 지표:**
- 분석 성공률, 평균/최대 분석 시간
- 일일 등록/처리/적체 건수
- 워커 가동률 (heartbeat 기반)

## 14. Distribution Plan — Rev 4

**백엔드/프론트엔드:** Gradle fat jar, 기존 Netis 사내 인프라 배포.

**워커 (macOS):**

1. `~/netis-maker/` 디렉토리 준비, `worker.jar` 배치, `.env` 작성
2. `claude login` 1회 수행
3. launchd plist 작성: `~/Library/LaunchAgents/com.hamonsoft.netis-maker.worker.plist`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.hamonsoft.netis-maker.worker</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>-lc</string>
        <string>cd "$HOME/netis-maker" && set -a && . ./.env && set +a && exec java -jar worker.jar</string>
    </array>
    <key>WorkingDirectory</key>
    <string>/Users/USERNAME/netis-maker</string>
    <key>StandardOutPath</key>
    <string>/Users/USERNAME/netis-maker/worker.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/USERNAME/netis-maker/worker.err.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    </dict>
    <key>KeepAlive</key>
    <true/>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
```

4. 로딩: `launchctl load ~/Library/LaunchAgents/com.hamonsoft.netis-maker.worker.plist`
5. 확인: `launchctl list | grep netis-maker`, `tail -f ~/netis-maker/worker.log`

`.env` 예시:
```bash
WORKER_API_KEY=...
GITHUB_PAT=ghp_...
API_BASE_URL=https://netis-maker-api.<사내-호스트>:8090
WORKER_ID=mac-khlee-1
WORKER_VERSION=v1.0.0
```

**시스템 요구사항:** macOS 12+, Java 21, `claude` CLI (Homebrew). VPN 불필요.

**사내 인프라 추가 작업:**
- nginx 설정 (예시):
  ```nginx
  # 외부 공개 server block — /worker/* 만 통과
  server {
      listen 443 ssl http2;
      server_name worker.netis-maker.<사내-도메인>;
      ssl_certificate     /etc/ssl/.../fullchain.pem;
      ssl_certificate_key /etc/ssl/.../privkey.pem;

      limit_req_zone $binary_remote_addr zone=worker:10m rate=100r/m;
      limit_req zone=worker burst=20 nodelay;

      # (선택) IP 화이트리스트
      # allow <운영자 공인 IP>; deny all;

      location /worker/ {
          proxy_pass http://127.0.0.1:8090;
          proxy_set_header Host $host;
          proxy_set_header X-Real-IP $remote_addr;
      }

      location / { return 404; }
  }

  # 사내 LAN 전용 server block — /api/*
  server {
      listen 8090;
      server_name netis-maker-api.<사내-호스트>;
      allow <사내 LAN CIDR>;
      deny all;
      location / { proxy_pass http://127.0.0.1:8090; }
  }
  ```
- DNS: `worker.netis-maker.<사내-도메인>` A 레코드 외부 공개
- TLS 인증서 발급 + 자동 갱신 (Let's Encrypt or 사내 CA)
- 사내 정보보호팀 외부 노출 승인

## 15. Open Questions — Rev 5

1. **외부 공개 호스트명**: `worker.netis-maker.<무엇>`의 도메인 확정? DNS A 레코드
   추가 가능한가? 외부 인증서 발급 가능한가?
2. **사내 정보보호 승인**: 백엔드 일부 경로 외부 공개에 대한 사내 거버넌스 통과 필요.
3. **운영자 macOS 공인 IP**: 정적인가 동적인가? 정적이면 nginx IP 화이트리스트로 1차
   방어 추가 권장. 동적이면 API 키 + rate limit만으로 갈음.
4. **운영자 = 누구**: 휴가/병가 시 워커 정지 SOP? 인계 가능한 사람?
5. **API 키 회전 정책**: 분기별 회전? 운영자 PC 교체 시 회전? 절차 문서화.
6. **레포 캐시 디스크 정리 정책**: 30일 미사용 레포 자동 삭제 등.

## 16. Reviewer Concerns — Rev 5

- **단일 장애점 = 운영자 macOS**: PC 꺼짐/`claude` 세션 만료/인터넷 단절 모두 분석 중단.
  V1엔 알림이 없으므로 사용자는 큐 통계 페이지로 적체를 자가 확인. SLA를 "업무시간 +
  운영자 가동" 명시.
- **외부 노출 보안 표면**: `/worker/*`가 외부 공개되므로 공격 표면이 생김. 방어:
  HTTPS + X-Worker-API-Key + rate limit + (선택)IP 화이트리스트. API 키 유출이 가장 큰
  위험 — 회전 정책 필수.
- **사내 거버넌스 리스크**: 백엔드 외부 노출은 사내 정보보호 정책 검토가 보통 필요.
  검토 미통과 시 옵션 A(VPN)로 회귀 필요할 수 있음.
- **알림 부재의 UX**: 분석이 길어지면 사용자가 새로고침 반복. V2 이메일 우선순위 ↑.
- **단일 워커 처리량 상한**: 분석 5분 가정 시 시간당 12건. 큐 적체 가시화 §13.
- **mTLS 부재**: V1은 공유 시크릿만. mTLS는 V2.
- **약관 회색지대**: 사내 인원 한정 유지.
- **분석 결과 신뢰성**: ADMIN approve 게이트로 차단.

## 17. The Assignment (다음 한 가지 액션)

**스파이크 2건을 순차로 수행하세요. 둘 다 30분 안에 끝납니다.**

**스파이크 #1 (가장 중요):** `claude -p` 한국어 분석 1건 (rev2 §17과 동일)
```bash
cd /tmp && git clone <netis-backend 주소> netis-test && cd netis-test
# /tmp/analyze.txt에 §11 프롬프트 + 실제 작업 1건
time claude -p "$(cat /tmp/analyze.txt)" > /tmp/result.md 2>&1
cat /tmp/result.md
```
확인: 한국어인가, 5개 섹션 구조 일관성, §4 파싱 가능성, 평균 소요시간.

**스파이크 #2 (외부 공개 엔드포인트 도달 확인):**
```bash
# 운영자 macOS, VPN 없이
curl -i -H "X-Worker-API-Key: test-key" \
     -H "Content-Type: application/json" \
     https://worker.netis-maker.<도메인>/worker/heartbeat \
     -d '{"worker_id":"test","hostname":"my-mac"}'

# 추가 확인: /api/* 는 외부 차단되는지
curl -i https://worker.netis-maker.<도메인>/api/me
# 기대: 403 or 404 (nginx 차단)
```
- 200/401 응답 → 외부 공개 경로 OK
- 사내 LAN에서 `/api/*` 200, 외부에서 `/api/*` 차단되면 경로 격리 검증 완료

두 스파이크가 ok면 →
- §15 외부 도메인/인증서/사내 정보보호 승인 진행
- `/plan-eng-review`로 구현 계획 락인 (Spring Security 워커 필터, nginx 설정,
  Spring Boot 모듈 구조, JPA 엔티티, netis-auth client 등록)

## 18. What I noticed about how you think

- "이메일 알람은 나중으로" — 좋은 절제. V1 스코프를 크게 줄였고, UI 폴링만으로도
  사용자가 결과를 확인할 수 있는 한 충분합니다. 다만 분석이 10분 넘어가면 사용자가
  답답해할 수 있으니, 사용 데이터를 보면서 V1.1 이메일 도입 시점을 정하면 됩니다.
- "외부망 + B(공개)" — 운영자 부담을 최소화하는 결정. 다만 VPN을 포기한 대가로 사내
  거버넌스/보안 검토 부담이 생깁니다. nginx 설정·인증서·IP 정책 같은 사내 인프라
  작업이 추가됩니다. 만약 정보보호팀이 외부 노출을 거부하면 A(VPN)로 회귀해야
  하므로, 정보보호 승인을 V1 빌드와 병렬로 진행하는 게 좋습니다.
- "macOS" — 자동 시작/배포 결정이 한 번에 락인됐습니다. 다음 단계의 의사결정 비용이
  크게 줄었습니다. 작은 디테일 같지만 이런 결정 누적이 V1 출시 시점을 결정합니다.

---

## 19. 테스트 계획 — Rev 6 (보일 더 레이크)

### 프레임워크
- **백엔드**: Spring Boot Test (JUnit 5) + AssertJ + Mockito + **Testcontainers**(PostgreSQL 16)
- **프론트엔드**: **Vitest** + @vue/test-utils + @nuxt/test-utils
- **E2E**: Playwright (OAuth 흐름 1건만)

### 커버리지 목표
- 백엔드 라인 커버리지 ≥ 90%
- 모든 권한 매트릭스 (8가지 조합) 통합 테스트로 검증
- §11 프롬프트 파서는 단위 테스트 ★★★ (load-bearing — 깨지면 전체 기능 마비)

### 테스트 트리 (30개 GAP 전부 V1)

```
src/test/java/com/hamonsoft/netismaker/
├── api/
│   ├── TaskCreateApiTest.java          [통합]
│   │   ├── 동시 등록 5건 초과 → 429
│   │   ├── github_repo 정규식 실패 → 400
│   │   └── happy → 201, status='작업대기'
│   ├── TaskQueryApiTest.java           [통합]
│   │   ├── 본인 조회 200
│   │   ├── 남 + ADMIN 200
│   │   └── 남 + USER 403
│   ├── TaskCancelApiTest.java          [통합]
│   ├── TaskDeleteApiTest.java          [통합]
│   ├── TaskApproveApiTest.java         [통합] ★★★ (ADMIN 게이트)
│   ├── TaskRetryApiTest.java           [통합]
│   │   └── retry_count >= max_retry → 409
│   └── PermissionMatrixTest.java       [통합] ★★★ (8가지 조합)
├── worker/
│   ├── WorkerHeartbeatApiTest.java     [통합] (API key 401)
│   ├── WorkerNextTaskApiTest.java      [통합]
│   │   ├── 204 if no pending
│   │   └── atomic claim (FOR UPDATE SKIP LOCKED) ★★★
│   └── WorkerResultApiTest.java        [통합]
├── scheduled/
│   └── StaleTaskRecoveryJobTest.java   [단위] ★★★
│       ├── heartbeat 정상 → no-op
│       ├── 5분 초과 → 작업대기 복귀
│       └── retry >= max → 분석실패 직접
├── service/
│   ├── PromptResultParserTest.java     [단위] ★★★ (load-bearing)
│   │   ├── 정상 5섹션 → subtasks 추출
│   │   ├── 섹션 누락 → ParseException
│   │   ├── 한국어 헤딩 자의적 변환 견고성
│   │   └── 4번 섹션 있고 subtask 0개 → 경고+성공
│   └── GitRepoCacheTest.java           [단위] (clone vs fetch)
├── workerdaemon/
│   ├── WorkerMainLoopTest.java         [통합] (mock backend)
│   ├── ClaudeExecAdapterTest.java      [통합] (exec 실패 처리)
│   └── PromptInjectionTest.java        [단위] (description sanitize)
└── e2e/
    └── OAuthFlowE2ETest.java           [E2E] ★★★
        └── 로그인→등록→처리→승인 full cycle

frontend/test/
├── stores/
│   └── auth.test.ts                    [단위] (OAuth+PKCE)
├── composables/
│   └── useTaskPolling.test.ts          [단위] (5초 폴링, 중복 방지)
└── components/
    └── TaskActions.test.ts             [컴포넌트] (권한별 버튼 활성)
```

### 회귀 테스트 정책
새 PR/feature가 §11 프롬프트 형식, 권한 매트릭스, stale 회수 로직을 건드리면 **위 ★★★ 테스트는 반드시 추가/수정 후 commit**.

### CI 게이트
- 모든 PR: `./gradlew test` + `npm test` 통과 필수
- `develop` 머지 전: 커버리지 90% 미만이면 머지 차단
