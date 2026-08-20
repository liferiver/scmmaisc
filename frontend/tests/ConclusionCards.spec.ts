import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { Conclusion } from '@/types'
import ConclusionCards from '@/components/ConclusionCards.vue'

/**
 * ConclusionCards 测试（T024）：D3 契约 12 要素渲染；缺失要素占位「——」；
 * 结论为空显示空态；conclusionNote 降级说明展示。
 */
function fullConclusion(): Conclusion {
  return {
    theory: {
      coreModel: '经济订货批量模型',
      derivation: 'Q*=√(2DS/H)',
      assumptions: '需求恒定',
      knowledgeLocation: '第2章',
    },
    practice: {
      paramBusiness: '年需求量',
      caseBenchmark: '京东案例',
      simRealityGap: '仿真简化',
      suggestions: '分批次采购',
    },
    frontier: {
      industry: '智能补货',
      academic: '绿色供应链',
      studentAdvice: '建议学习',
      voteItem: '投票：智慧物流',
    },
  }
}

describe('ConclusionCards 三维结论', () => {
  it('完整结论：三段 × 4 要素共 12 项（D3 契约）', () => {
    const wrapper = mount(ConclusionCards, { props: { conclusion: fullConclusion() } })

    expect(wrapper.findAll('[data-test="conclusion-card"]')).toHaveLength(3)
    expect(wrapper.findAll('[data-test="conclusion-item"]')).toHaveLength(12)
    expect(wrapper.text()).toContain('理论维度')
    expect(wrapper.text()).toContain('实操维度')
    expect(wrapper.text()).toContain('前沿维度')
    expect(wrapper.text()).toContain('经济订货批量模型')
    expect(wrapper.text()).toContain('投票：智慧物流')
    expect(wrapper.find('[data-test="conclusion-empty"]').exists()).toBe(false)
  })

  it('缺失要素：显示占位「——」不崩溃（T040 边界）', () => {
    const partial: Conclusion = {
      theory: { coreModel: '核心模型' }, // 缺 3 项
      practice: {}, // 全缺
      frontier: { industry: '', academic: '  ' }, // 空白视为缺失
    }
    const wrapper = mount(ConclusionCards, { props: { conclusion: partial } })

    const values = wrapper.findAll('.item-value').map((n) => n.text())
    expect(values).toHaveLength(12)
    expect(values.filter((v) => v === '——')).toHaveLength(11) // 12 - 1（仅 coreModel 有值）
    expect(values.filter((v) => v === '核心模型')).toHaveLength(1)
  })

  it('结论为空：显示空态占位', () => {
    const wrapper = mount(ConclusionCards, { props: { conclusion: null } })

    expect(wrapper.find('[data-test="conclusion-empty"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test="conclusion-item"]')).toHaveLength(0)
  })

  it('降级说明：note 展示为警告提示', () => {
    const wrapper = mount(ConclusionCards, {
      props: { conclusion: fullConclusion(), note: 'LLM 输出格式异常，已降级展示占位结论' },
    })

    const note = wrapper.find('[data-test="conclusion-note"]')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('降级')
  })
})
