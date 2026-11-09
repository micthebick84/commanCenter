import { describe, expect, it, vi } from 'vitest';
import { createMockupMcp, externalResource, MOCKUP_MAX_BYTES } from '../src/sdk/mockupMcp.js';
import type { PostMockupResult } from '../src/api/javaClient.js';
import type { WorkerMockupRequest } from '../src/types.js';

type Tools = ReturnType<typeof createMockupMcp>['tools'];
const call = async (tools: Tools, name: string, args: Record<string, unknown>) => {
  const t = tools.find((x) => x.name === name);
  if (!t) throw new Error(`도구 없음: ${name}`);
  return (await t.handler(args as never, {})) as { content: Array<{ text: string }>; isError?: boolean };
};
const text = (r: { content: Array<{ text: string }> }) => r.content[0]!.text;

const okResult = (n = 3): PostMockupResult => ({ ok: true, mockup: { id: 9, n, version: 1 } });
const setup = (result: PostMockupResult = okResult()) => {
  const post = vi.fn<(b: WorkerMockupRequest) => Promise<PostMockupResult>>(async () => result);
  const { tools, servers } = createMockupMcp(post);
  return { post, tools, servers };
};
const ok = { name: 'a-plan', title: 'A안', html: '<!doctype html><p>hi</p>' };

describe('createMockupMcp (스펙 2026-11-03 인터뷰 목업 §4)', () => {
  it('성공 결과 문구', async () => {
    const { tools, post } = setup(okResult(3));
    expect(text(await call(tools, 'show_mockup', ok))).toBe("목업 3 게시됨 — 질문에서 '목업 3'으로 가리키세요");
    expect(post).toHaveBeenCalledWith({ name: 'a-plan', title: 'A안', html: ok.html });
  });

  it('200KB는 바이트로 재고 넘으면 올리지 않는다', async () => {
    const { tools, post } = setup();
    const r = await call(tools, 'show_mockup', { ...ok, html: '가'.repeat(68_267) }); // 204 801바이트
    expect(r.isError).toBe(true);
    expect(text(r)).toContain('목업이 200KB를 넘습니다: 201KB');
    expect(post).not.toHaveBeenCalled();
    const r2 = await call(tools, 'show_mockup', { ...ok, html: 'a'.repeat(MOCKUP_MAX_BYTES) });
    expect(r2.isError).toBeUndefined();
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('외부 리소스는 올리지 않고 사유를 돌려준다', async () => {
    const bad = [
      '<script src="https://cdn.tailwindcss.com"></script>',
      "<LINK href='//fonts.googleapis.com/css2'>",
      '<img src=https://x/y.png>',
      '<style>@font-face{src:url( "https://x/f.woff2")}</style>',
      '<style>@import "https://x.css";</style>',
      '<img srcset="a.png 1x, https://x/b.png 2x">',
      '<video poster="https://x/p.png"></video>',
      '<svg><image href="//x/i.png"/></svg>',
      '<object data="https://x/o.svg"></object>',
    ];
    for (const html of bad) {
      const { tools, post } = setup();
      const r = await call(tools, 'show_mockup', { ...ok, html });
      expect(r.isError, html).toBe(true);
      expect(text(r), html).toContain('외부 리소스는 미리보기에서 막힙니다(');
      expect(text(r), html).toMatch(/글로 설명하고 계속하세요\.$/);
      expect(post, html).not.toHaveBeenCalled();
    }
    const good = [
      '<a href="https://example.com">x</a>',
      '<img src="data:image/png;base64,AA">',
      '<svg><use href="#icon"/></svg>',
      '<div data-src="https://x"></div>',
    ];
    for (const html of good) {
      const { tools, post } = setup();
      const r = await call(tools, 'show_mockup', { ...ok, html });
      expect(r.isError, html).toBeUndefined();
      expect(post, html).toHaveBeenCalledTimes(1);
    }
  });

  it('externalResource는 첫 위반 조각을 80자까지 돌려준다', () => {
    expect(externalResource('<p>ok</p>')).toBeNull();
    const frag = externalResource(`<p>x</p><script src="https://a/${'b'.repeat(200)}"></script>`);
    expect(frag).toHaveLength(80);
    expect(frag!.startsWith('<script src="https://a/')).toBe(true);
  });

  it('404 뒤에는 같은 인스턴스에서 다시 올리지 않는다', async () => {
    const { tools, post } = setup({ ok: false, status: 404, message: 'HTTP 404' });
    const a = await call(tools, 'show_mockup', ok);
    const b = await call(tools, 'show_mockup', ok);
    expect(post).toHaveBeenCalledTimes(1);
    for (const r of [a, b]) {
      expect(r.isError).toBe(true);
      expect(text(r)).toContain('이 서버는 아직 목업을 받지 않습니다');
    }
  });

  it('400 사유는 도구 결과로', async () => {
    const { tools } = setup({ ok: false, status: 400, message: '목업 이름은 영문 소문자·숫자·하이픈입니다' });
    const r = await call(tools, 'show_mockup', ok);
    expect(r.isError).toBe(true);
    expect(text(r)).toContain('목업을 올리지 못했습니다: 목업 이름은');
    expect(text(r).endsWith('글로 설명하고 계속하세요.')).toBe(true);
  });

  it('서버 이름·도구·alwaysLoad', () => {
    const { tools, servers } = setup();
    expect(Object.keys(servers)).toEqual(['mockup']);
    expect(tools).toHaveLength(1);
    expect(tools[0]!.name).toBe('show_mockup');
    expect(tools[0]!._meta?.['anthropic/alwaysLoad']).toBe(true);
  });

  it('도구 설명에 핵심 규칙', () => {
    const d = setup().tools[0]!.description;
    for (const s of ['<!doctype html>', '외부 리소스', '200KB', '같은 name', '파일 저장 대신']) expect(d).toContain(s);
  });
});
