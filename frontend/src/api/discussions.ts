import http, { get, post } from '@/api/http'
import { getClientId } from './runs'
import type {
  AbandonResult,
  CreateDiscussionResult,
  DiscussionHistory,
  DiscussionRecord,
  DiscussionStatus,
  SubmitQuestionResult,
} from '@/types'

/** 多智能体讨论接口封装（契约 D1-D8，镜像 runs.ts 风格：getClientId + ApiResult 解包）。 */

/** D1：创建讨论（202 + sessionId + 排队位置）。 */
export function createDiscussion(runId: number): Promise<CreateDiscussionResult> {
  return post<CreateDiscussionResult>('/discussions', { runId, clientId: getClientId() })
}

/** D2：讨论状态与进度（前端 2s 轮询）。 */
export function getDiscussionStatus(sessionId: number, clientId: string): Promise<DiscussionStatus> {
  return get<DiscussionStatus>(`/discussions/${sessionId}`, { clientId })
}

/** D3：完整讨论记录（回看 / 发言流 / 结论）。 */
export function getDiscussionRecord(sessionId: number, clientId: string): Promise<DiscussionRecord> {
  return get<DiscussionRecord>(`/discussions/${sessionId}/record`, { clientId })
}

/** D4：提交学生插话（201 + {questionId, roundNo, truncated}；空白 400、终态 409）。 */
export function submitQuestion(
  sessionId: number,
  clientId: string,
  content: string,
): Promise<SubmitQuestionResult> {
  return post<SubmitQuestionResult>(`/discussions/${sessionId}/questions`, { content }, { clientId })
}

/** D5：放弃讨论（非终态可放弃，已生成发言保留）。 */
export function abandonDiscussion(sessionId: number, clientId: string): Promise<AbandonResult> {
  return post<AbandonResult>(`/discussions/${sessionId}/abandon`, undefined, { clientId })
}

/** D6：历史讨论列表（clientId 过滤 + 可选 scenarioId + 时间倒序 + 分页默认 20 上限 50）。 */
export function getDiscussionHistory(params: {
  clientId: string
  scenarioId?: number
  page?: number
  size?: number
}): Promise<DiscussionHistory> {
  return get<DiscussionHistory>('/discussions/history', params)
}

/**
 * D7：导出实验报告附录 Markdown（ResponseEntity 原文 text/markdown 附件，非 ApiResult 包裹）。
 * 解析 Content-Disposition 取附件文件名，响应体即 Markdown 正文。
 */
export async function exportDiscussionMarkdown(
  sessionId: number,
  clientId: string,
): Promise<{ filename: string; content: string }> {
  const resp = await http.get(`/discussions/${sessionId}/export`, {
    params: { clientId },
    responseType: 'text',
  })
  const disposition = String(resp.headers['content-disposition'] ?? '')
  const m = /filename="?([^";]+)"?/i.exec(disposition)
  return { filename: m?.[1] ?? `discussion-${sessionId}.md`, content: resp.data as string }
}
