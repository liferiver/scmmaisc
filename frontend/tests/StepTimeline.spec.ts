import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import StepTimeline from '@/components/StepTimeline.vue'
import type { StepEvent } from '@/types'

/**
 * StepTimeline 单元测试（T033，FR-009/FR-014）：
 * 上一步/下一步边界、自动播放推进与终止、点击节点跳转、数据快照渲染。
 */

function steps(count = 3): StepEvent[] {
  return Array.from({ length: count }, (_, i) => ({
    stepNo: i + 1,
    eventType: 'STEP' as const,
    message: `第 ${i + 1} 步说明`,
    data: { key: `v${i + 1}` },
  }))
}

describe('StepTimeline', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('渲染步骤指示与当前步骤消息', () => {
    const wrapper = mount(StepTimeline, { props: { steps: steps() } })
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 1 / 3')
    expect(wrapper.find('[data-test="step-message"]').text()).toBe('第 1 步说明')
    expect(wrapper.findAll('[data-test^="step-item-"]').length).toBe(3)
  })

  it('下一步/上一步推进并更新指示', async () => {
    const wrapper = mount(StepTimeline, { props: { steps: steps() } })
    await wrapper.find('[data-test="step-next"]').trigger('click')
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 2 / 3')
    expect(wrapper.find('[data-test="step-message"]').text()).toBe('第 2 步说明')

    await wrapper.find('[data-test="step-prev"]').trigger('click')
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 1 / 3')
  })

  it('边界：第一步上一步禁用、最后一步下一步禁用', async () => {
    const wrapper = mount(StepTimeline, { props: { steps: steps() } })
    expect((wrapper.find('[data-test="step-prev"]').attributes('disabled'))).toBeDefined()

    await wrapper.find('[data-test="step-next"]').trigger('click')
    await wrapper.find('[data-test="step-next"]').trigger('click')
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 3 / 3')
    expect((wrapper.find('[data-test="step-next"]').attributes('disabled'))).toBeDefined()
  })

  it('自动播放逐步推进并在末步停止', async () => {
    const wrapper = mount(StepTimeline, { props: { steps: steps() } })
    await wrapper.find('[data-test="step-play"]').trigger('click')

    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 2 / 3')
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 3 / 3')

    // 末步后停止自动播放，不再推进
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 3 / 3')
    expect(wrapper.find('[data-test="step-play"]').text()).toContain('自动播放')
  })

  it('自动播放可暂停', async () => {
    const wrapper = mount(StepTimeline, { props: { steps: steps() } })
    await wrapper.find('[data-test="step-play"]').trigger('click')
    await vi.advanceTimersByTimeAsync(1500)
    await wrapper.find('[data-test="step-play"]').trigger('click')
    expect(wrapper.find('[data-test="step-play"]').text()).toContain('自动播放')
    await vi.advanceTimersByTimeAsync(3000)
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 2 / 3')
  })

  it('点击时间线节点跳转并渲染数据快照', async () => {
    const wrapper = mount(StepTimeline, { props: { steps: steps() } })
    await wrapper.find('[data-test="step-item-2"]').trigger('click')
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 3 / 3')
    expect(wrapper.find('[data-test="step-detail"]').text()).toContain('v3')
  })

  it('v-model 同步当前索引', async () => {
    const wrapper = mount(StepTimeline, { props: { steps: steps(), modelValue: 1 } })
    expect(wrapper.find('[data-test="step-indicator"]').text()).toContain('步骤 2 / 3')
    await wrapper.find('[data-test="step-next"]').trigger('click')
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1]).toEqual([2])
    await flushPromises()
  })
})
