# 사내 인프라 트랙 체크리스트

DESIGN.md §15 미결 결정사항을 운영 작업으로 정리. V1 출시 크리티컬 패스.

## A. netis-auth 신규 클라이언트 등록 (사내 인증 담당)

- [ ] netis-auth의 `oauth2_registered_client` 테이블에 `netis-maker-spa` 추가
  ```sql
  -- 예시 (실 등록은 netis-auth 운영 표준 절차로)
  INSERT INTO oauth2_registered_client (
      id, client_id, client_id_issued_at, client_name,
      client_authentication_methods, authorization_grant_types,
      redirect_uris, scopes, client_settings, token_settings
  ) VALUES (
      gen_random_uuid()::text,
      'netis-maker-spa',
      now(),
      'netisMaker SPA',
      'none',                                        -- public client
      'authorization_code,refresh_token',
      'http://localhost:3001/oauth/callback,https://netis-maker.<사내-도메인>/oauth/callback',
      'openid,profile,email',
      '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":true}',
      '{"@class":"java.util.Collections$UnmodifiableMap","settings.token.access-token-time-to-live":["java.time.Duration",3600.0]}'
  );
  ```
- [ ] redirect_uri는 dev(localhost:3001)와 prod(외부 도메인) 둘 다 등록
- [ ] PKCE required: `require-proof-key` = true
- [ ] custom claims에 `authorities`(ROLE_USER / ROLE_ADMIN), `user_id`, `username`, `email` 포함되는지 확인

## B. 외부 공개 도메인 + TLS (사내 네트워크 담당)

- [ ] DNS A 레코드 추가: `worker.netis-maker.<사내-도메인>` → 사내 백엔드 공인 IP
- [ ] TLS 인증서 발급
  - Let's Encrypt (자동 갱신) 또는 사내 CA
  - certbot 자동 갱신 cron 등록
- [ ] nginx 리버스 프록시 설정 (DESIGN §14 참조). 핵심 차단 규칙:
  - `/worker/*` → 외부 통과 + rate limit 100r/m
  - `/api/*` → 외부 차단(deny all 또는 IP 화이트리스트)
- [ ] (선택) 운영자 macOS 공인 IP가 정적이면 `allow <IP>; deny all;` 추가
- [ ] 인증서 만료 모니터링 (만료 30일 전 알람)

## C. 사내 정보보호 승인

- [ ] 백엔드 일부 경로(`/worker/*`) 외부 공개에 대한 사내 정책 검토 요청
- [ ] 변경 사항 / 보안 레이어 / 회수 절차 제출:
  - HTTPS + X-Worker-API-Key + rate limit + (선택) IP 화이트리스트
  - 공유 시크릿 회전 정책 (분기별)
  - mTLS 도입은 V2 계획
- [ ] 거부 시 회귀 계획: VPN 모델(옵션 A)로 전환

## D. 운영자 환경 (macOS)

- [ ] `claude` CLI 설치 + `claude login` 1회 완료 + 세션 유효성 확인
- [ ] GitHub PAT 발급 (분석 대상 레포 read 권한)
- [ ] `~/netis-maker/` 디렉토리 + `.env` 작성
  ```bash
  WORKER_API_KEY=<32+ chars random>
  GITHUB_PAT=ghp_...
  API_BASE_URL=https://worker.netis-maker.<사내-도메인>
  WORKER_ID=mac-<username>-1
  WORKER_VERSION=0.1.0
  ```
- [ ] worker fat jar 배치: `~/netis-maker/worker.jar`
- [ ] launchd plist 작성 + `launchctl load` (DESIGN §14 plist 샘플 그대로)
- [ ] 5분간 정상 동작 + 로그 확인: `tail -f ~/netis-maker/worker.log`

## E. 사내 백엔드 배포 (사내 인프라 담당)

- [ ] PostgreSQL `com` 스키마에 V1__schema.sql Flyway 마이그레이션 실행
- [ ] netis-backend와 동일 PostgreSQL 인스턴스/DB 사용 (P10: com."user" FK)
- [ ] 백엔드 fat jar 배치 + systemd unit 또는 사내 표준 배포
- [ ] application.yml 환경별 오버라이드: `WORKER_API_KEY`(macOS .env와 일치)
- [ ] netis-auth JWKS endpoint 도달 가능 확인: `curl http://localhost:9000/oauth2/jwks`

## 회수 절차 (Rollback)

문제 발생 시:
1. nginx에서 `/worker/*` 라우팅 차단
2. macOS launchctl unload로 워커 정지
3. 데이터는 영향 없음 (작업대기 상태 유지)
4. 트러블슈팅 후 재가동

## V1 출시 게이트

다음이 모두 ✓ 되면 V1 운영 시작:
- [ ] A 완료 (OAuth 로그인 가능)
- [ ] B 완료 (외부 도메인에서 워커 API 도달)
- [ ] C 승인 받음
- [ ] D 운영자 PC에서 워커 자동 시작 + heartbeat 정상
- [ ] E 백엔드 배포 + Flyway 마이그레이션 성공
- [ ] DESIGN §17 스파이크 #1 (claude -p 한국어 분석) 결과 안정적
- [ ] 통합 테스트 7/7 PASSED (`scripts/run-integration-tests.sh`)
