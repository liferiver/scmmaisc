import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CompareView from '@/views/CompareView.vue'
import type { OutputValue, ParamSet } from '@/types'

/**
 * CompareView 组件测试（T035，FR-011）：空态引导、多方案同指标对比渲染、
 * 取消勾选提示、删除需确认（jsdom 无 canvas，echarts 以 mock 替代）。
 *
 * 注意：el-table 的列注册发生在自身 onMounted 的「await nextTick()」之后，
 * 单次 nextTick 无法观察到已渲染的单元格，统一用 flushPromises() 等待。
 */
vi.mock('echarts', () => ({
  init: vi.fn(() => ({
    setOption: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn(),
    getDataURL: vi.fn(() => 'data:image/png;base64,AA'),
  })),
}))

const STORAGE_KEY = 'param_sets'

function output(partial: Partial<OutputValue>): OutputValue {
  return { key: 'k', label: '指标', type: 'scalar', value: 1, ...partial }
}

function set(id: string, name: string, qStar: number): ParamSet {
  return {
    id,
    scenarioId: 5,
    scenarioName: 'EOQ 经济订货批量',
    name,
    params: { annual_demand: qStar === 1000 ? 10000 : 8000 },
    seed: 42,
    result: {
      runId: 1,
      status: 'COMPLETED',
      params: {},
      seed: 42,
      durationMs: 10,
      outputs: [
        output({ key: 'q_star', label: '最优订货量', type: 'scalar', value: qStar, unit: '件' }),
        output({
          key: 'cost_curve',
          label: '年成本曲线',
          type: 'series',
          value: { x: [1, 2, 3], series: [{ name: '总成本', data: [qStar * 2, qStar, qStar / 2] }] },
        }),
        output({ key: 'flow', label: '流拓扑', type: 'topo', value: { nodes: [], edges: [] } }),
      ],
      steps: [],
    },
    savedAt: '2026-08-13T10:00:00.000Z',
  }
}

function seedStorage(sets: ParamSet[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(sets))
}

function mountView() {
  return mount(CompareView, {
    global: {
      stubs: { 'router-link': { template: '<a><slot /></a>' } },
    },
  })
}

describe('CompareView（T038）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    class ResizeObserverStub {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
    vi.stubGlobal('ResizeObserver', ResizeObserverStub)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.clearAllMocks()
    document.body.replaceChildren()
  })

  it('空态：无方案时展示引导与「去运行场景」入口', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="compare-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="compare-goto"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="compare-table"]').exists()).toBe(false)
  })

  it('多方案：勾选≥2 时渲染方案表、指标对比表与对比图', async () => {
    seedStorage([set('a1', '方案A', 1000), set('b2', '方案B', 800)])
    const wrapper = mountView()
    await flushPromises()

    // 方案列表（2 组）+ 导出入口
    expect(wrapper.find('[data-test="compare-table"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test="compare-table"] .el-table__row')).toHaveLength(2)
    expect(wrapper.find('[data-test="compare-export-csv"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="compare-need-two"]').exists()).toBe(false)

    // 同名指标对比表：q_star / cost_curve / flow（结构型也列出，仅图表排除）
    const rows = wrapper.findAll('[data-test="compare-compare-table"] .el-table__row')
    expect(rows).toHaveLength(3)
    expect(rows[0].text()).toContain('最优订货量')
    expect(rows[0].text()).toContain('1000')
    expect(rows[0].text()).toContain('800')

    // 指标选择 + 默认第一个可对比指标（q_star）→ 对比图渲染
    expect(wrapper.find('[data-test="compare-indicator-select"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="compare-chart"]').exists()).toBe(true)
  })

  it('取消勾选至不足 2 组：提示「请勾选至少 2 组方案」且隐藏对比区', async () => {
    seedStorage([set('a1', '方案A', 1000), set('b2', '方案B', 800)])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="compare-chart"]').exists()).toBe(true)

    // 表头另有「全选」复选框，仅取行内勾选框；jsdom 点击不会触发 checkbox 激活行为，
    // 手动置 checked 后派发 change（el-checkbox 以 change 事件同步选中状态）
    const boxes = wrapper.findAll('[data-test="compare-table"] .el-table__row .el-checkbox input')
    expect(boxes).toHaveLength(2)
    ;(boxes[0].element as HTMLInputElement).checked = false
    await boxes[0].trigger('change')
    await flushPromises()

    expect(wrapper.find('[data-test="compare-need-two"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="compare-chart"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="compare-export-csv"]').exists()).toBe(false)
  })

  it('删除方案需确认：确认后从列表与存储移除，勾选同步收窄', async () => {
    seedStorage([set('a1', '方案A', 1000), set('b2', '方案B', 800)])
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="compare-delete-a1"]').trigger('click')
    await flushPromises()
    const confirm = document.querySelector('.el-popconfirm__action .el-button--primary') as HTMLElement | null
    expect(confirm).not.toBeNull()
    confirm!.click()
    await flushPromises()

    const raw = JSON.parse(localStorage.getItem(STORAGE_KEY)!) as { id: string }[]
    expect(raw.map((s) => s.id)).toEqual(['b2'])
    expect(wrapper.findAll('[data-test="compare-table"] .el-table__row')).toHaveLength(1)
    // 删除后只剩 1 组 → 提示需至少 2 组
    expect(wrapper.find('[data-test="compare-need-two"]').exists()).toBe(true)
  })

  it('结构化输出（gauge/dist/heatmap）在对比表中显示紧凑摘要，不出现 [object Object]', async () => {
    const a = set('a1', '方案A', 1000)
    a.result.outputs.push(
      output({ key: 'score', label: '综合得分', type: 'gauge', value: [{ name: '综合得分', value: 82 }], unit: '%' }),
      output({ key: 'mode', label: '配送方式分布', type: 'dist', value: [{ name: '上门', value: 0.4 }, { name: '自提', value: 0.6 }] }),
      output({
        key: 'hm',
        label: '风险热力',
        type: 'heatmap',
        value: { rows: ['R1', 'R2'], columns: ['C1', 'C2', 'C3'], data: [[1, 2, 3], [4, 5, 6]] },
      }),
    )
    const b = set('b2', '方案B', 800)
    b.result.outputs.push(...a.result.outputs.map((o) => ({ ...o })))
    seedStorage([a, b])
    const wrapper = mountView()
    await flushPromises()

    // 原有 3 个（scalar/series/topo）+ 新增 3 个（gauge/dist/heatmap）
    expect(wrapper.findAll('[data-test="compare-compare-table"] .el-table__row')).toHaveLength(6)
    const text = wrapper.find('[data-test="compare-compare-table"]').text()
    expect(text).toContain('综合得分=82')
    expect(text).toContain('上门=0.4，自提=0.6')
    expect(text).toContain('热力图（2×3）')
    expect(text).not.toContain('[object Object]')
  })
})
