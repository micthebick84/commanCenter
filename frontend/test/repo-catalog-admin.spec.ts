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
})
