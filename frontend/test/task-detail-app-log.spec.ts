import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import TaskDetailMobile from '../components/tasks/TaskDetailMobile.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { FakeEventSource, CLOSED } from './mocks/eventsource'
import { baseTask } from './fixtures/task'
import { setViewportWidth } from './mocks/screen'

// 작업 상세의 앱 로그 칸(데스크톱·모바일)과 모바일 빌드 로그 (스펙 2026-11-03 §5). 하네스는 task-detail-fix.spec.ts와 같다.
;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const deployment = { deployUrl: 'http://x:8080', deployHostPort: 8080, deployImage: 'img', deployedAt: null, deployLog: null }
const fixNone = { activeRound: null, activeMode: null, approvedRounds: 0, maxRounds: 3 }
const fixOpen = { activeRound: 2, activeMode: 'DIRECT', approvedRounds: 2, maxRounds: 3 }
const deployed = {
  ...baseTask, status: 'DEPLOYED', statusLabel: '배포완료', requesterId: 'alice', deployment, fix: fixNone,
}
const redeploying = { ...deployed, status: 'DEPLOYING', statusLabel: '배포중' }
const emptyClose = { progress: [] as string[], mergeCommitSha: null, notePath: null }

/** 지금 화면이 연 앱 로그 연결과 빌드 로그 연결 — 둘은 주소로 가른다(앱 로그는 `/api/**` 밖의 `/stream/`). */
const appStreams = () => FakeEventSource.instances.filter((i) => i.url.startsWith('/stream/'))
const buildStreams = () => FakeEventSource.instances.filter((i) => i.url.startsWith('/api/tasks/42/logs/stream'))

describe('작업 상세 앱 로그 (스펙 2026-11-03 §5)', () => {
  let latest: Record<string, unknown>

  beforeEach(() => {
    authStub.isAdmin = false
    latest = deployed
    // 작업 조회만 최신 응답을 돌려주고, 수정 요청 기록 같은 목록 조회는 빈 배열로 답한다.
    useApiMock.mockImplementation((url: string) => Promise.resolve(url === '/api/tasks/42' ? latest : []))
  })
  afterEach(async () => {
    document.body.innerHTML = ''
    await setViewportWidth(1024)
  })

  async function mountPage(task: Record<string, unknown> = deployed, width = 1024) {
    latest = task
    await setViewportWidth(width)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    return w
  }
  /** 5초 폴링 틱 대신 페이지의 refresh를 직접 부른다 — useTaskPolling이 시험에서는 폴링 없는 스텁이다. */
  async function refreshTo(w: ReturnType<typeof mount>, task: Record<string, unknown>) {
    latest = task
    await (w.findComponent(TaskDetail).vm as any).refresh()
    await flushPromises()
  }
  const desktopHeader = (w: ReturnType<typeof mount>) => w.find('[data-test="app-log-desktop"] .q-item')
  const mobileHeader = (w: ReturnType<typeof mount>, key: 'applog' | 'buildlog') =>
    w.find(`[data-test="section-${key}"] [role="button"]`)

  it('데스크톱: 배포완료면 "앱 로그"가 보이고 펼치기 전에는 연결이 없다', async () => {
    const w = await mountPage()
    expect(w.find('[data-test="app-log-desktop"]').exists()).toBe(true)
    expect(w.find('[data-test="app-log-card"]').exists()).toBe(false)
    expect(appStreams()).toHaveLength(0)

    await desktopHeader(w).trigger('click')
    await flushPromises()
    expect(appStreams()).toHaveLength(1)
    expect(appStreams()[0]!.url.startsWith('/stream/tasks/42/app-logs?access_token=')).toBe(true)
    expect(w.find('[data-test="app-log-card"]').exists()).toBe(true)
    w.unmount()
  })

  it('수정 회차 구현중·종료승인대기에서도 보이고, deployment가 없으면 숨는다', async () => {
    // 열린 수정 회차가 있으면 구현중이어도 보인다
    let w = await mountPage({ ...deployed, status: 'IMPLEMENTING', statusLabel: '구현중', fix: fixOpen })
    expect(w.find('[data-test="app-log-desktop"]').exists()).toBe(true)
    w.unmount()
    document.body.innerHTML = ''

    // 종료승인대기
    w = await mountPage({ ...deployed, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', close: emptyClose })
    expect(w.find('[data-test="app-log-desktop"]').exists()).toBe(true)
    w.unmount()
    document.body.innerHTML = ''

    // 열린 회차 없이 구현중이면 숨는다
    w = await mountPage({ ...deployed, status: 'IMPLEMENTING', statusLabel: '구현중', fix: fixNone })
    expect(w.find('[data-test="app-log-desktop"]').exists()).toBe(false)
    w.unmount()
    document.body.innerHTML = ''

    // 배포 기록이 없다: deployment 자체가 없거나, 주소·시각이 모두 비었다
    w = await mountPage({ ...deployed, deployment: null })
    expect(w.find('[data-test="app-log-desktop"]').exists()).toBe(false)
    w.unmount()
    document.body.innerHTML = ''

    w = await mountPage({ ...deployed, deployment: { ...deployment, deployUrl: null, deployedAt: null } })
    expect(w.find('[data-test="app-log-desktop"]').exists()).toBe(false)
    w.unmount()
  })

  it('재배포로 배포중이 되면 앱 로그를 닫고 빌드 로그가 열린다', async () => {
    const w = await mountPage()
    await desktopHeader(w).trigger('click')
    await flushPromises()
    expect(appStreams()).toHaveLength(1)
    expect(appStreams()[0]!.readyState).not.toBe(CLOSED)
    expect(buildStreams()).toHaveLength(0)

    await refreshTo(w, redeploying)
    expect(appStreams()).toHaveLength(1)
    expect(appStreams()[0]!.readyState).toBe(CLOSED)
    expect(buildStreams()).toHaveLength(1)
    expect(w.find('[data-test="app-log-desktop"]').exists()).toBe(false)
    expect(w.find('[data-test="app-log-card"]').exists()).toBe(false)
    w.unmount()
  })

  it('모바일 390px: 앱 로그 칸은 접힌 채, 펼쳐야 연결', async () => {
    const w = await mountPage(deployed, 390)
    expect(w.find('[data-test="section-applog"]').exists()).toBe(true)
    expect(mobileHeader(w, 'applog').attributes('aria-expanded')).toBe('false')
    expect(w.find('[data-test="app-log-card"]').exists()).toBe(false)
    expect(appStreams()).toHaveLength(0)

    await mobileHeader(w, 'applog').trigger('click')
    await flushPromises()
    expect(mobileHeader(w, 'applog').attributes('aria-expanded')).toBe('true')
    expect(w.find('[data-test="app-log-card"]').exists()).toBe(true)
    expect(appStreams()).toHaveLength(1)
    expect(appStreams()[0]!.url.startsWith('/stream/tasks/42/app-logs?access_token=')).toBe(true)
    w.unmount()
  })

  it('모바일: 숨겨졌다 다시 보여도 저절로 연결하지 않는다', async () => {
    const w = await mountPage(deployed, 390)
    await mobileHeader(w, 'applog').trigger('click')
    await flushPromises()
    expect(appStreams()).toHaveLength(1)

    await refreshTo(w, redeploying) // 앱 로그 칸이 숨고 연결이 닫힌다
    expect(w.find('[data-test="section-applog"]').exists()).toBe(false)
    expect(appStreams()[0]!.readyState).toBe(CLOSED)

    await refreshTo(w, deployed) // 다시 보이지만 접힌 채다
    expect(w.find('[data-test="section-applog"]').exists()).toBe(true)
    expect(mobileHeader(w, 'applog').attributes('aria-expanded')).toBe('false')
    expect(appStreams()).toHaveLength(1)
    w.unmount()
  })

  it('모바일: 배포중이면 빌드 로그 칸, 펼치면 부모 liveLog', async () => {
    const w = await mountPage(redeploying, 390)
    expect(w.find('[data-test="section-buildlog"]').exists()).toBe(true)
    expect(w.find('[data-test="section-applog"]').exists()).toBe(false)
    expect(buildStreams()).toHaveLength(1)
    expect(mobileHeader(w, 'buildlog').attributes('aria-expanded')).toBe('false')
    expect(w.find('[data-test="section-buildlog"] pre.deploy-log').exists()).toBe(false)

    await mobileHeader(w, 'buildlog').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="section-buildlog"] pre.deploy-log').text()).toContain('로그 대기 중…')

    buildStreams()[0]!.emit('log', 'step 1')
    await flushPromises()
    expect(w.find('[data-test="section-buildlog"] pre.deploy-log').text()).toContain('step 1')
    expect(w.find('[data-test="section-buildlog"] pre.deploy-log').text()).not.toContain('로그 대기 중…')

    // 배포가 끝나면 빌드 로그 칸은 사라진다
    await refreshTo(w, deployed)
    expect(w.find('[data-test="section-buildlog"]').exists()).toBe(false)
    w.unmount()
  })

  it('데스크톱·모바일: 접으면 /stream 연결을 닫는다', async () => {
    // 데스크톱
    let w = await mountPage(deployed, 1024)
    await desktopHeader(w).trigger('click')
    await flushPromises()
    expect(appStreams()).toHaveLength(1)
    expect(appStreams()[0]!.readyState).not.toBe(CLOSED)
    await desktopHeader(w).trigger('click') // 같은 머리를 다시 눌러 접는다
    await flushPromises()
    expect(appStreams()).toHaveLength(1)
    expect(appStreams()[0]!.readyState).toBe(CLOSED)
    expect(w.find('[data-test="app-log-card"]').exists()).toBe(false)
    w.unmount()
    document.body.innerHTML = ''
    FakeEventSource.reset()

    // 모바일 390px
    w = await mountPage(deployed, 390)
    await mobileHeader(w, 'applog').trigger('click')
    await flushPromises()
    expect(appStreams()).toHaveLength(1)
    expect(appStreams()[0]!.readyState).not.toBe(CLOSED)
    await mobileHeader(w, 'applog').trigger('click')
    await flushPromises()
    expect(appStreams()).toHaveLength(1)
    expect(appStreams()[0]!.readyState).toBe(CLOSED)
    expect(w.find('[data-test="app-log-card"]').exists()).toBe(false)
    w.unmount()
  })

  it('데스크톱 빌드 로그는 그대로 .deploy-log', async () => {
    const w = await mountPage(redeploying, 1024)
    expect(w.find('[data-test="app-log-desktop"]').exists()).toBe(false)
    expect(buildStreams()).toHaveLength(1)
    expect(w.find('pre.deploy-log').text()).toContain('로그 대기 중…')

    buildStreams()[0]!.emit('log', 'step 1')
    await flushPromises()
    expect(w.find('pre.deploy-log').text()).toContain('step 1')
    w.unmount()
  })

  it('.deploy-log 스타일은 전역 main.css에 있다 — 모바일 칸의 pre도 같은 모양을 쓴다', () => {
    const css = readFileSync(resolve(__dirname, '../assets/css/main.css'), 'utf8')
    expect(css).toMatch(/\.deploy-log\s*\{/)
    // 페이지의 scoped 스타일에 남아 있으면 자식 컴포넌트(TaskDetailMobile)의 pre에는 적용되지 않는다
    const page = readFileSync(resolve(__dirname, '../pages/tasks/[id].vue'), 'utf8')
    const scopedStyle = page.slice(page.indexOf('<style scoped>'))
    expect(scopedStyle).not.toMatch(/\.deploy-log\s*\{/)
  })
})

describe('TaskDetailMobile — 앱 로그·빌드 로그 칸 (스펙 2026-11-03 §5)', () => {
  const props = (over: { task?: object; appLogViewable?: boolean; liveLog?: string }) => ({
    task: { ...deployed, ...over.task },
    isAdmin: false,
    steps: [],
    appLogViewable: over.appLogViewable ?? false,
    liveLog: over.liveLog ?? '',
  })
  const keys = (w: ReturnType<typeof mount>) => w.findAll('[data-test^="section-"]').map((s) => s.attributes('data-test'))

  afterEach(() => { document.body.innerHTML = '' })

  it('칸 순서는 배포 → 앱 로그 → 빌드 로그 → 지난 인터뷰 → 정보이고 정보 칸이 마지막이다', () => {
    const w = mount(TaskDetailMobile, {
      ...mountOpts,
      props: props({ task: { status: 'DEPLOYING' }, appLogViewable: true }),
      slots: { applog: '<div data-test="applog-slot">slot</div>' },
    })
    const k = keys(w)
    expect(k.slice(k.indexOf('section-deploy'))).toEqual([
      'section-deploy', 'section-applog', 'section-buildlog', 'section-interviews', 'section-info',
    ])
    w.unmount()
  })

  it('앱 로그 칸은 appLogViewable일 때만 보이고, 슬롯은 펼쳤을 때만 마운트된다', async () => {
    const w = mount(TaskDetailMobile, {
      ...mountOpts,
      props: props({ appLogViewable: true }),
      slots: { applog: '<div data-test="applog-slot">slot</div>' },
    })
    expect(w.find('[data-test="section-applog"]').exists()).toBe(true)
    expect(w.find('[data-test="applog-slot"]').exists()).toBe(false)
    await w.find('[data-test="section-applog"] [role="button"]').trigger('click')
    expect(w.find('[data-test="applog-slot"]').exists()).toBe(true)

    await w.setProps({ appLogViewable: false })
    expect(w.find('[data-test="section-applog"]').exists()).toBe(false)
    await w.setProps({ appLogViewable: true })
    expect(w.find('[data-test="section-applog"] [role="button"]').attributes('aria-expanded')).toBe('false')
    expect(w.find('[data-test="applog-slot"]').exists()).toBe(false)
    w.unmount()
  })

  it('빌드 로그 칸은 배포중·배포중지중에만 보이고, 숨겨졌다 다시 보이면 접힌 채다', async () => {
    const w = mount(TaskDetailMobile, { ...mountOpts, props: props({ task: { status: 'UNDEPLOYING' }, liveLog: 'a\n' }) })
    expect(w.find('[data-test="section-buildlog"]').exists()).toBe(true)
    await w.find('[data-test="section-buildlog"] [role="button"]').trigger('click')
    expect(w.find('[data-test="section-buildlog"] pre.deploy-log').text()).toContain('a')

    await w.setProps({ task: { ...deployed } })
    expect(w.find('[data-test="section-buildlog"]').exists()).toBe(false)
    await w.setProps({ task: { ...deployed, status: 'DEPLOYING' } })
    expect(w.find('[data-test="section-buildlog"] [role="button"]').attributes('aria-expanded')).toBe('false')
    w.unmount()
  })
})
