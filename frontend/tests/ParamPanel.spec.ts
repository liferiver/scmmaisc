import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ParamPanel from '@/components/ParamPanel.vue'
import type { Parameter, ParamGroupConstraint } from '@/types'

/**
 * 参数面板单元测试（T022）：按类型渲染控件、越界/非数字即时红字提示、不可提交（FR-004）。
 */
describe('ParamPanel', () => {
  const params: Parameter[] = [
    { key: 'annual_demand', label: '年需求量', type: 'int', min: 100, max: 100000, default: 10000 },
    { key: 'order_cost', label: '订货成本', type: 'float', min: 10, max: 5000, default: 100 },
    {
      key: 'mode',
      label: '运输方式',
      type: 'enum',
      options: [
        { label: '公路', value: 'road' },
        { label: '铁路', value: 'rail' },
      ],
      default: 'road',
    },
    { key: 'info_share', label: '信息共享', type: 'bool', default: false },
    {
      key: 'demand_dist',
      label: '需求分布',
      type: 'dist',
      fields: [
        { key: 'mu', label: '均值', type: 'float', default: 100 },
        { key: 'sigma', label: '标准差', type: 'float', min: 0.1, default: 10 },
      ],
    },
    { key: 'matrix', label: '距离矩阵', type: 'matrix', default: [[10, 20]] },
    { key: 'strategy', label: '策略', type: 'func', options: [{ label: '基本库存', value: 'base-stock' }] },
  ]

  function defaults(): Record<string, unknown> {
    return {
      annual_demand: 10000,
      order_cost: 100,
      mode: 'road',
      info_share: false,
      demand_dist: { mu: 100, sigma: 10 },
      matrix: [[10, 20]],
      strategy: 'base-stock',
    }
  }

  it('按类型渲染对应控件', () => {
    const wrapper = mount(ParamPanel, {
      props: { params, modelValue: defaults() },
    })
    // int/float → 数字输入
    expect(wrapper.find('[data-test="param-annual_demand"] input').exists()).toBe(true)
    expect(wrapper.find('[data-test="param-order_cost"] input').exists()).toBe(true)
    // enum / func → 下拉选择
    expect(wrapper.find('[data-test="param-mode"]').find('.el-select').exists()).toBe(true)
    expect(wrapper.find('[data-test="param-strategy"]').find('.el-select').exists()).toBe(true)
    // bool → 开关
    expect(wrapper.find('[data-test="param-info_share"] .el-switch').exists()).toBe(true)
    // dist → 子参数组
    expect(wrapper.find('[data-test="param-demand_dist"]').find('.el-input-number').exists()).toBe(true)
    // matrix → 表格
    expect(wrapper.find('[data-test="param-matrix"] table').exists()).toBe(true)
  })

  it('越界输入：即时红字提示且不可提交', async () => {
    const wrapper = mount(ParamPanel, {
      props: { params, modelValue: defaults() },
    })
    await wrapper.find('[data-test="param-annual_demand"] input').setValue('50')
    await nextTick()
    const error = wrapper.find('[data-test="param-error-annual_demand"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toContain('不能小于 100')
    expect(wrapper.find('[data-test="run-submit"]').attributes('disabled')).toBeDefined()
  })

  it('非数字/空值：即时红字提示且不可提交', async () => {
    const invalid = defaults()
    invalid.order_cost = 'abc'
    const wrapper = mount(ParamPanel, {
      props: { params, modelValue: invalid },
    })
    await nextTick()
    expect(wrapper.find('[data-test="param-error-order_cost"]').text()).toContain('必须为数字')

    await wrapper.find('[data-test="param-order_cost"] input').setValue('')
    await nextTick()
    expect(wrapper.find('[data-test="param-error-order_cost"]').text()).toContain('必填')
    expect(wrapper.find('[data-test="run-submit"]').attributes('disabled')).toBeDefined()
  })

  it('合法输入：无错误提示、可提交并触发 submit 事件', async () => {
    const wrapper = mount(ParamPanel, {
      props: { params, modelValue: defaults() },
    })
    await nextTick()
    expect(wrapper.find('[data-test="param-error-annual_demand"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="run-submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-test="run-submit"]').trigger('click')
    expect(wrapper.emitted('submit')).toHaveLength(1)
  })

  it('父级回写：值一致时不再派发 update:modelValue（回归：递归更新死循环）', async () => {
    const wrapper = mount(ParamPanel, {
      props: { params, modelValue: defaults() },
    })
    // 输入合法值 → 归一化为 number 后派发一次
    await wrapper.find('[data-test="param-annual_demand"] input').setValue('8000')
    await nextTick()
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1][0]).toEqual({ ...defaults(), annual_demand: 8000 })

    // 模拟 RunView v-model 回写：把 emit 值写回 modelValue → 不应再产生新 emit
    const last = emitted![emitted!.length - 1][0] as Record<string, unknown>
    await wrapper.setProps({ modelValue: last })
    await nextTick()
    await nextTick()
    expect(wrapper.emitted('update:modelValue')!.length).toBe(emitted!.length)
  })
})

describe('ParamPanel 语义化标量组约束（V11）', () => {
  const weightParams: Parameter[] = [
    { key: 'weight_tech', label: '技术维度权重', type: 'float', min: 0, max: 1, default: 0.2 },
    { key: 'weight_quality', label: '质量维度权重', type: 'float', min: 0, max: 1, default: 0.2 },
    { key: 'weight_response', label: '响应维度权重', type: 'float', min: 0, max: 1, default: 0.15 },
    { key: 'weight_delivery', label: '交付维度权重', type: 'float', min: 0, max: 1, default: 0.15 },
    { key: 'weight_cost', label: '成本维度权重', type: 'float', min: 0, max: 1, default: 0.2 },
    { key: 'weight_environment', label: '环境维度权重', type: 'float', min: 0, max: 1, default: 0.1 },
  ]

  function weightValues(): Record<string, unknown> {
    return {
      weight_tech: 0.2,
      weight_quality: 0.2,
      weight_response: 0.15,
      weight_delivery: 0.15,
      weight_cost: 0.2,
      weight_environment: 0.1,
    }
  }

  const weightSumGroup: ParamGroupConstraint = {
    name: 'weight_sum',
    message: '评估维度权重和必须等于 1',
    params: ['weight_tech', 'weight_quality', 'weight_response', 'weight_delivery', 'weight_cost', 'weight_environment'],
    op: '==',
    target: 1,
    targetParam: null,
  }

  it('权重和 = 1：组头展示实时合计且可提交', async () => {
    const wrapper = mount(ParamPanel, {
      props: { params: weightParams, modelValue: weightValues(), groups: [weightSumGroup] },
    })
    await nextTick()
    const label = wrapper.find('[data-test="param-group-weight_sum"]')
    expect(label.exists()).toBe(true)
    expect(label.text()).toContain('评估维度权重和必须等于 1')
    expect(label.text()).toContain('当前合计：1')
    expect(wrapper.find('[data-test="param-group-error-weight_sum"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="run-submit"]').attributes('disabled')).toBeUndefined()
  })

  it('权重和 ≠ 1：即时红字提示且不可提交', async () => {
    const wrapper = mount(ParamPanel, {
      props: { params: weightParams, modelValue: weightValues(), groups: [weightSumGroup] },
    })
    await wrapper.find('[data-test="param-weight_tech"] input').setValue('0.3')
    await nextTick()
    const error = wrapper.find('[data-test="param-group-error-weight_sum"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toContain('评估维度权重和必须等于 1')
    expect(error.text()).toContain('当前合计 1.1')
    expect(wrapper.find('[data-test="run-submit"]').attributes('disabled')).toBeDefined()
  })

  it('≤ 形态：合计 ≤ 常量时通过，超出时报错并禁提交（CH11-007 物资权重 ≤ 1）', async () => {
    const params: Parameter[] = [
      { key: 'weight_medicine', label: '药品', type: 'float', min: 0, max: 1, default: 0.4 },
      { key: 'weight_water', label: '水', type: 'float', min: 0, max: 1, default: 0.3 },
      { key: 'weight_food', label: '食品', type: 'float', min: 0, max: 1, default: 0.2 },
      { key: 'weight_tent', label: '帐篷', type: 'float', min: 0, max: 1, default: 0.1 },
    ]
    const group: ParamGroupConstraint = {
      name: 'weight_sum_ok',
      message: '物资优先级权重之和需 ≤ 1',
      params: ['weight_medicine', 'weight_water', 'weight_food', 'weight_tent'],
      op: '<=',
      target: 1,
      targetParam: null,
    }
    const wrapper = mount(ParamPanel, {
      props: {
        params,
        modelValue: { weight_medicine: 0.4, weight_water: 0.3, weight_food: 0.2, weight_tent: 0.1 },
        groups: [group],
      },
    })
    await nextTick()
    expect(wrapper.find('[data-test="param-group-error-weight_sum_ok"]').exists()).toBe(false)
    await wrapper.find('[data-test="param-weight_medicine"] input').setValue('0.9')
    await nextTick()
    expect(wrapper.find('[data-test="param-group-error-weight_sum_ok"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="run-submit"]').attributes('disabled')).toBeDefined()
  })

  it('右端为参数：成本合计 ≤ 预算（CH3-008）', async () => {
    const params: Parameter[] = [
      { key: 'cost_barcode', label: '条码', type: 'float', default: 10 },
      { key: 'cost_rfid', label: 'RFID', type: 'float', default: 20 },
      { key: 'budget', label: '预算', type: 'float', default: 100 },
    ]
    const group: ParamGroupConstraint = {
      name: 'budget_ok',
      message: '成本合计需 ≤ 预算',
      params: ['cost_barcode', 'cost_rfid'],
      op: '<=',
      target: null,
      targetParam: 'budget',
    }
    const wrapper = mount(ParamPanel, {
      props: {
        params,
        modelValue: { cost_barcode: 10, cost_rfid: 20, budget: 100 },
        groups: [group],
      },
    })
    await nextTick()
    expect(wrapper.find('[data-test="param-group-error-budget_ok"]').exists()).toBe(false)
    await wrapper.find('[data-test="param-cost_rfid"] input').setValue('120')
    await nextTick()
    expect(wrapper.find('[data-test="param-group-error-budget_ok"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="run-submit"]').attributes('disabled')).toBeDefined()
  })
})
