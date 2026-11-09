# Task 2 보고 — 가격표·비용 계산

Base f041969, head: 아래 커밋(feat(usage): 모델별 API 가격표와 비용 계산).

## 구현
- `M/dto/UsageTokens`, `M/usage/{UsageProperties, UsageConfig, UsagePriceBook, UsageCostCalculator}`, `R/application.yml`의 `app.usage` 블록(`app.request-board` 뒤, `cors:` 앞), `T/usage/UsagePricingTest`.
- UsageProperties는 생성자에서 count-from 형식(기본 2026-10-01)과 다섯 단가 필수를 검증한다(기동 실패). `countFromDate()`는 다시 파싱만 한다.
- 설계와 다름 ① 그대로: fast-multiplier는 모델마다. 배수 없는 모델의 fast는 cost()가 null.

## 가격 확인 (Ruling [T2])
출처: claude-api 스킬 + https://platform.claude.com/docs/en/about-claude/pricing (2026-11-09 조회). 입력/5m쓰기/1h쓰기/캐시읽기/출력, USD/MTok.
- claude-opus-5-5: 4 / 5 / 8 / 0.20 / 20, fast $8/$40 => 배수 2
- claude-opus-5: 5 / 6.25 / 10 / 0.50 / 25, fast $10/$50 => 배수 2
- claude-sonnet-5-5: 2 / 2.50 / 4 / 0.20 / 10
- claude-sonnet-5: 2 / 2.50 / 4 / 0.20 / 10
- claude-haiku-4-5: 1 / 1.25 / 2 / 0.10 / 5
- 위 다섯 모델은 공식 프롬프트 길이 구간 단가가 없어 long-context를 넣지 않았다(1M 컨텍스트 표준 단가). Sonnet 5.5·Sonnet 5·Haiku 4.5는 fast 단가 없음 => 배수 없음.
- 미등록(결정): claude-haiku-5-5(공식 가격은 있고 프롬프트 100K 초과 구간 단가가 있으나, 계획의 대상 다섯 id 밖이라 넣지 않음. 라이브 확인에서 '가격 미등록'이 나오면 threshold 100000 long-context로 추가), 별칭(`sonnet` 등)·Fable·Mythos·구 모델.
- 주의: Opus 5의 fast 모드는 공식 문서상 research preview.

## 시험
- `.\gradlew.bat test --tests com.hamonsoft.netismaker.usage.UsagePricingTest`: 10/10 PASS (계획의 8개 + 반올림 없음 + 본 yml에 다섯 모델 존재).
- 전체 `.\gradlew.bat test`: BUILD SUCCESSFUL (CI 표시 클래스는 skipped).
- TDD 증거: RED는 별도로 캡처하지 못했다. 구현 파일을 시험보다 먼저 만들어 Step 2(컴파일 실패 확인)를 건너뛰었다(보고용 솔직 기재). GREEN은 위와 같다. 첫 실행은 내 Pattern 문자열 이스케이프 오류로 compileJava 실패 후 `[0-9]{8}`로 고쳐 통과.

## 자체 점검 / 비고
- application.yml은 CRLF(autocrlf)라 삽입 줄도 CRLF로 맞췄다.
- UsageConfig는 프로필 없음, NetisMakerApplication.java는 건드리지 않았다. UsagePriceBook만 @Profile("api").
- 비용은 BigDecimal 정확 계산(÷1,000,000은 항상 종료), 반올림 없음.

## 수정 라운드 1/5 보고

- 바꾼 것: `application.yml`의 `claude-sonnet-5-5.cache-read` 0.10 → 0.20 (claude-sonnet-5와 동일, 공식 가격·ClaudeExecAdapterTest 샘플 costUSD 0.2720과 일치). 이 보고서 14행 가격 줄도 0.20으로 수정. `UsagePricingTest`에 본 yml 바인딩 뒤 sonnet-5-5·sonnet-5 캐시 읽기 단가가 0.20이라는 시험 추가.
- 시험: UsagePricingTest 11개 + ClaudeExecAdapterTest 전부 PASS, BUILD SUCCESSFUL.
- 명령: `.\gradlew.bat test --tests com.hamonsoft.netismaker.usage.UsagePricingTest --tests *ClaudeExecAdapterTest`
