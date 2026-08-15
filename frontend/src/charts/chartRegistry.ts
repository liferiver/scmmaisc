/**
 * 图表实例注册表（T037）：页面级 provide/inject 共享，
 * OutputChart 挂载时注册、卸载时注销；ExportButton 遍历导出 PNG。
 */
import type { ECharts } from 'echarts'

export interface ChartRegistryEntry {
  key: string
  chart: ECharts
}

export type ChartRegistry = ChartRegistryEntry[]

export const CHART_REGISTRY_KEY = 'chartRegistry'
