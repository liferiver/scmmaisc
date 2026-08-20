import { defineStore } from 'pinia'
import {
  abandonDiscussion,
  createDiscussion,
  exportDiscussionMarkdown,
  getDiscussionHistory,
  getDiscussionRecord,
  getDiscussionStatus,
  submitQuestion as apiSubmitQuestion,
} from '@/api/discussions'
import { getClientId } from '@/api/runs'
import type { ApiError } from '@/api/http'
import type { DiscussionHistory, DiscussionRecord, DiscussionStatusValue } from '@/types'

/**
 * discussionStore（T021）：讨论生命周期状态机 —— D1 创建 → D2 轮询（2s）→
 * utteranceCount/questionCount 变化时拉取 D3 增量刷新发言流 → 终态停止
 * （COMPLETED 展示结论 / QUEUED 展示排队位置 / FAILED、ABANDONED 停止并提示）；
 * D5 放弃；D4 提交学生插话（US3，T035）；D6 历史列表 + D7 Markdown 导出（US4，T042）。
 * 对齐 runStore 1s 轮询模式（宪法 IV 不阻塞）。
 */
export const useDiscussionStore = defineStore('discussion', {
  state: () => ({
    sessionId: null as number | null,
    runId: null as number | null,
    status: null as DiscussionStatusValue | null,
    roundNo: 0,
    queuePosition: null as number | null,
    utteranceCount: 0,
    questionCount: 0,
    abandonable: false,
    record: null as DiscussionRecord | null,
    running: false,
    /** 轮询定时器句柄（2s）。 */
    timer: null as ReturnType<typeof setInterval> | null,
    error: '',
    /** D6 历史列表（US4，T042）。 */
    history: null as DiscussionHistory | null,
    historyLoading: false,
  }),

  actions: {
    /** D1：创建讨论，成功后启动 2s 轮询。 */
    async start(runId: number) {
      this.reset()
      this.runId = runId
      this.running = true
      try {
        const resp = await createDiscussion(runId)
        this.sessionId = resp.sessionId
        this.status = resp.status
        this.queuePosition = resp.queuePosition
        this.startPolling(resp.sessionId)
      } catch (err) {
        this.running = false
        this.error = err instanceof Error ? err.message : '发起讨论失败'
      }
    },

    /** 启动 2s 轮询：先立即查一次，再定时查询。 */
    startPolling(sessionId: number) {
      this.clearPolling()
      this.poll(sessionId)
      this.timer = setInterval(() => this.poll(sessionId), 2000)
    },

    /** D2：单次轮询；发言/提问数变化 → D3 增量刷新；终态停止轮询。 */
    async poll(sessionId: number) {
      if (!this.running) return
      try {
        const s = await getDiscussionStatus(sessionId, getClientId())
        const recordChanged =
          s.utteranceCount !== this.utteranceCount || s.questionCount !== this.questionCount
        this.status = s.status
        this.roundNo = s.roundNo
        this.queuePosition = s.queuePosition
        this.utteranceCount = s.utteranceCount
        this.questionCount = s.questionCount
        this.abandonable = s.abandonable
        if (s.status === 'COMPLETED') {
          this.stopPolling()
          await this.fetchRecord(sessionId)
        } else if (s.status === 'FAILED' || s.status === 'ABANDONED') {
          this.stopPolling()
          if (s.status === 'FAILED') this.error = '讨论执行失败，请重新发起'
        } else if (recordChanged) {
          // 轮询约定：utteranceCount 变化 → 拉取 D3 刷新发言流
          await this.fetchRecord(sessionId)
        }
      } catch (err) {
        // 单次轮询失败不中断，等待下一轮重试
        this.error = err instanceof Error ? err.message : '查询讨论状态失败'
      }
    },

    /** D3：拉取完整讨论记录（发言流/结论）。 */
    async fetchRecord(sessionId: number) {
      try {
        this.record = await getDiscussionRecord(sessionId, getClientId())
      } catch (err) {
        this.error = err instanceof Error ? err.message : '拉取讨论记录失败'
      }
    },

    /** D4：提交学生插话（201 + {questionId, roundNo, truncated}；错误由视图提示，下次轮询自动刷新提问数）。 */
    async submitQuestion(content: string) {
      if (this.sessionId == null) return null
      return apiSubmitQuestion(this.sessionId, getClientId(), content)
    },

    /** D5：放弃讨论（确认由视图层完成）。 */
    async abandon() {
      if (this.sessionId == null || !this.abandonable) return
      try {
        const resp = await abandonDiscussion(this.sessionId, getClientId())
        this.status = resp.status
        this.abandonable = false
        this.stopPolling()
      } catch (err) {
        const e = err as ApiError
        // 已终态（409）则直接停止；其余错误展示提示
        if (e.httpStatus === 409) this.stopPolling()
        else this.error = e.message || '放弃失败'
      }
    },

    /** D6：历史列表（分页，clientId 服务端过滤；失败置 error 并返回 null）。 */
    async fetchHistory(page = 1, size = 20, scenarioId?: number): Promise<DiscussionHistory | null> {
      this.historyLoading = true
      try {
        this.history = await getDiscussionHistory({ clientId: getClientId(), page, size, scenarioId })
        return this.history
      } catch (err) {
        this.error = err instanceof Error ? err.message : '拉取讨论历史失败'
        return null
      } finally {
        this.historyLoading = false
      }
    },

    /** D7：导出实验报告附录（Markdown 正文 + 附件文件名；下载触发由视图层复用 downloadFile）。 */
    async exportMarkdown(sessionId: number) {
      return exportDiscussionMarkdown(sessionId, getClientId())
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
      this.sessionId = null
      this.runId = null
      this.status = null
      this.roundNo = 0
      this.queuePosition = null
      this.utteranceCount = 0
      this.questionCount = 0
      this.abandonable = false
      this.record = null
      this.error = ''
      this.history = null
      this.historyLoading = false
    },
  },
})
