#!/usr/bin/env bash
#
# 공개 배포 인프라 일회성 셋업: 네트워크 + Traefik 기동 + (수동) cloudflared/DNS 안내.
# 멱등 — 재실행 안전.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "── 1) 공유 도커 네트워크 netis-deploy ──"
docker network inspect netis-deploy >/dev/null 2>&1 || docker network create netis-deploy
echo "  ✓ netis-deploy"

echo "── 2) Traefik 기동 ──"
./scripts/start-traefik.sh

cat <<'EOF'

── 3) 수동 단계 (라이브 터널·DNS — 운영자 승인 후 실행) ──
  a) ~/.cloudflared/config.yml 의 catch-all(http_status:404) 위, auth/app 아래에 추가:
       - hostname: "*.micthebick.dev"
         service: http://localhost:18080
  b) 검증:    cloudflared tunnel ingress validate
  c) 재기동:  cloudflared 재시작 (LaunchAgent) — auth/app 순간 끊김 감수
  d) 와일드카드 DNS: *.micthebick.dev proxied CNAME →
       d1418766-bfc3-4179-a29b-79b2e1d63319.cfargotunnel.com
     (Cloudflare 대시보드, 또는: cloudflared tunnel route dns netis '*.micthebick.dev')
EOF
