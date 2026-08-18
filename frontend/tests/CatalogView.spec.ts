import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import type { Chapter, ScenarioSummary } from '@/types'

// mock API 层：场景数据全部来自 C1/C2
vi.mock('@/api/http', () => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}))

const pushMock = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))

import { get } from '@/api/http'
import CatalogView from '@/views/CatalogView.vue'

const getMock = vi.mocked(get)

const chapters: Chapter[] = [
  { id: 1, code: 'CH1', name: '概论', sortNo: 1, scenarioCount: 2 },
  { id: 2, code: 'CH2', name: '物流系统控制', sortNo: 2, scenarioCount: 1 },
]

const scenarios: ScenarioSummary[] = [
  { id: 1, chapterId: 1, moduleId: 'CH1-002', name: '物流 7R 服务目标履约', difficulty: 'intro', classHours: 2, isRolePlay: false, deps: [] },
  { id: 2, chapterId: 1, moduleId: 'CH1-004', name: '自营/3PL/联盟/4PL 模式对比', difficulty: 'basic', classHours: 2, isRolePlay: false, deps: [] },
  { id: 3, chapterId: 2, moduleId: 'CH2-003', name: 'EOQ 经济订货批量', difficulty: 'intro', classHours: 2, isRolePlay: false, deps: [] },
  { id: 4, chapterId: 2, moduleId: 'CH9-006', name: '供应链金融综合实训', difficulty: 'comprehensive', classHours: 2, isRolePlay: true, deps: [] },
]

function mockOk() {
  getMock.mockImplementation((url: string) => {
    if (url === '/chapters') return Promise.resolve(chapters)
    if (url === '/scenarios') return Promise.resolve(scenarios)
    return Promise.reject(new Error(`unknown url: ${url}`))
  })
}

describe('CatalogView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    pushMock.mockReset()
  })

  it('渲染章节面板与场景卡片（moduleId/名称/难度标签）', async () => {
    mockOk()
    const wrapper = mount(CatalogView)
    await flushPromises()

    expect(wrapper.find('[data-test="catalog-content"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('概论')
    expect(wrapper.text()).toContain('物流系统控制')
    const cards = wrapper.findAll('[data-test="scenario-card"]')
    expect(cards.length).toBe(4)
    expect(wrapper.text()).toContain('CH1-002')
    expect(wrapper.text()).toContain('物流 7R 服务目标履约')
    expect(wrapper.text()).toContain('入门')
    expect(wrapper.text()).toContain('基础')
  })

  it('comprehensive 难度展示「综合」标签（FR-016）', async () => {
    mockOk()
    const wrapper = mount(CatalogView)
    await flushPromises()

    const cards = wrapper.findAll('[data-test="scenario-card"]')
    const comprehensiveCard = cards.find((c) => c.text().includes('CH9-006'))
    expect(comprehensiveCard).toBeTruthy()
    expect(comprehensiveCard!.text()).toContain('综合')
    expect(comprehensiveCard!.text()).toContain('角色扮演')
  })

  it('加载中显示 loading 态', async () => {
    let resolveChapters: (v: Chapter[]) => void = () => {}
    getMock.mockImplementation((url: string) => {
      if (url === '/chapters') {
        return new Promise<Chapter[]>((r) => {
          resolveChapters = r
        })
      }
      return Promise.resolve(scenarios)
    })
    const wrapper = mount(CatalogView)
    await nextTick()
    expect(wrapper.find('[data-test="catalog-loading"]').exists()).toBe(true)

    // 完成后进入内容态
    resolveChapters(chapters)
    await flushPromises()
    expect(wrapper.find('[data-test="catalog-content"]').exists()).toBe(true)
  })

  it('无数据时显示空态', async () => {
    getMock.mockImplementation((url: string) => {
      if (url === '/chapters') return Promise.resolve([])
      return Promise.resolve([])
    })
    const wrapper = mount(CatalogView)
    await flushPromises()
    expect(wrapper.find('[data-test="catalog-empty"]').exists()).toBe(true)
  })

  it('请求失败显示错误态并可重试', async () => {
    getMock
      .mockRejectedValueOnce(new Error('网络错误'))
      .mockImplementation((url: string) => {
        if (url === '/chapters') return Promise.resolve(chapters)
        return Promise.resolve(scenarios)
      })
    const wrapper = mount(CatalogView)
    await flushPromises()

    expect(wrapper.find('[data-test="catalog-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('网络错误')

    await wrapper.find('[data-test="catalog-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="scenario-card"]').exists()).toBe(true)
  })

  it('点击场景卡片跳转详情路由', async () => {
    mockOk()
    const wrapper = mount(CatalogView)
    await flushPromises()

    await wrapper.findAll('[data-test="scenario-card"]')[0].trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/scenarios/CH1-002')
  })
})
