import { get } from './http'
import type { Chapter, ScenarioDetail, ScenarioSummary } from '@/types'

/**
 * 场景目录 API（对齐 contracts/api.md C1/C2/C3，T017）。
 */

/** C1: 章节列表（含各章场景数）。 */
export function getChapters(): Promise<Chapter[]> {
  return get<Chapter[]>('/chapters')
}

/** C2: 场景列表；chapterId 为空时返回全部。 */
export function getScenarios(chapterId?: number): Promise<ScenarioSummary[]> {
  return get<ScenarioSummary[]>('/scenarios', chapterId === undefined ? undefined : { chapterId })
}

/** C3: 场景完整定义。 */
export function getScenarioDetail(moduleId: string): Promise<ScenarioDetail> {
  return get<ScenarioDetail>(`/scenarios/${moduleId}`)
}
