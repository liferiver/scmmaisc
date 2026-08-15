import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ScenarioDetail, ScenarioSummary } from '@/types'

// mock API 层：详情与目录均来自 C2/C3
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
import ScenarioDetailView from '@/views/ScenarioDetailView.vue'

const getMock = vi.mocked(get)

const scenarios: ScenarioSummary[] = [
  { id: 1, chapterId: 2, moduleId: 'CH2-002', name: '物流成本与动态平衡', difficulty: 'basic', classHours: 2, isRolePlay: false, deps: [] },
  { id: 2, chapterId: 2, moduleId: 'CH2-003', name: 'EOQ 经济订货批量', difficulty: 'intro', classHours: 2, isRolePlay: false, deps: ['CH2-002', 'CH9-999'] },
]

/** CH2-003 详情：依赖 CH2-002（已实现）与 CH9-999（未实现）。 */
function eoqDetail(): ScenarioDetail {
  return {
    id: 2,
    chapterId: 2,
    moduleId: 'CH2-003',
    name: 'EOQ 经济订货批量',
    difficulty: 'intro',
    classHours: 2,
    isRolePlay: false,
    deps: ['CH2-002', 'CH9-999'],
    engineKey: 'eoq',
    concept: '经济订货批量模型',
    description: '按经济订货批量决策',
    params: [
      { key: 'annual_demand', label: '年需求量', type: 'int', min: 100, max: 100000, default: 10000 },
      { key: 'holding_cost', label: '持有成本', type: 'float', min: 1, max: 500, default: 2 },
    ],
    outputs: [{ key: 'q_star', label: '经济订货批量', type: 'scalar', unit: '件' }],
    constraints: [],
  }
}

function mockOk(detail: ScenarioDetail = eoqDetail()) {
  getMock.mockImplementation((url: string) => {
    if (url === '/scenarios') return Promise.resolve(scenarios)
    if (url === '/scenarios/CH2-003') return Promise.resolve(detail)
    return Promise.reject(new Error(`unknown url: ${url}`))
  })
}

describe('ScenarioDetailView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    pushMock.mockReset()
  })

  it('渲染详情：概念/参数表/输出表与已实现依赖的跳转链接（FR-013）', async () => {
    mockOk()
    const wrapper = mount(ScenarioDetailView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    expect(wrapper.find('[data-test="detail-content"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('经济订货批量模型')
    const links = wrapper.findAll('[data-test="dep-link"]')
    expect(links).toHaveLength(1)
    expect(links[0].text()).toContain('CH2-002')

    await links[0].trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/scenarios/CH2-002')
  })

  it('依赖模块未实现时标注"尚未开放"且不提供跳转（T040 边界：提示而非阻断）', async () => {
    mockOk()
    const wrapper = mount(ScenarioDetailView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    const pending = wrapper.findAll('[data-test="dep-pending"]')
    expect(pending).toHaveLength(1)
    expect(pending[0].text()).toContain('CH9-999')
    expect(pending[0].text()).toContain('尚未开放')
    expect(wrapper.text()).not.toContain('/scenarios/CH9-999')
  })

  it('目录不可用（加载失败）时依赖仍按可跳转处理，不误标"尚未开放"', async () => {
    getMock.mockImplementation((url: string) => {
      if (url === '/scenarios') return Promise.reject(new Error('目录服务不可用'))
      if (url === '/scenarios/CH2-003') return Promise.resolve(eoqDetail())
      return Promise.reject(new Error(`unknown url: ${url}`))
    })
    const wrapper = mount(ScenarioDetailView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    expect(wrapper.findAll('[data-test="dep-pending"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-test="dep-link"]')).toHaveLength(2)
  })

  it('参数缺少默认值时展示降级提示（T040 边界：数据缺失不崩溃）', async () => {
    const broken = eoqDetail()
    broken.params = [
      { key: 'annual_demand', label: '年需求量', type: 'int', min: 100, max: 100000 },
      { key: 'order_cost', label: '订货成本', type: 'float', min: 10, max: 5000 },
    ]
    mockOk(broken)
    const wrapper = mount(ScenarioDetailView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    const alert = wrapper.find('[data-test="missing-defaults-alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('annual_demand')
    expect(alert.text()).toContain('order_cost')
    expect(alert.text()).toContain('需手动填写')
  })

  it('参数均含默认值时无降级提示', async () => {
    mockOk()
    const wrapper = mount(ScenarioDetailView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    expect(wrapper.find('[data-test="missing-defaults-alert"]').exists()).toBe(false)
  })

  it('详情请求失败显示错误态并可重试', async () => {
    // 目录请求先行消费，不能用 mockImplementationOnce；用状态位让详情首次失败、重试成功
    let detailFailed = false
    getMock.mockImplementation((url: string) => {
      if (url === '/scenarios') return Promise.resolve(scenarios)
      if (url === '/scenarios/CH2-003') {
        if (!detailFailed) {
          detailFailed = true
          return Promise.reject(new Error('网络错误'))
        }
        return Promise.resolve(eoqDetail())
      }
      return Promise.reject(new Error(`unknown url: ${url}`))
    })
    const wrapper = mount(ScenarioDetailView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    expect(wrapper.find('[data-test="detail-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('网络错误')

    await wrapper.find('[data-test="detail-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="detail-content"]').exists()).toBe(true)
  })
})
