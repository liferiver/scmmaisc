import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage, ElPagination } from 'element-plus'
import type { DiscussionHistory, DiscussionHistoryItem } from '@/types'

/**
 * DiscussionHistoryView 测试（T042，US4）：D6 历史列表渲染（状态标签/场景名/时间/轮次/发言数）、
 * 分页翻页、回看跳转（?sessionId=&readonly=1）、导出 D7 触发（仅 COMPLETED 可用，
 * downloadFile 复用）、空态/错误态。用 vi.hoisted 共享 storeMock，对齐 DiscussionView.spec 模式。
 */
const { storeMock, pushMock } = vi.hoisted(() => {
  const storeMock = {
    history: null as DiscussionHistory | null,
    historyLoading: false,
    error: '',
    fetchHistory: vi.fn(),
    exportMarkdown: vi.fn(),
  }
  const pushMock = vi.fn()
  return { storeMock, pushMock }
})

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('@/stores/discussionStore', () => ({
  useDiscussionStore: () => storeMock,
}))

const downloadFileMock = vi.fn()
vi.mock('@/utils/report', () => ({
  downloadFile: (...args: unknown[]) => downloadFileMock(...args),
}))

import DiscussionHistoryView from '@/views/DiscussionHistoryView.vue'

/** D6 历史项（COMPLETED：roundNo=6 表示结论已生成）。 */
function item(partial: Partial<DiscussionHistoryItem> = {}): DiscussionHistoryItem {
  return {
    sessionId: 55,
    moduleId: 'CH2-003',
    scenarioName: 'EOQ经济订货批量',
    status: 'COMPLETED',
    roundNo: 6,
    utteranceCount: 20,
    createdAt: '2026-08-13T10:00:00',
    finishedAt: '2026-08-13T10:12:00',
    ...partial,
  }
}

function history(items: DiscussionHistoryItem[]): DiscussionHistory {
  return { total: items.length, items }
}

async function mountView() {
  const wrapper = mount(DiscussionHistoryView)
  await flushPromises()
  return wrapper
}

describe('DiscussionHistoryView 历史列表', () => {
  beforeEach(() => {
    storeMock.history = null
    storeMock.historyLoading = false
    storeMock.error = ''
    vi.mocked(storeMock.fetchHistory).mockResolvedValue(null)
    vi.mocked(storeMock.exportMarkdown).mockResolvedValue({
      filename: 'discussion-CH2-003-55.md',
      content: '# 讨论记录',
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('挂载即拉取第一页 D6，并渲染列表字段（场景名/状态标签/轮次/发言数/时间）', async () => {
    storeMock.history = history([item()])
    const wrapper = await mountView()

    expect(storeMock.fetchHistory).toHaveBeenCalledWith(1, 20)
    expect(wrapper.findAll('[data-test="history-item"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="history-scenario"]').text()).toContain('EOQ经济订货批量')
    expect(wrapper.find('[data-test="history-status"]').text()).toBe('COMPLETED')
    expect(wrapper.text()).toContain('已完成 5 轮')
    expect(wrapper.text()).toContain('20 条发言')
    expect(wrapper.find('[data-test="history-time"]').text()).not.toBe('—')
  })

  it('RUNNING 项轮次文案为「n / 5 轮」，导出按钮禁用', async () => {
    storeMock.history = history([item({ status: 'RUNNING', roundNo: 2 })])
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('2 / 5 轮')
    expect(wrapper.find('[data-test="history-export"]').attributes('disabled')).toBeDefined()
  })

  it('COMPLETED 项导出：D7 → downloadFile 触发 .md 下载并提示成功', async () => {
    storeMock.history = history([item()])
    const successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)
    const wrapper = await mountView()

    await wrapper.find('[data-test="history-export"]').trigger('click')
    await flushPromises()

    expect(storeMock.exportMarkdown).toHaveBeenCalledWith(55)
    expect(downloadFileMock).toHaveBeenCalledWith('discussion-CH2-003-55.md', '# 讨论记录', 'text/markdown;charset=utf-8')
    expect(successSpy).toHaveBeenCalled()
    successSpy.mockRestore()
  })

  it('导出失败 → 错误提示', async () => {
    storeMock.history = history([item()])
    vi.mocked(storeMock.exportMarkdown).mockRejectedValue(new Error('导出失败'))
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never)
    const wrapper = await mountView()

    await wrapper.find('[data-test="history-export"]').trigger('click')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('导出失败')
    errorSpy.mockRestore()
  })

  it('点击回看 → 跳转只读讨论页（?sessionId=&readonly=1）', async () => {
    storeMock.history = history([item()])
    const wrapper = await mountView()

    await wrapper.find('[data-test="history-replay"]').trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/scenarios/CH2-003/discussion?sessionId=55&readonly=1')
  })

  it('total 超过页容量 → 显示分页，翻页拉取对应页', async () => {
    const items = Array.from({ length: 21 }, (_, i) => item({ sessionId: 100 + i }))
    storeMock.history = { total: 21, items }
    const wrapper = await mountView()

    const pagination = wrapper.findComponent(ElPagination) as unknown as VueWrapper
    expect(pagination.exists()).toBe(true)
    await pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(storeMock.fetchHistory).toHaveBeenCalledWith(2, 20)
  })

  it('空态：无记录时展示提示', async () => {
    storeMock.history = history([])
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="history-empty"]').exists()).toBe(true)
  })

  it('加载失败 → 错误提示 + 重新加载', async () => {
    storeMock.error = '网络异常'
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="history-error"]').exists()).toBe(true)
    await wrapper.find('[data-test="history-retry"]').trigger('click')
    expect(storeMock.fetchHistory).toHaveBeenCalledTimes(2)
  })
})
