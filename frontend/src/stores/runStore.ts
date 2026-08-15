import { defineStore } from 'pinia'
import { cancelRun, createRun, getClientId, getRunResult, getRunStatus } from '@/api/runs'
import type { ApiError } from '@/api/http'
import type { RunResult, RunStatusValue } from '@/types'

/**
 * runStore（T022/T034）：运行生命周期状态机 —— C4 提交 → C5 轮询（1s）→
 * 终态停止（COMPLETED 拉取 C6 结果；CANCELLED/FAILED 停止并展示错误）；C7 取消不中断轮询。
 */
export const useRunStore = defineStore('run', {
  state: () => ({
    runId: null as number | null,
    status: null as RunStatusValue | null,
    stepTotal: 0,
    stepCount: 0,
    progress: 0,
    result: null as RunResult | null,
    running: false,
    /** 轮询定时器句柄。 */
    timer: null as ReturnType<typeof setInterval> | null,
    error: '',
  }),

  actions: {
    /** C4：提交参数创建运行，成功后启动轮询。 */
    async submit(scenarioId: number, params: Record<string, unknown>, seed: number) {
      this.reset()
      this.running = true
      try {
        const resp = await createRun({ scenarioId, clientId: getClientId(), params, seed })
        this.runId = resp.runId
        this.startPolling(resp.runId)
      } catch (err) {
        this.running = false
        this.error = err instanceof Error ? err.message : '提交失败'
      }
    },

    /** 启动 1s 轮询：先立即查一次，再定时查询。 */
    startPolling(runId: number) {
      this.clearPolling()
      this.poll(runId)
      this.timer = setInterval(() => this.poll(runId), 1000)
    },

    /** C5：单次轮询；终态停止，COMPLETED 追加拉取结果。 */
    async poll(runId: number) {
      if (!this.running) return
      try {
        const s = await getRunStatus(runId, getClientId())
        this.status = s.status
        this.stepTotal = s.stepTotal ?? 0
        this.stepCount = s.stepCount
        this.progress = s.progress
        this.error = s.errorMessage ?? ''
        if (s.status === 'COMPLETED') {
          this.stopPolling()
          await this.fetchResult(runId)
        } else if (s.status === 'CANCELLED' || s.status === 'FAILED') {
          this.stopPolling()
        }
      } catch (err) {
        // 单次轮询失败不中断，等待下一轮重试
        this.error = err instanceof Error ? err.message : '查询运行状态失败'
      }
    },

    /** C6：拉取运行结果。 */
    async fetchResult(runId: number) {
      try {
        this.result = await getRunResult(runId, getClientId())
      } catch (err) {
        this.error = err instanceof Error ? err.message : '拉取结果失败'
      }
    },

    /** C7：取消运行（轮询保持，等待服务端终态）。 */
    async cancel() {
      if (this.runId == null || !this.running) return
      try {
        const resp = await cancelRun(this.runId, getClientId())
        if (resp.status === 'CANCELLED') this.status = resp.status as RunStatusValue
      } catch (err) {
        const e = err as ApiError
        // 已终态则直接停止；其余错误展示提示
        if (e.httpStatus === 409) this.stopPolling()
        else this.error = e.message || '取消失败'
      }
    },

    stopPolling() {
      this.clearPolling()
      this.running = false
    },

    clearPolling() {
      if (this.timer) {
        clearInterval(this.timer)
        this.timer = null
      }
    },

    reset() {
      this.stopPolling()
      this.runId = null
      this.status = null
      this.stepTotal = 0
      this.stepCount = 0
      this.progress = 0
      this.result = null
      this.error = ''
    },
  },
})
