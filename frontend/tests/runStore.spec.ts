import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useRunStore } from '@/stores/runStore'
import { cancelRun, createRun, getRunResult, getRunStatus } from '@/api/runs'
import type { RunResult, RunStatus } from '@/types'

/**
 * runStore 单元测试（T022）：轮询终止条件 —— COMPLETED / CANCELLED / FAILED 均停止轮询。
 * 模拟 '@/api/runs'，用假定时器控制轮询节奏。
 */
vi.mock('@/api/runs', () => ({
  createRun: vi.fn(),
  getRunStatus: vi.fn(),
  getRunResult: vi.fn(),
  cancelRun: vi.fn(),
  getClientId: vi.fn(() => 'test-client'),
}))

function status(partial: Partial<RunStatus>): RunStatus {
  return { runId: 1, scenarioId: 5, status: 'RUNNING', stepCount: 0, progress: 0, ...partial }
}

function result(): RunResult {
  return {
    runId: 1,
    status: 'COMPLETED',
    params: {},
    seed: 42,
    durationMs: 10,
    outputs: [],
    steps: [],
  }
}

describe('runStore 轮询', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.mocked(createRun).mockResolvedValue({ runId: 1, status: 'RUNNING' })
    vi.mocked(getRunResult).mockResolvedValue(result())
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('COMPLETED 终止轮询并拉取结果', async () => {
    const statuses = [
      status({ stepCount: 2, progress: 0.5 }),
      status({ status: 'COMPLETED', stepCount: 5, stepTotal: 5, progress: 1 }),
    ]
    vi.mocked(getRunStatus).mockImplementation(() => Promise.resolve(statuses.shift()!))

    const store = useRunStore()
    await store.submit(5, { annual_demand: 10000 }, 42)
    await flushPromises()

    expect(store.status).toBe('RUNNING')
    expect(store.progress).toBe(0.5)
    expect(getRunStatus).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(getRunStatus).toHaveBeenCalledTimes(2)
    expect(store.status).toBe('COMPLETED')
    expect(store.progress).toBe(1)
    expect(getRunResult).toHaveBeenCalledTimes(1)
    expect(store.result).not.toBeNull()
    expect(store.running).toBe(false)

    // 终态后不再轮询
    await vi.advanceTimersByTimeAsync(5000)
    expect(getRunStatus).toHaveBeenCalledTimes(2)
  })

  it('CANCELLED 终止轮询且不拉取结果', async () => {
    vi.mocked(getRunStatus).mockResolvedValue(
      status({ status: 'CANCELLED', stepCount: 3, stepTotal: 200, progress: 1 }),
    )
    const store = useRunStore()
    await store.submit(5, {}, 42)
    await flushPromises()

    expect(store.status).toBe('CANCELLED')
    expect(store.running).toBe(false)
    expect(getRunResult).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(5000)
    expect(getRunStatus).toHaveBeenCalledTimes(1)
  })

  it('FAILED 终止轮询并展示错误信息', async () => {
    vi.mocked(getRunStatus).mockResolvedValue(
      status({ status: 'FAILED', errorMessage: '引擎内部错误', progress: 1 }),
    )
    const store = useRunStore()
    await store.submit(5, {}, 42)
    await flushPromises()

    expect(store.status).toBe('FAILED')
    expect(store.error).toBe('引擎内部错误')
    expect(store.running).toBe(false)
    expect(getRunResult).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(5000)
    expect(getRunStatus).toHaveBeenCalledTimes(1)
  })

  it('cancel 调用 C7 并保持轮询直至终态', async () => {
    const statuses = [
      status({ stepCount: 1 }),
      status({ status: 'CANCELLED', stepCount: 4, progress: 1 }),
    ]
    vi.mocked(getRunStatus).mockImplementation(() => Promise.resolve(statuses.shift()!))
    vi.mocked(cancelRun).mockResolvedValue({ runId: 1, status: 'CANCELLED' })

    const store = useRunStore()
    await store.submit(5, {}, 42)
    await flushPromises()
    await store.cancel()
    expect(cancelRun).toHaveBeenCalledWith(1, 'test-client')

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(store.status).toBe('CANCELLED')
    expect(store.running).toBe(false)
  })
})
