import {
  createSdkMcpServer,
  tool,
  type McpSdkServerConfigWithInstance,
  type SdkMcpToolDefinition,
} from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';
import type { PostMockupResult } from '../api/javaClient.js';
import type { WorkerMockupRequest } from '../types.js';
import { textResult } from './dbMcp.js';

/**
 * 인터뷰 내장 목업 MCP (스펙 2026-11-03 인터뷰 목업 §4). 에이전트가 만든 화면 HTML을 API에 올려 관리자 화면 목업 목록에 쌓는다.
 * dbMcp.ts와 같은 모양(SDK 인프로세스 서버, 예외 대신 isError 결과)이다.
 */

export const MOCKUP_SERVER = 'mockup';
export const MOCKUP_TOOL = 'show_mockup';
/** UTF-8 바이트 상한 — API `DesignResultHarvester`와 같은 값(zod .max()는 글자 수라 쓰지 않는다). */
export const MOCKUP_MAX_BYTES = 200 * 1024;
export const MOCKUP_NAME_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;

const NOT_ACCEPTED = '이 서버는 아직 목업을 받지 않습니다';

const DESCRIPTION =
  '인터뷰 사용자에게 화면 목업을 보여 줍니다. html은 외부 리소스(CDN 스크립트·웹 글꼴·외부 이미지) 없는 완전한 HTML 문서(<!doctype html>)이고 200KB 이하입니다. ' +
  'name은 영문 소문자·숫자·하이픈의 짧은 이름이며, 같은 name으로 다시 부르면 그 목업의 새 버전이 됩니다. ' +
  "결과의 '목업 N'으로 질문·계획에서 가리키세요. visual companion 서버나 파일 저장 대신 이 도구를 쓰세요.";

// 값이 외부 주소(http(s): 또는 프로토콜 상대 //)인지
const EXTERNAL_VALUE = /^\s*(?:https?:|\/\/)/i;

/** 태그 이름 → 외부 주소를 검사할 속성 이름(소문자). srcset은 따로 본다. */
const URL_ATTRS: Record<string, string[]> = {
  script: ['src'],
  link: ['href'],
  img: ['src'],
  source: ['src'],
  video: ['src', 'poster'],
  audio: ['src'],
  iframe: ['src'],
  embed: ['src'],
  track: ['src'],
  input: ['src'],
  object: ['data'],
  image: ['href', 'xlink:href'],
  use: ['href', 'xlink:href'],
};
const SRCSET_TAGS = new Set(['img', 'source']);

// 따옴표 안의 '>'를 건너뛰며 태그 하나를 잡는다. 대안들의 첫 글자가 서로 달라 역추적이 선형이다.
const TAG_RE = /<(script|link|img|source|video|audio|iframe|embed|track|input|object|image|use)\b((?:"[^"]*"|'[^']*'|[^>"'])*)>/gi;
const ATTR_RE = /([^\s"'<>/=]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/g;
const CSS_URL_RE = /url\(\s*["']?\s*(?:https?:|\/\/)/gi;
const CSS_IMPORT_RE = /@import\s+(?:url\(\s*)?["']?\s*(?:https?:|\/\/)/gi;

const FRAGMENT_CHARS = 80;

function srcsetHasExternal(value: string): boolean {
  return value.split(',').some((candidate) => EXTERNAL_VALUE.test(candidate));
}

/** 외부 리소스를 가리키는 첫 위반 조각(80자)을 돌려준다. 없으면 null. `<a href>`·`data:`·`#id`·`data-*`는 통과한다. */
export function externalResource(html: string): string | null {
  let first = -1;
  const hit = (index: number): void => {
    if (first < 0 || index < first) first = index;
  };

  for (const m of html.matchAll(TAG_RE)) {
    const tag = m[1]!.toLowerCase();
    const urlAttrs = URL_ATTRS[tag] ?? [];
    for (const a of m[2]!.matchAll(ATTR_RE)) {
      const name = a[1]!.toLowerCase();
      const value = a[2] ?? a[3] ?? a[4] ?? '';
      if (urlAttrs.includes(name) ? EXTERNAL_VALUE.test(value) : SRCSET_TAGS.has(tag) && name === 'srcset' && srcsetHasExternal(value)) {
        hit(m.index!);
        break;
      }
    }
  }
  for (const re of [CSS_URL_RE, CSS_IMPORT_RE]) {
    for (const m of html.matchAll(re)) hit(m.index!);
  }
  return first < 0 ? null : html.slice(first, first + FRAGMENT_CHARS);
}

export interface PreparedMockupMcp {
  servers: Record<string, McpSdkServerConfigWithInstance>;
  /** 도구 정의 — 테스트·진단용(핸들러 직접 호출). */
  tools: SdkMcpToolDefinition[];
}

const failure = (reason: string) => textResult(`목업을 올리지 못했습니다: ${reason}. 글로 설명하고 계속하세요.`, true);

export function createMockupMcp(post: (body: WorkerMockupRequest) => Promise<PostMockupResult>): PreparedMockupMcp {
  // 이 인스턴스(= 한 턴)가 404를 받았으면 구 API다 — 같은 턴에서 다시 올리지 않는다.
  let notAccepted = false;

  const tools = [
    tool(
      MOCKUP_TOOL,
      DESCRIPTION,
      {
        name: z.string().regex(MOCKUP_NAME_RE),
        title: z.string().min(1).max(80),
        description: z.string().max(120).optional(),
        html: z.string().min(1),
      },
      async ({ name, title, description, html }) => {
        const bytes = Buffer.byteLength(html, 'utf8');
        if (bytes > MOCKUP_MAX_BYTES) return failure(`목업이 200KB를 넘습니다: ${Math.ceil(bytes / 1024)}KB`);
        const external = externalResource(html);
        if (external !== null) {
          return failure(`외부 리소스는 미리보기에서 막힙니다(${external}) — 스타일·스크립트·글꼴·그림을 문서 안에 넣으세요`);
        }
        if (notAccepted) return failure(NOT_ACCEPTED);
        let result: PostMockupResult;
        try {
          result = await post({ name, title, ...(description !== undefined ? { description } : {}), html });
        } catch {
          result = { ok: false, status: null, message: '네트워크 오류' };
        }
        if (result.ok) {
          const n = result.mockup.n;
          return textResult(`목업 ${n} 게시됨 — 질문에서 '목업 ${n}'으로 가리키세요`);
        }
        if (result.status === 404) {
          notAccepted = true;
          return failure(NOT_ACCEPTED);
        }
        return failure(result.message);
      },
      { alwaysLoad: true },
    ),
  ] as unknown as SdkMcpToolDefinition[]; // 도구 입력 스키마 타입 때문에 공통 배열 타입으로 맞춘다(dbMcp.ts와 같다)

  return {
    servers: { [MOCKUP_SERVER]: createSdkMcpServer({ name: MOCKUP_SERVER, version: '1.0.0', tools }) },
    tools,
  };
}
