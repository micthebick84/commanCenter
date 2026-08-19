# netisMaker TODOS (post-V1)

## V1.1 — 운영 안정성

### [ ] 레포 캐시 자동 정리 정책

**What**: macOS 운영자 PC의 `~/netis-maker/repos/{owner}/{repo}/`에 누적되는 레포 캐시를 주기적으로 정리하는 자동화.

**Why**: Netis 레포 1개당 수GB. V1 운영 중 다양한 레포 분석 누적되면 운영자 macOS 디스크가 차서 워커 정지 시나리오 발생 가능.

**Pros**:
- 운영 리스크 사전 차단
- 운영자 수동 개입 불필요
- 디스크 모니터링 알림 없이도 안전

**Cons**:
- 정책 결정 필요 (얼마 미사용이면 삭제? 디스크 사용량 임계점?)
- 다시 사용되는 레포는 재clone 시간 발생

**Context**:
- V1 §15 #6에 이미 명시된 deferred 항목
- 후보 정책: ① 30일 미사용 레포 자동 삭제, ② 디스크 사용량 80% 초과 시 LRU 정리, ③ 운영자 수동 트리거
- 워커 데몬 내부 `@Scheduled(cron="0 0 3 * * *")` 새벽 3시 정리 잡 + admin UI 트리거 버튼
- 정리 후 task_analysis에 사용된 레포 캐시 메타데이터 기록 → 통계로 추적

**Depends on / blocked by**:
- V1 운영 데이터 1~3개월 누적 (어떤 레포가 자주 쓰이는지 패턴 보고 정책 결정)
- 운영자 macOS 디스크 가용량 베이스라인 측정

**우선순위**: 중간. V1 디스크 압박이 실제로 발생하면 즉시 작업 착수.

### [ ] 첨부파일 정리 정책

**What**: 작업 등록 시 첨부된 파일이 저장되는 `~/netis-maker/attachments/task-{id}/`를 정리하는 자동화/정책. 현재는 생성 후 자동 삭제가 전혀 없다(인터뷰 `work_dir`와 동일 정책).

**Why**: task가 soft-delete되거나 완료 후 오래 방치돼도 디렉터리는 그대로 남는다. 등록이 반복될수록 운영자 macOS 디스크에 무기한 누적되어, 위 "레포 캐시 자동 정리 정책"과 동일한 디스크 압박 실패 시나리오로 이어질 수 있다.

**Pros**:
- 운영 리스크 사전 차단 (레포 캐시와 같은 이유)
- task soft-delete 시 같이 지우면 사용자 관점에서도 "삭제됐다"는 기대와 일치

**Cons**:
- soft-delete된 task를 나중에 복구/재조회해야 하는 경우가 생기면 첨부도 같이 날아가 복구 불가
- 보존 기한 정책 결정 필요 (며칠/몇 개월? 상태별로 다르게?)

**Context**:
- 제한값은 `application.yml`의 `app.attachment.*`: per-file 20MB, task당 최대 10개, task당 합계 50MB
- 즉 worst case는 task 1건당 50MB가 무기한 누적 — 레포 캐시 항목(레포 1개당 수GB)보다 단위는 작지만 등록 빈도가 훨씬 높아 누적 속도가 다를 수 있음
- 후보안: ① task soft-delete 시 디렉터리 즉시 삭제, ② 보존 기한(예: 90일) 경과 시 배치 삭제, ③ 위 레포 캐시 정리 잡과 통합해 같은 새벽 스케줄러에서 처리

**Depends on / blocked by**:
- soft-delete 이후 첨부 복구가 실제로 필요한 운영 시나리오가 있는지 확인
- 레포 캐시 정리 정책(위 항목)과 동일 스케줄러로 묶을지 별도로 갈지 결정

**우선순위**: 낮음. 파일당/건당 상한이 이미 걸려 있어 레포 캐시보다 급하지 않음. 등록 건수가 누적되며 디스크 압박 조짐이 보이면 착수.

### [ ] 프론트 테스트가 생성물 `.nuxt`에 의존

**What**: `frontend/tsconfig.json`이 Nuxt 빌드 시점 생성물인 `./.nuxt/tsconfig.json`을 extends한다. 이 파일은 `nuxi prepare`(보통 `postinstall`이 실행)가 만들어야 존재한다.

**Why**: 신규 clone, 새 git worktree, 또는 `node_modules`만 캐시하고 `.nuxt`는 캐시하지 않는 CI 잡에서는 `.nuxt/tsconfig.json`이 없다. 이 상태에서 vitest를 돌리면 테스트를 한 건도 수집하기 전에 transform 단계에서 `Cannot find module './.nuxt/tsconfig.json'`로 죽는다. 에러 메시지가 실제 원인(Nuxt prepare 미실행)을 가리키지 않아 원인 파악에 시간이 든다.

**Pros**:
- CI에서 원인불명 실패로 시간 낭비하는 일을 사전 차단
- 신규 기여자/새 워크트리에서 온보딩 마찰 감소 (지금은 `.nuxt` 심볼릭 링크라는 비공식 우회를 각자 알아내야 함)

**Cons**:
- `.nuxt` 캐싱을 선택하면 CI 캐시 용량/무효화 관리가 하나 더 늘어남
- extends를 그만두고 tsconfig를 직접 명시하면 Nuxt가 관리하는 경로 별칭(alias) 등을 수동으로 동기화해야 할 수 있음

**Context**:
- 이번 `spec/task-attachments` 브랜치 작업 중 리뷰어 2명이 독립적으로 이 문제를 겪었고, 각자 `.nuxt`를 심볼릭 링크로 우회해 진행함
- 복구법: `nuxi prepare` 명시 실행. `postinstall`이 보통 이를 실행하지만, `node_modules`만 복원되고 `npm install`을 다시 안 돌리면 postinstall도 안 돈다
- 후보 해결책: ① CI에서 테스트 전에 `nuxi prepare`를 명시적으로 실행, ② `node_modules`와 함께 `.nuxt`도 캐싱, ③ 생성물을 extends하지 않도록 tsconfig를 직접 명시

**Depends on / blocked by**:
- CI 파이프라인 구성 확정 (어느 방식으로 고정할지는 CI 셋업 작업과 함께 결정)

**우선순위**: 중간. 로컬에서는 매번 걸리는 건 아니라 급하지 않지만, CI를 새로 구축하는 시점에는 반드시 셋업 단계에 포함해야 함 (안 그러면 첫 CI 실행부터 전원 매몰).
