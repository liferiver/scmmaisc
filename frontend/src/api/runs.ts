import { del, get, post } from '@/api/http'
import type { RunCreateRequest, RunResult, RunStatus } from '@/types'

/** 运行接口封装（契约 C4–C7）。 */

/** C4：创建并启动运行（返回 202 + runId）。 */
export function createRun(body: RunCreateRequest): Promise<{ runId: number; status: string }> {
  return post<{ runId: number; status: string }>('/runs', body)
}

/** C5：运行状态与进度。 */
export function getRunStatus(runId: number, clientId: string): Promise<RunStatus> {
  return get<RunStatus>(`/runs/${runId}`, { clientId })
}

/** C6：运行结果（输出指标 + 全部步骤日志）。 */
export function getRunResult(runId: number, clientId: string): Promise<RunResult> {
  return get<RunResult>(`/runs/${runId}/result`, { clientId })
}

/** C7：取消运行。 */
export function cancelRun(runId: number, clientId: string): Promise<{ runId: number; status: string }> {
  return del<{ runId: number; status: string }>(`/runs/${runId}`, { clientId })
}

/** 浏览器端归属标识（无账号体系，R-07）：localStorage 生成一次后复用。 */
export function getClientId(): string {
  const KEY = 'client_id'
  let id = localStorage.getItem(KEY)
  if (!id) {
    id =
      typeof crypto !== 'undefined' && 'randomUUID' in crypto
        ? crypto.randomUUID()
        : `c-${Date.now()}-${Math.random().toString(36).slice(2)}`
    localStorage.setItem(KEY, id)
  }
  return id
}
