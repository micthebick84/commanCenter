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
    ])
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog')
    expect(w.text()).toContain('Netis7.0')
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
      },
    })
    w.unmount()
  })
})
