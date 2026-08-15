/**
 * 前端类型定义（镜像 contracts/api.md 与 data-model.md 结构）。
 */

/** 章节（C1）。 */
export interface Chapter {
  id: number
  code: string
  name: string
  sortNo: number
  scenarioCount?: number
}

/** 输入参数定义（data-model.md params 元素）。 */
export interface Parameter {
  key: string
  label: string
  type: 'int' | 'float' | 'enum' | 'dist' | 'bool' | 'matrix' | 'timeseries' | 'func'
  unit?: string
  min?: number
  max?: number
  step?: number
  default?: unknown
  options?: { label: string; value: string | number }[]
  /** dist 类型：子参数组（如均值/标准差）。 */
  fields?: Parameter[]
  description?: string
}

/** 输出指标定义（data-model.md outputs 元素）。 */
export interface OutputIndicator {
  key: string
  label: string
  type: 'scalar' | 'series' | 'compare' | 'dist' | 'topo' | 'heatmap' | 'gauge'
  unit?: string
}

/** 约束表达式（FR-005，expression 仅展示与提示，R-11）。 */
export interface Constraint {
  name: string
  expression: string
  message: string
}

/** 场景概要（C2）。 */
export interface ScenarioSummary {
  id: number
  chapterId: number
  moduleId: string
  name: string
  difficulty: 'intro' | 'basic' | 'advanced'
  classHours?: number
  isRolePlay: boolean
  deps: string[]
}

/** 场景详情（C3）。 */
export interface ScenarioDetail extends ScenarioSummary {
  chapterId: number
  engineKey: string
  concept: string
  description: string
  params: Parameter[]
  outputs: OutputIndicator[]
  constraints: Constraint[]
}

/** 创建运行请求（C4）。 */
export interface RunCreateRequest {
  scenarioId: number
  clientId: string
  params: Record<string, unknown>
  seed: number
}

/** 运行状态（C5）。 */
export type RunStatusValue = 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED'

export interface RunStatus {
  runId: number
  scenarioId: number
  status: RunStatusValue
  stepTotal?: number
  stepCount: number
  progress: number
  errorMessage?: string
}

/** 步骤事件（C6，对应 simulation_log）。 */
export interface StepEvent {
  stepNo: number
  eventType: 'STEP' | 'INFO' | 'WARN' | 'ERROR'
  message: string
  data: Record<string, unknown>
}

/** 输出指标值。 */
export interface OutputValue {
  key: string
  label: string
  type: string
  value: unknown
  unit?: string
}

/** 运行结果（C6）。 */
export interface RunResult {
  runId: number
  status: RunStatusValue
  params: Record<string, unknown>
  seed: number
  durationMs: number
  outputs: OutputValue[]
  steps: StepEvent[]
}

/** 统一响应包裹。 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** 已保存方案（localStorage param_sets，data-model.md 客户端模型）。 */
export interface ParamSet {
  id: string
  scenarioId: number
  scenarioName: string
  name: string
  params: Record<string, unknown>
  seed: number
  result: RunResult
  savedAt: string
}
