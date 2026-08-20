import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useDiscussionStore } from '@/stores/discussionStore'
import {
  abandonDiscussion,
  createDiscussion,
  exportDiscussionMarkdown,
  getDiscussionHistory,
  getDiscussionRecord,
  getDiscussionStatus,
} from '@/api/discussions'
import type { DiscussionRecord, DiscussionStatus } from '@/types'

/**
 * discussionStore 单元测试（T024）：创建 → 2s 轮询 D2 → 发言数变化拉取 D3 →
 * 终态（COMPLETED/FAILED/ABANDONED）停止；QUEUED 排队位置；D5 放弃（含 409 已终态）。
 * 模拟 '@/api/discussions'，用假定时器控制轮询节奏（对齐 runStore.spec 模式）。
 */
vi.mock('@/api/discussions', () => ({
  createDiscussion: vi.fn(),
  getDiscussionStatus: vi.fn(),
  getDiscussionRecord: vi.fn(),
  abandonDiscussion: vi.fn(),
  getDiscussionHistory: vi.fn(),
  exportDiscussionMarkdown: vi.fn(),
}))
vi.mock('@/api/runs', () => ({
  getClientId: vi.fn(() => 'test-client'),
}))

function status(partial: Partial<DiscussionStatus>): DiscussionStatus {
  return {
    sessionId: 55,
    runId: 9,
    scenarioId: 5,
    moduleId: 'CH2-003',
    scenarioName: 'EOQ经济订货批量',
    status: 'RUNNING',
    roundNo: 1,
    queuePosition: null,
    utteranceCount: 4,
    questionCount: 0,
    abandonable: true,
    ...partial,
  }
}

function record(): DiscussionRecord {
  return {
    sessionId: 55,
    status: 'RUNNING',
    roundNo: 1,
    conclusionNote: null,
    snapshot: { params: { annual_demand: 10000 }, seed: 42, outputs: [] },
    rounds: [
      {
        roundNo: 1,
        title: '现象解读',
        utterances: [{ id: 1, agentRole: 'JING', content: '确定性发言', replyQuestionId: null }],
      },
    ],
    questions: [],
    conclusion: null,
    moduleId: 'CH2-003',
    scenarioName: 'EOQ经济订货批量',
  }
}

describe('discussionStore 轮询', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.mocked(createDiscussion).mockResolvedValue({ sessionId: 55, status: 'QUEUED', queuePosition: 2 })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('start 创建讨论并 2s 轮询；QUEUED 展示排队位置且不拉取记录', async () => {
    vi.mocked(getDiscussionStatus).mockResolvedValue(
      status({ status: 'QUEUED', roundNo: 0, utteranceCount: 0, queuePosition: 2 }),
    )
    const store = useDiscussionStore()
    await store.start(9)
    await flushPromises()

    expect(createDiscussion).toHaveBeenCalledWith(9)
    expect(store.sessionId).toBe(55)
    expect(store.status).toBe('QUEUED')
    expect(store.queuePosition).toBe(2)
    expect(getDiscussionStatus).toHaveBeenCalledTimes(1)
    expect(getDiscussionRecord).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(getDiscussionStatus).toHaveBeenCalledTimes(2)
  })

  it('utteranceCount 变化 → 拉取 D3 刷新发言流；无变化不重复拉取', async () => {
    const statuses = [
      status({ utteranceCount: 4 }),
      status({ utteranceCount: 8, roundNo: 2 }),
      status({ utteranceCount: 8, roundNo: 2 }),
    ]
    vi.mocked(getDiscussionStatus).mockImplementation(() => Promise.resolve(statuses.shift()!))
    vi.mocked(getDiscussionRecord).mockResolvedValue(record())
    const store = useDiscussionStore()
    await store.start(9)
    await flushPromises()
    expect(getDiscussionRecord).toHaveBeenCalledTimes(1) // 4 ≠ 0

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(getDiscussionRecord).toHaveBeenCalledTimes(2) // 8 ≠ 4

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(getDiscussionRecord).toHaveBeenCalledTimes(2) // 8 = 8 不拉取
  })

  it('COMPLETED 停止轮询并拉取记录（结论展示）', async () => {
    const statuses = [
      status({ utteranceCount: 4 }),
      status({ status: 'COMPLETED', roundNo: 6, utteranceCount: 20, abandonable: false }),
    ]
    vi.mocked(getDiscussionStatus).mockImplementation(() => Promise.resolve(statuses.shift()!))
    vi.mocked(getDiscussionRecord).mockResolvedValue(record())
    const store = useDiscussionStore()
    await store.start(9)
    await flushPromises()

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(store.status).toBe('COMPLETED')
    expect(store.roundNo).toBe(6)
    expect(store.abandonable).toBe(false)
    expect(store.running).toBe(false)
    expect(getDiscussionRecord).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(5000)
    expect(getDiscussionStatus).toHaveBeenCalledTimes(2) // 终态后不再轮询
  })

  it('FAILED 停止轮询并设置错误提示', async () => {
    vi.mocked(getDiscussionStatus).mockResolvedValue(
      status({ status: 'FAILED', abandonable: false }),
    )
    const store = useDiscussionStore()
    await store.start(9)
    await flushPromises()

    expect(store.status).toBe('FAILED')
    expect(store.running).toBe(false)
    expect(store.error).toContain('重新发起')
    expect(getDiscussionRecord).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(5000)
    expect(getDiscussionStatus).toHaveBeenCalledTimes(1)
  })

  it('abandon 调用 D5：成功 → ABANDONED 并停止轮询', async () => {
    vi.mocked(getDiscussionStatus).mockResolvedValue(status({ utteranceCount: 4 }))
    vi.mocked(abandonDiscussion).mockResolvedValue({ sessionId: 55, status: 'ABANDONED' })
    const store = useDiscussionStore()
    await store.start(9)
    await flushPromises()

    await store.abandon()
    expect(abandonDiscussion).toHaveBeenCalledWith(55, 'test-client')
    expect(store.status).toBe('ABANDONED')
    expect(store.running).toBe(false)

    await vi.advanceTimersByTimeAsync(4000)
    expect(getDiscussionStatus).toHaveBeenCalledTimes(1)
  })

  it('abandon 409（服务端已终态）→ 停止轮询不报错', async () => {
    vi.mocked(getDiscussionStatus).mockResolvedValue(status({ utteranceCount: 4 }))
    vi.mocked(abandonDiscussion).mockRejectedValue(
      Object.assign(new Error('讨论已处于终态'), { httpStatus: 409, code: 40901 }),
    )
    const store = useDiscussionStore()
    await store.start(9)
    await flushPromises()

    await store.abandon()
    expect(store.running).toBe(false)
    expect(store.error).toBe('')
  })
})

describe('discussionStore 历史与导出（US4/T042）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('fetchHistory 调用 D6 并写入 history；带 clientId/页码/条数', async () => {
    const resp = {
      total: 1,
      items: [
        {
          sessionId: 55,
          moduleId: 'CH2-003',
          scenarioName: 'EOQ经济订货批量',
          status: 'COMPLETED' as const,
          roundNo: 6,
          utteranceCount: 20,
          createdAt: '2026-08-13T10:00:00',
          finishedAt: '2026-08-13T10:12:00',
        },
      ],
    }
    vi.mocked(getDiscussionHistory).mockResolvedValue(resp)
    const store = useDiscussionStore()

    const result = await store.fetchHistory(2, 20, 5)

    expect(getDiscussionHistory).toHaveBeenCalledWith({ clientId: 'test-client', page: 2, size: 20, scenarioId: 5 })
    expect(store.history).toEqual(resp)
    expect(store.historyLoading).toBe(false)
    expect(result).toEqual(resp)
  })

  it('fetchHistory 失败 → 置 error 并返回 null，不抛异常', async () => {
    vi.mocked(getDiscussionHistory).mockRejectedValue(new Error('服务不可用'))
    const store = useDiscussionStore()

    const result = await store.fetchHistory()

    expect(result).toBeNull()
    expect(store.error).toContain('服务不可用')
    expect(store.historyLoading).toBe(false)
  })

  it('exportMarkdown 调用 D7（sessionId + clientId），返回文件名与正文', async () => {
    vi.mocked(exportDiscussionMarkdown).mockResolvedValue({
      filename: 'discussion-CH2-003-55.md',
      content: '# 讨论记录',
    })
    const store = useDiscussionStore()

    const result = await store.exportMarkdown(55)

    expect(exportDiscussionMarkdown).toHaveBeenCalledWith(55, 'test-client')
    expect(result).toEqual({ filename: 'discussion-CH2-003-55.md', content: '# 讨论记录' })
  })
})
