# 질문 레포 추천 — Jev 한국어 정확도 확인 결과 (Task 1)

- 측정일: 2026-11-20
- API가 돌려준 모델: `jev-1.14.0` (요청도 같은 값으로 고정)
- 입력: 카탈로그 레포 16개, 지어낸 질문 29개 — site 10 · version 7 · none 7 · unlisted 5 (그중 DB 필요 14). 질문·별칭·키워드는 이 문서에 싣지 않는다(키워드 초안은 별도 전달).
- 임계값(스펙 §6.2 시작값, 변경 없음): mentions ≥ 0.7, listed 예 ≥ 0.7, listed 아니오 ≤ 0.3, 1위 확률 ≥ 0.5, needs_db ≥ 0.6
- 지시문·키워드는 수정하지 않았고 질문·기대값도 고치지 않았다. 호출은 변형마다 29건, 아래 측정과 기록하지 않은 사전 실행 1회까지 모두 같은 입력·같은 지시문이다.

## 1. 결과 (기준 실행)

| 항목 | 별칭만 | 키워드 포함 |
|---|---|---|
| 상태 적중 site | 10/10 (100%) | 10/10 (100%) |
| 상태 적중 version | 7/7 (100%) | 7/7 (100%) |
| 상태 적중 none | 7/7 (100%) | 7/7 (100%) |
| 상태 적중 unlisted | 5/5 (100%) | 5/5 (100%) |
| 1위 적중 (site+version) | 17/17 (100%) | 17/17 (100%) |
| needs_db 적중 | 26/29 (89.7%) | 25/29 (86.2%) |
| none 질문의 repo 1위 확률 (최소/중앙/최대) | 0.36 / 0.48 / 0.56 | 0.59 / 0.72 / 0.78 |
| 평균 응답 시간 | 171ms | 175ms |

- 판정(Step 5): 키워드 포함 1위 적중 17/17 = 100% ≥ 80% → 통과.
- 같은 입력을 세 번(사전 실행 1회 포함) 돌린 값의 흔들림: 별칭만 none 중앙 0.46~0.48, 키워드 포함 0.63~0.72, needs_db 적중 86~90%, 나머지 지표는 세 번 모두 같았다. 상태·1위는 매번 100%.
- needs_db 오답은 전부 0.41~0.61 사이의 경계값이다(DB가 필요한 질문을 0.6 아래로, 소스만 보면 되는 질문 1건을 0.6 위로 판정). 임계값 0.6을 0.5 부근으로 내리면 놓침이 줄 것으로 보이나 이번에 시험하지 않았다.

## 2. 임계값을 바꿨을 때(관찰만)

두 변형 합 58건 기준. 1위 확률 하한을 0.4·0.6·0.7로, listed 예를 0.5·0.85로, listed 아니오를 0.15·0.5로 바꿔도 상태 적중은 모두 100%였다. 단 하나, 별칭만 변형에서 listed 아니오를 0.15로 낮추면 1건이 NO_MATCH에서 못 좁힘으로 빠졌다(28/29). 이 입력에서는 판정이 임계값에 민감하지 않다(마진이 크다). 입력이 쉬운 편이라 실제 질문에서의 민감도는 기록(§6.4)이 쌓인 뒤 다시 본다.

## 3. 비슷한 후보 하한 결정

none 질문의 repo 1위 확률 중앙값: 별칭만 0.48, 키워드 포함 0.72 — 둘 다 0.25 이상이므로 규칙대로 **Task 7의 `비슷한 후보` 하한은 `0`**.
주의: 이는 "고르게 퍼지지 않는다"는 뜻이다. 레포 16개인데도 아무것도 가리키지 않은 질문에서 1위 확률이 0.5~0.8로 높다 — 모델이 어느 한 레포로 쏠린다. 그 경우 `비슷한 후보` 칩의 1위는 근거가 약한 추천일 수 있다(확인은 사용자 몫, 규칙대로 하한은 두지 않음).

## 4. 최종 지시문 4개 (그대로)

- `repo` (choice): `Which repository is the user's `question` about? Pick the repository whose site, customer, product or version the question refers to.`
- `mentions_target` (noul): `Does `question` refer by name to a specific site, customer, product, or product version (for example a customer name or a version number)?`
- `target_listed` (noul): `Is the site, customer, product, or version that `question` refers to one of the entries in `repositories`? Answer no if the question names something that is not in `repositories`, or names nothing specific.`
- `needs_db` (noul): `Would answering `question` require looking at an actual database (table structure, columns, stored rows, or query results) rather than reading source code only?`

`repo`의 `criteria` 값은 `별칭 — 키워드`(키워드 없으면 별칭만), 키 순서는 요청마다 섞는다.

## 5. 요청·응답 JSON 모양 (확인됨)

`POST https://api.typesafe.ai/v1/systemone`, 헤더 `Authorization: Bearer <키>`, `Content-Type: application/json`. 예시(가상 값):

```json
{
  "model": "jev-1.14.0",
  "state": { "question": "예시 질문", "repositories": [ { "key": "r1", "name": "예시 레포", "hint": "예시 키워드" } ] },
  "questions": {
    "repo": { "type": "choice", "instructions": "…", "criteria": { "r1": "예시 레포 — 예시 키워드" } },
    "mentions_target": { "type": "noul", "instructions": "…" },
    "target_listed": { "type": "noul", "instructions": "…" },
    "needs_db": { "type": "noul", "instructions": "…" }
  }
}
```

응답 필드(실호출로 확인): 최상위 `model`(문자열)·`answers`·`usage{input_tokens, output_tokens}`.
`answers.repo` = `{ type:"choice", choice:"r<id>", confidence:수, probabilities:{ "r<id>": 수, … } }`(모든 선택지 포함, 합 1).
`answers.mentions_target`·`target_listed`·`needs_db` = `{ type:"noul", noul:0~1 }`(confidence 없음).

계획과의 차이: 없다. `state`를 객체(`question`, `repositories`)로 보내도 그대로 받아들여졌다(HTTP 오류 없음). 문서의 noul 선택 필드 `criteria{true,false}`는 쓰지 않았다. 지연이 평균 약 175ms로 스펙의 전체 상한 3초에 크게 못 미친다.
