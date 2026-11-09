import type { InterviewClaimResponse } from '../types.js';

/**
 * 작업 타입별 인터뷰 kickoff 문구 (스펙 2026-11-03 §5.1). 순수 함수만 둔다 — kickoff 프롬프트의 세 자리를 채운다:
 *  - `kickoffLead`  : 맨 앞 첫 문장(`I want to …\n`)
 *  - `taskTypeGuide`: 첨부 절 다음·`IMPORTANT —` 앞의 지시 문단(없으면 '')
 *  - `writableDbLine`: `taskTypeGuide` 바로 뒤의 개발 DB 쓰기 안내 한 줄(타입과 무관, 없으면 '')
 *
 * 타입 판정은 와이어 값과의 리터럴 비교다. 타입 없음(undefined·null)·`NEW_FEATURE`·모르는 값(다른 버전 API의 새 타입)은 모두 지금의
 * 문구(첫 문장 그대로, 지시 문단 없음)라 타입이 생기기 전과 프롬프트가 바이트까지 같다.
 *
 * 이 파일은 "무슨 일인지"(타입 절)만 정한다. 진행 방식 문구 — PLANNING-ONLY 문단의 `(via brainstorming → writing-plans)`와
 * 마지막 `Use the brainstorming skill…` 줄 — 은 `interviewMode.ts`(간단 인터뷰 = superpowers 끔의 진행 방식 절)가 정한다.
 * 다만 `taskTypeGuide`의 "…부터 질문하세요." 문장은 질문 순서(진행 방식)이기도 해서 `superpowers` 인자가 false면 그 문장만
 * "먼저 자료·코드·DB로 확인하고, 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요."로 바뀐다 — 계획 내용 요구는 그대로다.
 */

type KickoffClaim = Pick<InterviewClaimResponse, 'taskType' | 'githubRepo' | 'githubBranch'>;

/** kickoff 첫 문장 — 줄바꿈까지 포함한다. 타입 없음·NEW_FEATURE·모르는 값은 지금 문장. */
export function kickoffLead(claim: KickoffClaim): string {
  const where = `at ${claim.githubRepo} (branch ${claim.githubBranch}).\n`;
  if (claim.taskType === 'FEATURE_CHANGE') return `I want to change an existing feature in the repo ${where}`;
  if (claim.taskType === 'BUG_FIX') return `I want to fix a bug in the repo ${where}`;
  if (claim.taskType === 'DB_WORK') return `I want to change database tables or data used by the repo ${where}`;
  return `I want to add a feature to the repo ${where}`;
}

/**
 * 타입 지시 문단의 조각. 켬 문단 = `head + askFirst + rest`, 끔 문단 = `head + askOnce + rest`다 — 첫 "…부터 질문하세요." 문장
 * (`askFirst`)만 끔에서 `askOnce`로 바뀌고 나머지(`head`의 작업 타입·질문 대상, `rest`의 계획 내용 요구)는 같다.
 */
interface TypeGuide {
  /** "작업 타입: …"부터 질문 대상까지. */
  head: string;
  /** 켬: 사람에게 먼저 묻는 문장(끝 마침표까지). */
  askFirst: string;
  /** 끔: 먼저 자료·코드·DB로 확인하고 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣는 문장. */
  askOnce: string;
  /** 첫 문장 뒤의 계획 내용 요구 — 앞 공백을 포함하고, 없으면 ''. */
  rest: string;
}

const FEATURE_CHANGE_GUIDE: TypeGuide = {
  head: '작업 타입: 기능 수정. 먼저 코드를 읽어 지금 어떻게 동작하는지 확인하고, 무엇을 어떻게 바꿀지와 바꾸면 안 되는 것(유지할 동작)',
  askFirst: '부터 질문하세요.',
  askOnce: '도 자료·코드·DB로 먼저 확인하세요. 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요.',
  rest: '',
};

const BUG_FIX_GUIDE: TypeGuide = {
  head: '작업 타입: 버그 픽스. 어느 화면에서, 무엇을 했을 때, 어떤 오류가 뜨는지',
  askFirst: '부터 질문하세요.',
  askOnce: '는 먼저 자료·코드·DB로 확인하고, 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요.',
  rest:
    ' 첨부(캡처·로그)가 있으면 먼저 보고, 코드 읽기와 DB 조회 도구로 원인을 좁혀 보세요. ' +
    '계획의 작업은 재현 → 원인 → 수정 → 같은 오류가 다시 안 나는지 확인(가능하면 회귀 테스트) 순서로 쓰세요.',
};

const DB_WORK_GUIDE: TypeGuide = {
  head: '작업 타입: DB 작업. 어떤 테이블·데이터를 어떻게 바꿀지',
  askFirst: '부터 질문하세요.',
  askOnce: '는 먼저 자료·코드·DB로 확인하고, 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요.',
  rest:
    ' 계획에는 실행할 SQL을 순서대로 적고, 문장마다 [바로 실행] 또는 [승인 대기]를 표시하세요. ' +
    '[바로 실행]: 새 테이블·인덱스·시퀀스·뷰, NULL 허용이거나 DEFAULT 있는 컬럼 추가, INSERT, WHERE 있는 UPDATE/DELETE. ' +
    '[승인 대기]: 남의 테이블 컬럼 삭제·이름·타입·NULL 여부 변경, NOT NULL이면서 DEFAULT 없는 컬럼 추가, 제약·유니크 인덱스·트리거·프로시저. ' +
    '기존 테이블 DROP·TRUNCATE와 조건 없는 UPDATE/DELETE는 거부됩니다.',
};

/**
 * 타입별 인터뷰 지시 문단 — 끝에 빈 줄(`\n\n`)을 포함한다. 타입 없음·NEW_FEATURE·모르는 값이면 ''.
 * 인자가 `unknown`인 것은 와이어 값이 다른 버전 API의 새 타입이거나 숫자·객체일 수도 있어서다.
 *
 * `superpowers`(기본 true)가 false면 간단 인터뷰(스펙 2026-11-03 §5-2)다 — 첫 "…부터 질문하세요." 문장만 "먼저 자료·코드·DB로
 * 확인하고, 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요."로 바뀌고 나머지(계획 내용 요구)는 같다. 둘째 인자를 생략하면 켬이라
 * 이 인자가 생기기 전과 바이트까지 같다.
 */
export function taskTypeGuide(taskType: unknown, superpowers = true): string {
  const guide =
    taskType === 'FEATURE_CHANGE' ? FEATURE_CHANGE_GUIDE
    : taskType === 'BUG_FIX' ? BUG_FIX_GUIDE
    : taskType === 'DB_WORK' ? DB_WORK_GUIDE
    : null;
  if (guide === null) return '';
  return guide.head + (superpowers ? guide.askFirst : guide.askOnce) + guide.rest + '\n\n';
}

/**
 * 구현 단계에서 개발 DB 쓰기가 열리는 연결 안내 문단 (스펙 2026-11-03 §5.1) — kickoff의 `taskTypeGuide` 바로 뒤에 붙는다.
 * 끝에 빈 줄(`\n\n`)을 포함하고, 연결 이름이 하나도 없으면 ''라 프롬프트가 지금과 바이트까지 같다.
 * 인자가 `unknown`인 것은 구버전 API에는 칸이 없고(undefined) 다른 버전의 값이 배열이 아닐 수도 있어서다 — 배열이 아니면 안내하지 않는다.
 * 이름은 API가 claim DB 절과 같은 label(`이름 (DB 종류)`)로 보낸다. 문자열이 아닌 원소와 빈 이름은 건너뛴다.
 * 인터뷰 단계의 DB는 읽기 전용이라는 말을 같이 적어, 인터뷰 중에 변경을 시도하지 않게 한다.
 */
export function writableDbLine(names: unknown): string {
  if (!Array.isArray(names)) return '';
  const labels = names.filter((n): n is string => typeof n === 'string').map((n) => n.trim()).filter((n) => n.length > 0);
  if (labels.length === 0) return '';
  return (
    `구현 단계에서는 다음 DB 연결의 공용 개발 DB에 변경이 직접 적용됩니다: ${labels.join(', ')}. ` +
    '남의 테이블 컬럼 삭제·변경 등은 승인 대기로 남습니다. ' +
    'DB 변경이 필요하면 계획에 DB 변경 단계를 넣으세요. 이 인터뷰에서는 읽기 전용입니다.\n\n'
  );
}
