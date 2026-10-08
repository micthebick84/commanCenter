#!/usr/bin/env bash
#
# Traefik 리버스 프록시 기동 — 공개 배포 라우팅(*.micthebick.dev → 컨테이너).
# Docker provider로 netis-deploy 네트워크의 traefik.enable=true 컨테이너를 Host 라벨로 라우팅.
#
# 사용: ./scripts/start-traefik.sh      중지: docker rm -f traefik
set -euo pipefail

NETWORK="${NETWORK:-netis-deploy}"
ENTRYPOINT_PORT="${ENTRYPOINT_PORT:-18080}"
# v3.7: docker provider가 Docker Engine 29.x와 호환 (v3.1은 신형 데몬 API와 비호환 — provider가 컨테이너 디스커버리 실패).
IMAGE="${TRAEFIK_IMAGE:-traefik:v3.7}"
# 앞단 cloudflared가 넣은 X-Forwarded-Proto: https 등을 믿을 출발지. 기본값(미설정)이면 Traefik이 자기 연결 기준
# (http)으로 덮어써 배포 앱이 https 공개 주소에서 http://…로 리다이렉트한다(2026-10-08 작업 9).
# Docker Desktop은 게시 포트로 들어온 요청의 출발지를 네트워크 게이트웨이(172.x)로 보여 주므로 사설 대역을 믿는다 —
# 18080을 cloudflared 호스트만 닿게(방화벽) 두는 전제.
TRUSTED_IPS="${TRAEFIK_TRUSTED_IPS:-127.0.0.1/32,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}"

# 공유 네트워크 보장 (멱등)
docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK"

if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx traefik; then
  echo "● Traefik 이미 실행 중 — skip"
  exit 0
fi
docker rm -f traefik >/dev/null 2>&1 || true

echo "● Traefik 기동 (:$ENTRYPOINT_PORT, net=$NETWORK)"
docker run -d --name traefik \
  --restart unless-stopped \
  --network "$NETWORK" \
  -p "${ENTRYPOINT_PORT}:80" \
  -v /var/run/docker.sock:/var/run/docker.sock:ro \
  "$IMAGE" \
  --providers.docker=true \
  --providers.docker.exposedByDefault=false \
  --entrypoints.web.address=:80 \
  "--entrypoints.web.forwardedHeaders.trustedIPs=${TRUSTED_IPS}"

echo "  ✓ Traefik ready — http://localhost:${ENTRYPOINT_PORT} (미지 Host는 404)"
