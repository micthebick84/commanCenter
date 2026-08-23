import { HttpStatusError, type JavaApiClient } from '../api/javaClient.js';
import type { ActivityEvent, ActivityInput } from '../types.js';

export interface ActivityPosterOpts {
  /** flush 주기 (기본 300ms — 스펙 §5.3). */
  flushIntervalMs?: number;
  /** 이 개수 도달 시 interval 전 즉시 flush (기본 20 — Java @Size(max=100)보다 충분히 작게). */
  maxQueue?: number;
}

// Java ActivityEvent @Size 상한과 동기 — 위반 시 400으로 배치 전체가 폐기되므로 생산 측에서 절단한다.
// 병합 상한(4000)은 4096보다 여유를 둔다: SDK 자식프로세스 stdout이 델타 다수를 한 chunk로 실어오면
// for-await 드레인이 setInterval을 기아시켜 연속 델타가 한 항목으로 병합될 수 있다(검증 리뷰 MAJOR).
const MAX_CONTENT = 4096;
const MAX_MERGED = 4000;
const MAX_LABEL = 100;

/**
 * 활동 이벤트 배치 전송기 (스펙 §5.3).
 * - push()는 큐 적재만 — 어떤 경로로도 throw하지 않는다 (활동 스트림은 인터뷰를 절대 죽이지 않는다).
 * - 배치 내 동일 type 연속 text/thinking 델타는 content 병합(페이로드 수 최소화) — 단 병합 4000자,
 *   개별 content 4096자·label 100자 상한(Java @Size 동기, 초과 시 배치 전체 400 폐기 방지).
 * - in-flight POST 1개 직렬 체이닝(순서 보장). 실패 배치는 폐기 + 경고 1회.
 * - 404(구 Java, activity 엔드포인트 부재)면 남은 세션 동안 전송 비활성화.
 */
export class ActivityPoster {
  private queue: ActivityEvent[] = [];
  private seq = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private inFlight: Promise<void> = Promise.resolve();
  private disabled = false;
  private warned = false;

  constructor(
    private readonly client: Pick<JavaApiClient, 'postActivity'>,
    private readonly sessionId: number,
    private readonly opts: ActivityPosterOpts = {},
  ) {}

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => this.flush(), this.opts.flushIntervalMs ?? 300);
  }

  push(e: ActivityInput): void {
    if (this.disabled) return;
    const content = typeof e.content === 'string' ? e.content.slice(0, MAX_CONTENT) : e.content;
    const label = typeof e.label === 'string' ? e.label.slice(0, MAX_LABEL) : e.label;
    const last = this.queue[this.queue.length - 1];
    if (
      last &&
      last.type === e.type &&
      (e.type === 'text' || e.type === 'thinking') &&
      typeof last.content === 'string' &&
      typeof content === 'string' &&
      last.content.length + content.length <= MAX_MERGED
    ) {
      last.content += content;
      return;
    }
    this.queue.push({ ...e, label, content, seq: ++this.seq });
    if (this.queue.length >= (this.opts.maxQueue ?? 20)) this.flush();
  }

  private flush(): void {
    if (this.disabled || this.queue.length === 0) return;
    const events = this.queue;
    this.queue = [];
    // 직렬 체이닝: 이전 POST 완료 후 다음 배치 — 순서 보장, 동시 요청 없음.
    this.inFlight = this.inFlight.then(async () => {
      if (this.disabled) return; // 체인 대기 중 앞 배치가 404로 비활성화됐으면 전송하지 않는다
      try {
        await this.client.postActivity(this.sessionId, { events });
      } catch (err) {
        if (err instanceof HttpStatusError && err.status === 404) this.disabled = true;
        if (!this.warned) {
          this.warned = true;
          // eslint-disable-next-line no-console
          console.warn(
            `[activity] session=${this.sessionId} 전송 실패 — 활동 스트림만 유실, 인터뷰는 계속: ${(err as Error).message}`,
          );
        }
        // 실패 배치는 폐기 — 재시도 없음 (transient 미리보기라 유실 수용)
      }
    });
  }

  /** 타이머 정리 + 잔여 큐 flush 후 in-flight 완료 대기. run()의 finally에서 호출. */
  async stop(): Promise<void> {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.flush();
    await this.inFlight;
  }
}
