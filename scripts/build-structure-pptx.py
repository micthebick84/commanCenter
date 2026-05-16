"""netisMaker V1 구조 요약 파워포인트 생성기."""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.oxml.ns import qn
from copy import deepcopy

# 색상 팔레트 (네이비 + 오렌지 액센트)
NAVY    = RGBColor(0x1F, 0x3A, 0x68)
ACCENT  = RGBColor(0xF2, 0x99, 0x4A)
LIGHT   = RGBColor(0xF4, 0xF6, 0xFA)
TEXT    = RGBColor(0x2D, 0x3A, 0x4B)
MUTED   = RGBColor(0x6B, 0x7A, 0x90)
WHITE   = RGBColor(0xFF, 0xFF, 0xFF)
GREEN   = RGBColor(0x27, 0xAE, 0x60)
RED     = RGBColor(0xEB, 0x5A, 0x46)

PRS_W, PRS_H = Inches(13.333), Inches(7.5)   # 16:9
prs = Presentation()
prs.slide_width  = PRS_W
prs.slide_height = PRS_H

BLANK = prs.slide_layouts[6]  # 완전 빈 레이아웃

# ---------- 유틸 ----------

def set_ea_font(run, name):
    """East-Asian(한국어) 글꼴을 별도 지정해 PowerPoint가 영문/한글 모두 자연스럽게 렌더."""
    rPr = run._r.get_or_add_rPr()
    ea = rPr.find(qn('a:ea'))
    if ea is None:
        from lxml import etree
        ea = etree.SubElement(rPr, qn('a:ea'))
    ea.set('typeface', name)

def add_textbox(slide, x, y, w, h, text, *, size=18, bold=False, color=TEXT,
                align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, font='Calibri',
                ea_font='Apple SD Gothic Neo'):
    tb = slide.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = Emu(0)
    tf.margin_top = tf.margin_bottom = Emu(0)
    tf.vertical_anchor = anchor
    lines = text.split('\n')
    for i, line in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        run = p.add_run()
        run.text = line
        run.font.name = font
        run.font.size = Pt(size)
        run.font.bold = bold
        run.font.color.rgb = color
        set_ea_font(run, ea_font)
    return tb

def add_rect(slide, x, y, w, h, *, fill=NAVY, line=None, shape=MSO_SHAPE.ROUNDED_RECTANGLE):
    s = slide.shapes.add_shape(shape, x, y, w, h)
    s.fill.solid()
    s.fill.fore_color.rgb = fill
    s.line.color.rgb = line if line else fill
    s.shadow.inherit = False
    return s

def add_arrow(slide, x1, y1, x2, y2, color=NAVY, weight=2.5):
    line = slide.shapes.add_connector(1, x1, y1, x2, y2)  # STRAIGHT
    line.line.color.rgb = color
    line.line.width = Pt(weight)
    # 화살표 머리
    from pptx.oxml.ns import qn
    ln = line.line._get_or_add_ln()
    from lxml import etree
    headEnd = etree.SubElement(ln, qn('a:tailEnd'))
    headEnd.set('type', 'triangle')
    return line

def add_card(slide, x, y, w, h, title, body, *, accent=NAVY):
    """제목 + 본문 카드. 상단 색띠 + 회색 외곽."""
    bg = add_rect(slide, x, y, w, h, fill=WHITE, line=MUTED, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    bg.line.width = Pt(0.75)
    bg.line.color.rgb = MUTED
    bg.fill.solid()
    bg.fill.fore_color.rgb = WHITE
    # 상단 색띠
    band = add_rect(slide, x, y, w, Inches(0.32), fill=accent, shape=MSO_SHAPE.RECTANGLE)
    add_textbox(slide, x + Inches(0.18), y + Inches(0.05), w - Inches(0.36), Inches(0.32),
                title, size=12, bold=True, color=WHITE, anchor=MSO_ANCHOR.MIDDLE)
    add_textbox(slide, x + Inches(0.18), y + Inches(0.46), w - Inches(0.36), h - Inches(0.6),
                body, size=11, color=TEXT)

def slide_header(slide, idx, title, subtitle=''):
    """모든 슬라이드 공통 헤더."""
    add_rect(slide, Emu(0), Emu(0), PRS_W, Inches(0.8), fill=NAVY, shape=MSO_SHAPE.RECTANGLE)
    add_textbox(slide, Inches(0.5), Inches(0.12), Inches(9), Inches(0.4),
                title, size=22, bold=True, color=WHITE)
    if subtitle:
        add_textbox(slide, Inches(0.5), Inches(0.5), Inches(9), Inches(0.3),
                    subtitle, size=11, color=LIGHT)
    add_textbox(slide, Inches(12.0), Inches(0.25), Inches(1.0), Inches(0.4),
                f'{idx:02d} / 12', size=10, color=LIGHT, align=PP_ALIGN.RIGHT)

# =====================================================================
# Slide 1 — 표지
# =====================================================================
s = prs.slides.add_slide(BLANK)
add_rect(s, Emu(0), Emu(0), PRS_W, PRS_H, fill=NAVY, shape=MSO_SHAPE.RECTANGLE)
# 액센트 바
add_rect(s, Inches(0.7), Inches(2.2), Inches(0.18), Inches(1.6),
         fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
add_textbox(s, Inches(1.1), Inches(2.0), Inches(11), Inches(1.0),
            'netisMaker V1', size=52, bold=True, color=WHITE)
add_textbox(s, Inches(1.1), Inches(2.9), Inches(11), Inches(0.6),
            '한국어 모바일 코드 분석 큐 — 프로젝트 구조 정리',
            size=22, color=LIGHT)
add_textbox(s, Inches(1.1), Inches(3.7), Inches(11), Inches(0.5),
            'Spring Boot 3.4 · Nuxt 3 · PostgreSQL · netis-auth OAuth2 · Claude Code',
            size=13, color=LIGHT)
add_textbox(s, Inches(1.1), Inches(6.7), Inches(11), Inches(0.4),
            '2026-05  ·  Hamonsoft', size=11, color=LIGHT)

# =====================================================================
# Slide 2 — 한 줄 요약
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 2, '한 줄 요약', '무엇을, 누구를 위해, 왜')
add_textbox(s, Inches(0.7), Inches(1.2), Inches(12), Inches(0.5),
            '"사용자가 GitHub 레포 + 작업 요청을 등록하면,',
            size=24, bold=True, color=NAVY)
add_textbox(s, Inches(0.7), Inches(1.8), Inches(12), Inches(0.5),
            ' 운영자 macOS의 Claude Code 구독이 한국어 분석 보고서를 만들어',
            size=24, bold=True, color=NAVY)
add_textbox(s, Inches(0.7), Inches(2.4), Inches(12), Inches(0.5),
            ' 관리자 승인 후 사용자에게 돌려준다."',
            size=24, bold=True, color=NAVY)

cards = [
    ('대상 사용자', '사내 개발자 / 관리자\n사용자는 작업만 등록, 관리자(ROLE_ADMIN)는 결과 승인'),
    ('핵심 제약 (P6)', 'Claude Code 구독 1개 = macOS 1대\n분석은 운영자 단일 호스트에서 직렬 수행'),
    ('전송 채널', '사내 백엔드 ←외부망(HTTPS)→ 운영자 macOS 워커\nDB 직접 접근 없이 워커 API만 사용'),
]
for i, (t, b) in enumerate(cards):
    x = Inches(0.7 + i * 4.15)
    add_card(s, x, Inches(4.0), Inches(3.9), Inches(2.5), t, b, accent=ACCENT)

# =====================================================================
# Slide 3 — 시스템 구성
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 3, '시스템 구성', '3개 컴포넌트 + 외부 서비스 1개')

# 프론트엔드 (왼쪽)
add_card(s, Inches(0.5), Inches(1.4), Inches(3.6), Inches(2.2),
         '프론트엔드 (Nuxt 3 SPA)',
         '· :3001 (dev) — 모바일 우선 반응형\n· Quasar UI + Pinia 스토어\n· OAuth2 Auth Code + PKCE\n· 5초 폴링으로 task 상태 갱신\n· 5 페이지: login, callback,\n  tasks/[id], admin/workers',
         accent=ACCENT)

# 백엔드 (가운데)
add_card(s, Inches(4.85), Inches(1.4), Inches(3.6), Inches(2.2),
         '백엔드 API (api 프로파일)',
         '· :8090 사내 서버\n· Spring Boot 3.4 + JPA + Flyway\n· JWT 검증 (issuer=netis-auth:9000)\n· /api/tasks, /api/queue, /api/me,\n  /api/workers, /worker/* (API-Key)\n· PostgreSQL com 스키마',
         accent=NAVY)

# 워커 (오른쪽)
add_card(s, Inches(9.2), Inches(1.4), Inches(3.6), Inches(2.2),
         '워커 데몬 (worker 프로파일)',
         '· 운영자 macOS\n· web-application-type=none\n· 5초 폴링 + 10초 heartbeat\n· git clone/fetch → claude -p exec\n· 5섹션 마크다운 + subtasks JSON\n· DB 직접 접근 없음 (HTTP만)',
         accent=GREEN)

# 외부 — netis-auth
add_card(s, Inches(0.5), Inches(4.0), Inches(3.6), Inches(1.5),
         'netis-auth (외부)',
         '· :9000 Spring Authorization Server\n· client: netis-maker-spa (PKCE)\n· 1h access · 7d refresh (rotation)\n· claim: username, authorities',
         accent=MUTED)

# PostgreSQL
add_card(s, Inches(4.85), Inches(4.0), Inches(3.6), Inches(1.5),
         'PostgreSQL (외부)',
         '· :5432 — 5개 스키마 공유 환경\n· com 스키마에 netisMaker 객체\n· Flyway baseline-version=0\n· task, task_analysis, history,\n  worker_heartbeat',
         accent=MUTED)

# Claude Code
add_card(s, Inches(9.2), Inches(4.0), Inches(3.6), Inches(1.5),
         'Claude Code 구독 (외부)',
         '· 운영자 macOS 로그인 1계정\n· claude -p "<prompt>" 형태로 호출\n· 10분 timeout, 한국어 출력 강제\n· OAuth (API key 아님)',
         accent=MUTED)

# 화살표
add_textbox(s, Inches(0.5), Inches(5.8), Inches(12), Inches(1.5),
            '데이터 흐름:\n'
            '  ① 사용자 → 프론트엔드(JWT) → 백엔드 /api/tasks  (task 등록)\n'
            '  ② 워커 → 백엔드 /worker/next-task               (큐 폴링)\n'
            '  ③ 워커 → GitHub clone + Claude exec             (외부망)\n'
            '  ④ 워커 → 백엔드 /worker/tasks/{id}/result        (결과 업로드)\n'
            '  ⑤ 관리자 → 프론트엔드 → 백엔드 /api/tasks/{id}/approve',
            size=11, color=TEXT)

# =====================================================================
# Slide 4 — 인증 흐름
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 4, '인증 흐름', 'OAuth2 Authorization Code + PKCE (public SPA client)')

steps = [
    ('1', '사용자 → 프론트엔드 /login',                       '브라우저에서 로그인 버튼'),
    ('2', '프론트엔드 → netis-auth /oauth2/authorize',       'state + PKCE code_challenge 동봉, redirect'),
    ('3', '사용자 로그인 + 동의 → 콜백',                       '/oauth/callback?code=...&state=...'),
    ('4', '프론트엔드 → /oauth2/token (Nuxt proxy)',          'code + code_verifier → access/refresh token'),
    ('5', '프론트엔드 → 백엔드 /api/me',                        'Bearer 토큰으로 user_id, authorities 확인'),
    ('6', '이후 모든 /api 호출에 Authorization 헤더',           '백엔드는 netis-auth JWKS로 RS256 검증'),
]
for i, (n, h, d) in enumerate(steps):
    y = Inches(1.3 + i * 0.85)
    # 단계 번호 원
    add_rect(s, Inches(0.6), y, Inches(0.65), Inches(0.65), fill=ACCENT, shape=MSO_SHAPE.OVAL)
    add_textbox(s, Inches(0.6), y, Inches(0.65), Inches(0.65),
                n, size=18, bold=True, color=WHITE, align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    add_textbox(s, Inches(1.5), y, Inches(7.5), Inches(0.35), h,
                size=14, bold=True, color=NAVY)
    add_textbox(s, Inches(1.5), y + Inches(0.35), Inches(11), Inches(0.35), d,
                size=11, color=MUTED)

# 우측 코드 박스 — Nuxt proxy 핵심
add_card(s, Inches(9.5), Inches(1.3), Inches(3.5), Inches(5.5),
         'Nuxt devProxy 핵심',
         "/api → :8090/api\n   (백엔드)\n\n/oauth2 → :9000/oauth2\n   (netis-auth)\n\n· nitro가 prefix를 strip 후\n  target에 append하므로\n  target에 path 포함 필수\n\n· /oauth2 프록시는 Origin/\n  Referer를 same-origin으로\n  rewrite (netis-auth의\n  CorsFilter 우회)",
         accent=NAVY)

# =====================================================================
# Slide 5 — 작업 라이프사이클
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 5, '작업 라이프사이클', '9개 상태 머신 — DESIGN §4')

states = [
    ('작업대기',    NAVY,   '사용자가 등록\n워커가 polling 대기'),
    ('분석중',      ACCENT, '워커 claim 직후\nclaude 분석 진행'),
    ('분석완료',    GREEN,  'subtasks 파싱 성공\n관리자 승인 대기'),
    ('승인됨',      GREEN,  '관리자가 승인\n실행 가능 상태'),
    ('승인거절',    RED,    '관리자 거절\n사용자 재등록 가능'),
    ('분석실패',    RED,    'clone/exec/parse 실패\nretry_count++ 후 자동복귀'),
]
for i, (label, color, desc) in enumerate(states):
    col = i % 3
    row = i // 3
    x = Inches(0.7 + col * 4.15)
    y = Inches(1.3 + row * 2.6)
    add_rect(s, x, y, Inches(3.9), Inches(0.7), fill=color, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    add_textbox(s, x, y, Inches(3.9), Inches(0.7),
                label, size=18, bold=True, color=WHITE,
                align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    add_textbox(s, x + Inches(0.2), y + Inches(0.85), Inches(3.6), Inches(1.5),
                desc, size=11, color=TEXT)

add_textbox(s, Inches(0.7), Inches(6.7), Inches(12), Inches(0.6),
            '※ 모든 전이는 com.task_status_history에 actor_type/actor_id/reason과 함께 감사 기록.\n'
            '※ retry_count >= max_retry(3) 도달 시에만 최종 분석실패. 그 전엔 자동으로 작업대기로 복귀.',
            size=11, color=MUTED)

# =====================================================================
# Slide 6 — PostgreSQL 스키마
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 6, 'PostgreSQL 스키마', 'com 스키마 — Flyway V1__schema.sql')

tables = [
    ('com.task',                 NAVY,
     'id BIGINT PK\nrequester_id VARCHAR(20) → com."user"\ngithub_repo / github_branch\ntitle / description / status\nretry_count / max_retry\nworker_id / claimed_at\ndeleted_at (soft delete)'),
    ('com.task_analysis',        ACCENT,
     'task_id PK FK\nmarkdown_result TEXT\nsubtasks_json JSONB\nclaude_log / duration_ms\napproved / approved_by\napproved_at / completed_at'),
    ('com.task_status_history',  GREEN,
     'id BIGINT PK\ntask_id FK\nfrom_status / to_status\nactor_type (user|worker|admin|system)\nactor_id / reason\nat TIMESTAMPTZ'),
    ('com.worker_heartbeat',     MUTED,
     'worker_id VARCHAR(50) PK\nlast_seen_at TIMESTAMPTZ\nhostname / version\nclaude_session_ok\nvpn_status'),
]
for i, (name, color, body) in enumerate(tables):
    col = i % 2
    row = i // 2
    x = Inches(0.6 + col * 6.3)
    y = Inches(1.4 + row * 2.8)
    add_card(s, x, y, Inches(6.0), Inches(2.6), name, body, accent=color)

# =====================================================================
# Slide 7 — 백엔드 책임
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 7, '백엔드 (api 프로파일)', 'src/main/java/com/hamonsoft/netismaker — Spring Boot 3.4')

groups = [
    ('controller/', NAVY, [
        'TaskController — /api/tasks CRUD + approve/reject',
        'MeController — /api/me 사용자 프로필',
        'QueueStatsController — /api/queue/stats',
        'WorkerHealthController — /api/workers (ADMIN)',
        'WorkerController — /worker/* (API-Key)',
    ]),
    ('service/', ACCENT, [
        'TaskService — task 생성·조회·승인',
        'WorkerService — claim/result/heartbeat 트랜잭션',
        'StaleTaskRecoveryJob — @Scheduled 5min',
        'GlobalExceptionHandler',
    ]),
    ('config/', GREEN, [
        'SecurityConfig — 두 SecurityFilterChain',
        '  /worker/** : WorkerApiKeyFilter (ROLE_WORKER)',
        '  /api/**    : JWT (ROLE_USER/ROLE_ADMIN)',
        'CorsConfig — :3001/:3000 허용',
    ]),
    ('repository/', MUTED, [
        'TaskRepository — JpaRepository + SKIP LOCKED 쿼리',
        'TaskAnalysisRepository / TaskStatusHistoryRepository',
        'WorkerHeartbeatRepository',
        'QueueStatsRepository (EntityManager 직접)',
    ]),
]
for i, (name, color, items) in enumerate(groups):
    col = i % 2
    row = i // 2
    x = Inches(0.5 + col * 6.4)
    y = Inches(1.3 + row * 2.85)
    body = '\n'.join('· ' + line for line in items)
    add_card(s, x, y, Inches(6.1), Inches(2.7), name, body, accent=color)

# =====================================================================
# Slide 8 — 워커 데몬 파이프라인
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 8, '워커 데몬 처리 파이프라인', 'WorkerMainLoop.processTask — 매 5초 폴링')

stages = [
    ('①', '폴링 + Claim',  'GET /worker/next-task\n→ SELECT FOR UPDATE\n   SKIP LOCKED'),
    ('②', '레포 캐시',      'GitRepoCache.ensureFresh\n첫회 clone --depth=1\n이후 fetch + reset --hard'),
    ('③', '프롬프트 치환',  '한국어 분석 템플릿에\n{github_repo}, {branch},\n{commit_sha}, {title} 삽입'),
    ('④', 'Claude exec',    'claude -p "<prompt>"\nin repo dir\n10분 timeout, stdout 캡처'),
    ('⑤', '결과 파싱',      'PromptResultParser\n## 1.~## 5. 5섹션 검증\n## 4. 항목을 JSON 배열로'),
    ('⑥', '업로드',         'POST /worker/tasks/{id}/result\nmarkdown_result + subtasks_json\n+ claude_log + duration_ms'),
]
for i, (n, title, body) in enumerate(stages):
    x = Inches(0.5 + i * 2.13)
    y = Inches(1.5)
    # 번호 원
    add_rect(s, x + Inches(0.85), y, Inches(0.45), Inches(0.45), fill=ACCENT, shape=MSO_SHAPE.OVAL)
    add_textbox(s, x + Inches(0.85), y, Inches(0.45), Inches(0.45),
                n, size=16, bold=True, color=WHITE, align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    # 카드
    add_rect(s, x, y + Inches(0.6), Inches(2.0), Inches(2.4),
             fill=WHITE, line=MUTED, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    add_textbox(s, x + Inches(0.1), y + Inches(0.7), Inches(1.8), Inches(0.4),
                title, size=12, bold=True, color=NAVY, align=PP_ALIGN.CENTER)
    add_textbox(s, x + Inches(0.1), y + Inches(1.15), Inches(1.8), Inches(1.7),
                body, size=9, color=TEXT, align=PP_ALIGN.CENTER)
    if i < len(stages) - 1:
        add_arrow(s, x + Inches(2.0), y + Inches(1.8),
                  x + Inches(2.13), y + Inches(1.8), color=NAVY)

# 별도 heartbeat 트랙
add_rect(s, Inches(0.5), Inches(4.5), Inches(12.3), Inches(0.7),
         fill=LIGHT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
add_textbox(s, Inches(0.7), Inches(4.55), Inches(12), Inches(0.6),
            '병행 트랙 — @Scheduled 10초마다 POST /worker/heartbeat  (worker_id, hostname, version)',
            size=12, bold=True, color=NAVY, anchor=MSO_ANCHOR.MIDDLE)

add_textbox(s, Inches(0.5), Inches(5.5), Inches(12.3), Inches(1.6),
            '실패 경로:\n'
            '· clone 실패 → safePostFailure(FAILED 보고) → 백엔드가 retry_count++ 후 작업대기로 복귀\n'
            '· claude exit≠0 / timeout → 동일\n'
            '· 파싱 실패 → 동일 (단, V1.1에서 permanent로 분류 예정)\n'
            '· max_retry(3) 도달 시 최종 분석실패 유지',
            size=11, color=TEXT)

# =====================================================================
# Slide 9 — 프론트엔드
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 9, '프론트엔드 (Nuxt 3 + Quasar)', 'frontend/ — 모바일 우선 5 페이지')

pages = [
    ('/login',           '로그인 시작 — netis-auth 리다이렉트'),
    ('/oauth/callback',  'code → access_token 교환'),
    ('/tasks',           '내 task 리스트 + 새 task 등록'),
    ('/tasks/[id]',      '상세 + 마크다운/subtasks 표시'),
    ('/admin/workers',   'ADMIN 워커 헬스 페이지'),
]
for i, (path, desc) in enumerate(pages):
    y = Inches(1.4 + i * 0.85)
    add_rect(s, Inches(0.6), y, Inches(0.3), Inches(0.65), fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
    add_textbox(s, Inches(1.1), y, Inches(3.5), Inches(0.4),
                path, size=15, bold=True, color=NAVY, font='Menlo')
    add_textbox(s, Inches(1.1), y + Inches(0.35), Inches(7), Inches(0.4),
                desc, size=11, color=MUTED)

# 우측 스토어 카드
add_card(s, Inches(9.3), Inches(1.3), Inches(3.6), Inches(2.3),
         'Pinia 스토어 — stores/auth.ts',
         '· accessToken / refreshToken\n· expiresAt + me 캐시\n· loginRedirect (PKCE)\n· handleCallback\n· refreshMe / logout\n· localStorage 영속화',
         accent=NAVY)
add_card(s, Inches(9.3), Inches(3.8), Inches(3.6), Inches(2.3),
         'QueueStatsBar',
         '· 모든 페이지 상단 고정\n· 5초마다 /api/queue/stats\n· PENDING / IN_PROGRESS /\n  내 task 진행률 표시',
         accent=ACCENT)

# =====================================================================
# Slide 10 — 실행 + 포트
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 10, '빌드 · 실행 · 포트', '로컬 개발 환경 기준')

cmds = [
    ('PostgreSQL :5432',  ACCENT,
     'brew services start postgresql@17\n# db=postgres user=postgres pw=ntflow'),
    ('netis-auth :9000',  NAVY,
     'cd ../netis-auth\n./gradlew bootRun'),
    ('netisMaker 백엔드 :8090',  GREEN,
     '# 기본 = api 프로파일\n./gradlew bootRun'),
    ('netisMaker 프론트엔드 :3001',  MUTED,
     'cd frontend && npm install\nnpm run dev'),
    ('워커 데몬 (운영자 macOS)',  RED,
     'SPRING_PROFILES_ACTIVE=worker \\\nAPI_BASE_URL=http://localhost:8090 \\\nWORKER_API_KEY=... \\\nCLAUDE_CLI=$(which claude) \\\n./gradlew bootRun \\\n  --args=\'--spring.profiles.active=worker\''),
]
for i, (title, color, code) in enumerate(cmds):
    col = i % 2
    row = i // 2
    x = Inches(0.5 + col * 6.3)
    y = Inches(1.3 + row * 1.85)
    h = Inches(1.7) if i < 4 else Inches(2.5)
    add_rect(s, x, y, Inches(6.0), h, fill=WHITE, line=MUTED, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    add_rect(s, x, y, Inches(6.0), Inches(0.32), fill=color, shape=MSO_SHAPE.RECTANGLE)
    add_textbox(s, x + Inches(0.2), y, Inches(5.6), Inches(0.32),
                title, size=12, bold=True, color=WHITE, anchor=MSO_ANCHOR.MIDDLE)
    add_textbox(s, x + Inches(0.2), y + Inches(0.4), Inches(5.6), h - Inches(0.4),
                code, size=10, color=TEXT, font='Menlo')

# =====================================================================
# Slide 11 — E2E 검증
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 11, 'E2E 검증 결과', '2026-05-13 실 환경 테스트 — task #2')

# 타임라인
events = [
    ('00:34:59', '작업대기 → 분석중',   '워커 첫 claim',                NAVY),
    ('00:35:00', '분석중 → 분석실패',   '빈 PAT URL 버그로 clone 실패', RED),
    ('00:36:06', '작업대기 → 분석중',   '버그 수정 후 재시작 + 재claim', NAVY),
    ('00:39:06', '분석중 → 분석완료',   '178초 소요, markdown 8.5KB',  GREEN),
]
for i, (ts, trans, note, c) in enumerate(events):
    y = Inches(1.3 + i * 0.7)
    add_textbox(s, Inches(0.5), y, Inches(1.4), Inches(0.4),
                ts, size=13, bold=True, color=MUTED, font='Menlo')
    add_rect(s, Inches(2.0), y + Inches(0.05), Inches(0.18), Inches(0.3),
             fill=c, shape=MSO_SHAPE.OVAL)
    add_textbox(s, Inches(2.4), y, Inches(4.5), Inches(0.4),
                trans, size=14, bold=True, color=c)
    add_textbox(s, Inches(7.0), y, Inches(6), Inches(0.4),
                note, size=12, color=TEXT)

# 결과 메트릭
add_card(s, Inches(0.5), Inches(4.4), Inches(4.0), Inches(2.5),
         '산출물',
         'markdown_result\n  8,523 chars (5섹션 완벽)\n\nsubtasks_json\n  8 항목 (L=1, M=5, H=2)\n\nduration_ms\n  178,047',
         accent=GREEN)
add_card(s, Inches(4.7), Inches(4.4), Inches(4.0), Inches(2.5),
         'Claude가 짚어낸 결함',
         '· timeout(10m) > stale(5m) 모순\n· 단일스레드로 heartbeat 동결\n· PAT가 .git/config·ps에 노출\n· FAILED 보고가 retry 우회\n· claude 자식 프로세스 누수',
         accent=ACCENT)
add_card(s, Inches(8.9), Inches(4.4), Inches(4.0), Inches(2.5),
         '세션 중 수정 (커밋 b395ff2)',
         '· @Profile("api") 12개 클래스\n· web-application-type=none\n  (worker 프로파일)\n· GitRepoCache 빈 PAT 폴백\n  → public repo 익명 clone',
         accent=NAVY)

# =====================================================================
# Slide 12 — 현재 상태 + 백로그
# =====================================================================
s = prs.slides.add_slide(BLANK)
slide_header(s, 12, '현재 상태 및 V1.1 백로그', 'origin/main = b395ff2')

# 완료
add_card(s, Inches(0.5), Inches(1.2), Inches(6.1), Inches(2.8),
         '완료 ✓',
         '· DESIGN rev6 APPROVED (29 KB, 21 premises)\n'
         '· 백엔드 api 프로파일 + 워커 daemon + 프론트 MVP\n'
         '· PostgreSQL com 스키마 Flyway 적용\n'
         '· netis-auth에 netis-maker-spa OAuth 클라이언트 등록\n'
         '· admin/password123 로그인 E2E + task #1 등록 검증\n'
         '· 워커 E2E (task #2 분석완료, 8개 subtask 추출)\n'
         '· 16 unit test PASSED (parser 9 + recovery 4 + me 3)\n'
         '· GitHub: micthebick84/commanCenter',
         accent=GREEN)

# 백로그
add_card(s, Inches(6.7), Inches(1.2), Inches(6.1), Inches(2.8),
         'V1.1 백로그 — Claude 분석이 식별한 우선순위',
         '· stale-threshold ↔ analysisTimeout 정합화 (M)\n'
         '· heartbeat 페이로드 + 스케줄러 풀 분리 (M)\n'
         '· ClaudeExecAdapter 견고화 (H)\n'
         '· transient/permanent 실패 분류 + 재시도 (H)\n'
         '· 단일 인스턴스 PID 락 + lease token (M)\n'
         '· GitRepoCache PAT 노출 제거 (M)\n'
         '· PromptResultParser 코드블록 무시 (L)\n'
         '· 결과 보고 디스크 큐 + 멱등 (M)',
         accent=ACCENT)

# 인프라
add_card(s, Inches(0.5), Inches(4.2), Inches(12.3), Inches(2.8),
         '인프라 트랙 (INFRA-CHECKLIST.md)',
         '· Track A — netis-auth 클라이언트 운영 등록 (스코프·redirect URI 사내 도메인)\n'
         '· Track B — 외부망 워커 엔드포인트 도메인 + TLS 인증서 + 방화벽 화이트리스트\n'
         '· Track C — 정보보안 승인 (외부망에서 사내 DB 접근 없음 확인, API-Key 회전 정책)\n'
         '· Track D — macOS LaunchAgent plist (worker auto-start + KeepAlive)\n'
         '· Track E — 모니터링 대시보드 (worker_heartbeat + 큐 길이 + 실패율)',
         accent=NAVY)

# =====================================================================
# 저장
# =====================================================================
out = '/Users/micthebick/IdeaProjects/netisMaker/netisMaker-구조-V1.pptx'
prs.save(out)
print(f'WROTE {out}')
