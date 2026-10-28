import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import RepoCatalog from '../pages/admin/repo-catalog.vue'
import { useApiMock } from './mocks/nuxt'

// QPage requires QLayout > QPageContainer ancestry; wrap for testing.
// Quasar renders q-input's data-test directly on the <input> element,
// so selectors use [data-test="..."] directly (not [data-test="..."] input).
const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(RepoCatalog) }),
      })
  },
})

afterEach(() => {
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

async function mountPage(rows: any[] = []) {
  useApiMock.mockResolvedValueOnce(rows) // onMounted load()
  const w = mount(PageWrapper, { attachTo: document.body })
  await flushPromises()
  return w
}

describe('admin/repo-catalog', () => {
  it('lists entries from /api/admin/repo-catalog on mount', async () => {
    const w = await mountPage([
      {
        id: 1, alias: 'Netis7.0', gitUrl: 'https://github.com/o/r.git', host: 'github',
        ownerRepo: 'o/r', defaultBranch: null, description: null, enabled: true,
        createdBy: 'admin', createdAt: '', updatedAt: '',
      },
      {
        id: 2, alias: 'GL', gitUrl: 'https://gitlab.hamon.vip/g/sub/p.git', host: 'gitlab',
        ownerRepo: 'g/sub/p', defaultBranch: null, description: null, enabled: true,
        createdBy: 'admin', createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z',
      },
    ])
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog')
    expect(w.text()).toContain('Netis7.0')
    // host:'gitlab' 행은 경고색(orange) 칩이 아니고, 'other' 행만 "등록 불가" 표시
    expect(w.text()).toContain('gitlab')
    w.unmount()
  })

  it('POSTs a new entry with alias + gitUrl', async () => {
    const w = await mountPage([])
    await w.find('[data-test="open-create"]').trigger('click')
    await flushPromises()

    // Quasar renders data-test on the <input> element itself, not a wrapper
    const aliasInput = document.querySelector('[data-test="form-alias"]') as HTMLInputElement
    const urlInput = document.querySelector('[data-test="form-giturl"]') as HTMLInputElement
    aliasInput.value = 'Netis7.0'
    aliasInput.dispatchEvent(new Event('input'))
    urlInput.value = 'https://github.com/micthebick84/netis7.0.git'
    urlInput.dispatchEvent(new Event('input'))
    await flushPromises()

    useApiMock.mockResolvedValueOnce({}) // POST
    useApiMock.mockResolvedValueOnce([]) // reload
    const submit = document.querySelector('[data-test="form-submit"]') as HTMLElement
    submit.click()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog', {
      method: 'POST',
      body: {
        alias: 'Netis7.0',
        gitUrl: 'https://github.com/micthebick84/netis7.0.git',
        defaultBranch: null,
        description: null,
        enabled: true,
        artifactEnabled: false,
        artifactPath: null,
        prebuildEnabled: false,
        prebuildCommand: null,
        prebuildArtifact: null,
        prebuildToolchain: null,
        noteFolder: null,
      },
    })
    w.unmount()
  })

  it('산출물 보관을 켜면 경로 입력이 보이고 함께 보낸다', async () => {
    const w = await mountPage([])
    await w.find('[data-test="open-create"]').trigger('click')
    await flushPromises()
    const aliasInput = document.querySelector('[data-test="form-alias"]') as HTMLInputElement
    const urlInput = document.querySelector('[data-test="form-giturl"]') as HTMLInputElement
    aliasInput.value = '동부병원'
    aliasInput.dispatchEvent(new Event('input'))
    urlInput.value = 'owner/netisdash'
    urlInput.dispatchEvent(new Event('input'))
    expect(document.querySelector('[data-test="form-artifact-path"]')).toBeNull()
    ;(document.querySelector('[data-test="form-artifact-enabled"]') as HTMLElement).click()
    await flushPromises()
    const pathInput = document.querySelector('[data-test="form-artifact-path"]') as HTMLInputElement
    pathInput.value = '/usr/local/tomcat/webapps/ROOT.war'
    pathInput.dispatchEvent(new Event('input'))
    await flushPromises()

    useApiMock.mockResolvedValueOnce({})
    useApiMock.mockResolvedValueOnce([])
    ;(document.querySelector('[data-test="form-submit"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog', expect.objectContaining({
      method: 'POST',
      body: expect.objectContaining({ artifactEnabled: true, artifactPath: '/usr/local/tomcat/webapps/ROOT.war' }),
    }))
    w.unmount()
  })

  function setInput(sel: string, value: string) {
    const el = document.querySelector(sel) as HTMLInputElement
    el.value = value
    el.dispatchEvent(new Event('input'))
  }

  async function fillRequired() {
    setInput('[data-test="form-alias"]', '동부병원')
    setInput('[data-test="form-giturl"]', 'owner/netisdash')
    await flushPromises()
  }

  it('미리 빌드를 켜면 세 입력이 보이고 함께 보낸다', async () => {
    const w = await mountPage([])
    await w.find('[data-test="open-create"]').trigger('click')
    await flushPromises()
    await fillRequired()
    expect(document.body.textContent).toContain('명령에 비밀번호·토큰을 넣지 마세요 — 배포 로그에 그대로 보입니다.')
    expect(document.querySelector('[data-test="form-prebuild-command"]')).toBeNull()
    expect(document.querySelector('[data-test="form-prebuild-artifact"]')).toBeNull()
    expect(document.querySelector('[data-test="form-prebuild-toolchain"]')).toBeNull()
    ;(document.querySelector('[data-test="form-prebuild-enabled"]') as HTMLElement).click()
    await flushPromises()
    setInput('[data-test="form-prebuild-command"]', ' mvn -B -o package ')
    setInput('[data-test="form-prebuild-artifact"]', 'hm-dash/target/*.war')
    setInput('[data-test="form-prebuild-toolchain"]', 'jdk8-maven')
    await flushPromises()

    useApiMock.mockResolvedValueOnce({})
    useApiMock.mockResolvedValueOnce([])
    ;(document.querySelector('[data-test="form-submit"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog', expect.objectContaining({
      method: 'POST',
      body: expect.objectContaining({
        prebuildEnabled: true,
        prebuildCommand: 'mvn -B -o package',
        prebuildArtifact: 'hm-dash/target/*.war',
        prebuildToolchain: 'jdk8-maven',
      }),
    }))
    w.unmount()
  })

  it('도구 이름을 비우면 prebuildToolchain은 null로 보낸다', async () => {
    const w = await mountPage([])
    await w.find('[data-test="open-create"]').trigger('click')
    await flushPromises()
    await fillRequired()
    ;(document.querySelector('[data-test="form-prebuild-enabled"]') as HTMLElement).click()
    await flushPromises()
    setInput('[data-test="form-prebuild-command"]', 'mvn -B -o package')
    setInput('[data-test="form-prebuild-artifact"]', 'hm-dash/target/*.war')
    await flushPromises()

    useApiMock.mockResolvedValueOnce({})
    useApiMock.mockResolvedValueOnce([])
    ;(document.querySelector('[data-test="form-submit"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog', expect.objectContaining({
      body: expect.objectContaining({ prebuildEnabled: true, prebuildToolchain: null }),
    }))
    w.unmount()
  })

  const prebuildRow = {
    id: 9, alias: '동부병원', gitUrl: 'https://gitlab.hamon.vip/g/p.git', host: 'gitlab',
    ownerRepo: 'g/p', defaultBranch: null, description: null, enabled: true,
    createdBy: 'admin', createdAt: '', updatedAt: '',
    artifactEnabled: false, artifactPath: null,
    prebuildEnabled: true, prebuildCommand: 'mvn -B -o package',
    prebuildArtifact: 'hm-dash/target/*.war', prebuildToolchain: 'jdk8-maven',
  }

  it('켜진 항목에만 목록에 미리 빌드 칩이 보인다', async () => {
    const w = await mountPage([
      prebuildRow,
      { ...prebuildRow, id: 10, alias: '꺼진 레포', prebuildEnabled: false, prebuildCommand: null, prebuildArtifact: null, prebuildToolchain: null },
    ])
    const chips = w.findAll('[data-test="catalog-prebuild-chip"]')
    expect(chips).toHaveLength(1)
    expect(chips[0].text()).toBe('미리 빌드')
    w.unmount()
  })

  it('수정 다이얼로그가 기존 미리 빌드 값을 채운다', async () => {
    const w = await mountPage([prebuildRow])
    const editBtn = w.findAll('button').find((b) => b.find('.q-icon').text() === 'edit')
    await editBtn!.trigger('click')
    await flushPromises()
    expect((document.querySelector('[data-test="form-prebuild-command"]') as HTMLInputElement).value).toBe('mvn -B -o package')
    expect((document.querySelector('[data-test="form-prebuild-artifact"]') as HTMLInputElement).value).toBe('hm-dash/target/*.war')
    expect((document.querySelector('[data-test="form-prebuild-toolchain"]') as HTMLInputElement).value).toBe('jdk8-maven')
    w.unmount()
  })

  const noteRow = {
    id: 11, alias: '동부병원 대시보드', gitUrl: 'https://gitlab.hamon.vip/a/b.git', host: 'gitlab',
    ownerRepo: 'a/b', defaultBranch: null, description: null, enabled: true,
    createdBy: 'admin', createdAt: '', updatedAt: '',
    artifactEnabled: false, artifactPath: null,
    prebuildEnabled: false, prebuildCommand: null, prebuildArtifact: null, prebuildToolchain: null,
    noteFolder: 'HamonSoft/동부병원',
  }

  async function openEditOf(w: any) {
    const editBtn = w.findAll('button').find((b: any) => b.find('.q-icon').text() === 'edit')
    await editBtn!.trigger('click')
    await flushPromises()
  }

  async function submitAndBody() {
    useApiMock.mockClear()
    useApiMock.mockResolvedValueOnce({})
    useApiMock.mockResolvedValueOnce([])
    ;(document.querySelector('[data-test="form-submit"]') as HTMLElement).click()
    await flushPromises()
    return useApiMock.mock.calls.find((c: any[]) => c[1]?.method === 'PUT' || c[1]?.method === 'POST')![1].body
  }

  it('목록에 노트 폴더가 보이고 비었으면 자동', async () => {
    const w = await mountPage([noteRow, { ...noteRow, id: 12, alias: '빈 레포', noteFolder: null }])
    const cells = w.findAll('[data-test="catalog-note-folder"]').map((c) => c.text())
    expect(cells).toEqual(['노트 폴더: HamonSoft/동부병원', '노트 폴더: 자동'])
    w.unmount()
  })

  it('노트 폴더를 안 바꾸고 저장하면 PUT 바디에 noteFolder가 없다', async () => {
    const w = await mountPage([noteRow])
    await openEditOf(w)
    expect((document.querySelector('[data-test="form-note-folder"]') as HTMLInputElement).value).toBe('HamonSoft/동부병원')
    const body = await submitAndBody()
    expect(body).not.toHaveProperty('noteFolder')
    w.unmount()
  })

  it('노트 폴더를 바꾸면 그 값을 보낸다', async () => {
    const w = await mountPage([noteRow])
    await openEditOf(w)
    setInput('[data-test="form-note-folder"]', ' HamonSoft/Netis-플랫폼공통/netisweb_v6.8 ')
    await flushPromises()
    expect(await submitAndBody()).toMatchObject({ noteFolder: 'HamonSoft/Netis-플랫폼공통/netisweb_v6.8' })
    w.unmount()
  })

  it('노트 폴더를 지우면 빈 문자열을 보낸다', async () => {
    const w = await mountPage([noteRow])
    await openEditOf(w)
    setInput('[data-test="form-note-folder"]', '')
    await flushPromises()
    expect(await submitAndBody()).toMatchObject({ noteFolder: '' })
    w.unmount()
  })

  it('등록 때 노트 폴더를 함께 보낸다', async () => {
    const w = await mountPage([])
    await w.find('[data-test="open-create"]').trigger('click')
    await flushPromises()
    await fillRequired()
    setInput('[data-test="form-note-folder"]', 'HamonSoft/동부병원')
    await flushPromises()
    expect(await submitAndBody()).toMatchObject({ noteFolder: 'HamonSoft/동부병원' })
    w.unmount()
  })

  it('활성 토글 바디에는 noteFolder가 없다', async () => {
    const w = await mountPage([noteRow])
    useApiMock.mockClear()
    useApiMock.mockResolvedValueOnce({})
    useApiMock.mockResolvedValueOnce([])
    await w.find('.q-toggle').trigger('click')
    await flushPromises()
    const put = useApiMock.mock.calls.find((c: any[]) => c[1]?.method === 'PUT')!
    expect(put[1].body).not.toHaveProperty('noteFolder')
    w.unmount()
  })
})
