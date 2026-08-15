import type { EChartsOption } from 'echarts'
import type { OutputValue } from '@/types'

/**
 * chartFactory（T032，FR-007）：输出指标值 → ECharts option 的统一映射。
 * scalar → null（由 OutputChart 渲染数值卡片）；series/compare/dist/gauge/heatmap/topo →
 * 折线/柱状/仪表/热力/拓扑图。统一样式与调色板；展示层抽稀 ≤2000 点（R-09），
 * 原始全量数据由调用方保留（CSV 导出用全量，见 T037）。
 */

export const PRIMARY_COLOR = '#409EFF'
export const PALETTE = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#B37FEB', '#36CFC9']

/** series 型输出值形状（执行器约定）。 */
export interface SeriesShape {
  x: Array<number | string>
  series: { name: string; data: Array<number | null> }[]
}

/** compare/dist/gauge 型输出值形状：具名数值列表。 */
export type ItemsShape = { name: string; value: number }[]

/** heatmap 型输出值形状。 */
export interface HeatmapShape {
  rows: string[]
  columns: string[]
  data: number[][]
}

/** topo 型输出值形状。 */
export interface TopoShape {
  nodes: { id: string; name: string; type: string }[]
  edges: { source: string; target: string }[]
}

export function isSeries(v: unknown): v is SeriesShape {
  return !!v && typeof v === 'object' && Array.isArray((v as SeriesShape).x) && Array.isArray((v as SeriesShape).series)
}

export function isItems(v: unknown): v is ItemsShape {
  return Array.isArray(v) && v.every((i) => i && typeof i.name === 'string' && typeof i.value === 'number')
}

export function isHeatmap(v: unknown): v is HeatmapShape {
  return !!v && typeof v === 'object' && Array.isArray((v as HeatmapShape).rows) && Array.isArray((v as HeatmapShape).columns)
}

export function isTopo(v: unknown): v is TopoShape {
  return !!v && typeof v === 'object' && Array.isArray((v as TopoShape).nodes) && Array.isArray((v as TopoShape).edges)
}

/** 展示层抽稀：Largest-Triangle-Three-Buckets，保留端点与波峰波谷，点数 ≤ maxPoints（R-09）。 */
export function downsample(
  x: Array<number | string>,
  data: Array<number | null>,
  maxPoints = 2000,
): { x: Array<number | string>; data: Array<number | null> } {
  const n = data.length
  if (n <= maxPoints || maxPoints < 3) return { x: [...x], data: [...data] }
  const bucketSize = (n - 2) / (maxPoints - 2)
  const outX: Array<number | string> = [x[0]]
  const outData: Array<number | null> = [data[0]]
  let a = 0
  for (let i = 0; i < maxPoints - 2; i++) {
    const rangeStart = Math.floor(i * bucketSize) + 1
    const rangeEnd = Math.min(Math.floor((i + 1) * bucketSize) + 1, n - 1)
    // 下一桶的平均点（x 用下标近似，保证单调）
    const avgNextStart = Math.floor((i + 1) * bucketSize) + 1
    const avgNextEnd = Math.min(Math.floor((i + 2) * bucketSize) + 1, n)
    const avgX = (avgNextStart + avgNextEnd) / 2
    let avgY = 0
    for (let j = avgNextStart; j < avgNextEnd; j++) avgY += data[j] ?? 0
    avgY /= Math.max(1, avgNextEnd - avgNextStart)
    // 桶内选与 (a, 下桶均值) 构成最大三角形的点
    let bestArea = -1
    let bestIdx = rangeStart
    for (let j = rangeStart; j < rangeEnd; j++) {
      if (data[j] == null) continue
      const area = Math.abs((a - avgX) * ((data[j] ?? 0) - (data[a] ?? 0)) - (a - j) * (avgY - (data[a] ?? 0)))
      if (area > bestArea) {
        bestArea = area
        bestIdx = j
      }
    }
    a = bestIdx
    outX.push(x[a])
    outData.push(data[a])
  }
  outX.push(x[n - 1])
  outData.push(data[n - 1])
  return { x: outX, data: outData }
}

function fmt(v: number): string {
  if (!Number.isFinite(v)) return String(v)
  return Number.isInteger(v) ? String(v) : String(Math.round(v * 100) / 100)
}

function baseOption(): EChartsOption {
  return {
    backgroundColor: 'transparent',
    textStyle: { color: '#606266', fontSize: 12 },
    grid: { left: 16, right: 24, top: 40, bottom: 8, containLabel: true },
  }
}

function valueLabel(v: unknown, unit?: string): string {
  if (v == null) return '-'
  const s = typeof v === 'number' ? fmt(v) : String(v)
  return unit ? `${s} ${unit}` : s
}

/** series → 折线/面积图（多序列 + 抽稀 ≤2000 点）。 */
function seriesOption(v: SeriesShape, unit?: string): EChartsOption {
  return {
    ...baseOption(),
    legend: v.series.length > 1 ? { top: 0, type: 'scroll' } : undefined,
    tooltip: {
      trigger: 'axis',
      valueFormatter: (val) => valueLabel(val, unit),
    },
    xAxis: { type: 'category', data: v.x, boundaryGap: false },
    yAxis: { type: 'value' },
    series: v.series.map((s, i) => {
      const ds = downsample(v.x, s.data ?? [])
      const color = PALETTE[i % PALETTE.length]
      return {
        name: s.name,
        type: 'line',
        showSymbol: ds.data.length <= 60,
        symbolSize: 6,
        connectNulls: false,
        data: ds.data,
        lineStyle: { width: 2, color },
        itemStyle: { color },
      }
    }),
  }
}

/** compare/dist → 柱状图。 */
function barOption(items: ItemsShape, unit?: string): EChartsOption {
  return {
    ...baseOption(),
    tooltip: {
      trigger: 'axis',
      valueFormatter: (val) => valueLabel(val, unit),
    },
    xAxis: { type: 'category', data: items.map((i) => i.name) },
    yAxis: { type: 'value' },
    series: [
      {
        type: 'bar',
        barMaxWidth: 40,
        data: items.map((i) => i.value),
        itemStyle: { color: PRIMARY_COLOR, borderRadius: [4, 4, 0, 0] },
        label: {
          show: items.length <= 8,
          position: 'top',
          formatter: (p: any) => fmt(Number(p.value)),
        },
      },
    ],
  }
}

/** gauge → 仪表盘（执行器约定 0-100 百分比/评分）。 */
function gaugeOption(items: ItemsShape, unit?: string): EChartsOption {
  return {
    ...baseOption(),
    tooltip: {
      trigger: 'item',
      formatter: (p: any) => `${p.name}：${fmt(Number(p.value))}${unit ? unit : '%'}`,
    },
    series: [
      {
        type: 'gauge',
        min: 0,
        max: 100,
        radius: '88%',
        center: ['50%', '60%'],
        axisLine: { lineStyle: { width: 10, color: [[1, '#E9EEF5']] } },
        progress: { show: true, width: 10, itemStyle: { color: PRIMARY_COLOR } },
        axisTick: { show: false },
        splitLine: { length: 8, lineStyle: { color: '#C0C4CC' } },
        axisLabel: { distance: 16, fontSize: 10 },
        pointer: { length: '62%', width: 5 },
        detail: { formatter: (p: any) => `${fmt(Number(p.value))}${unit ? unit : '%'}`, fontSize: 14 },
        data: items.map((i) => ({ name: i.name, value: i.value })),
      },
    ],
  }
}

/** heatmap → 热力图（rows × columns，visualMap 渐变）。 */
function heatmapOption(v: HeatmapShape): EChartsOption {
  const cells: [number, number, number][] = []
  let min = Number.POSITIVE_INFINITY
  let max = Number.NEGATIVE_INFINITY
  v.data.forEach((row, r) => {
    row.forEach((cell, c) => {
      cells.push([c, r, cell])
      min = Math.min(min, cell)
      max = Math.max(max, cell)
    })
  })
  return {
    ...baseOption(),
    tooltip: {
      position: 'top',
      formatter: (p: any) => `${v.rows[p.value[1]]} × ${v.columns[p.value[0]]}：${fmt(p.value[2])}`,
    },
    grid: { left: 80, right: 20, top: 20, bottom: 60 },
    xAxis: { type: 'category', data: v.columns, splitArea: { show: true } },
    yAxis: { type: 'category', data: v.rows, splitArea: { show: true } },
    visualMap: {
      min,
      max,
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      inRange: { color: ['#E9EEF5', '#A7C8F2', PRIMARY_COLOR, '#0E5EAF'] },
    },
    series: [{ type: 'heatmap', data: cells, label: { show: true, fontSize: 10 } }],
  }
}

/** topo → 节点连线图（力导向布局，节点按类型着色）。 */
function topoOption(v: TopoShape): EChartsOption {
  const typeColor: Record<string, string> = {
    origin: '#67C23A',
    clearance: '#E6A23C',
    port: '#409EFF',
    transport: '#909399',
    delivery: '#F56C6C',
  }
  return {
    ...baseOption(),
    tooltip: {
      trigger: 'item',
      formatter: (p: any) => (p.dataType === 'edge' ? '' : `${p.name}（${p.data.type}）`),
    },
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        force: { repulsion: 220, edgeLength: 90 },
        label: { show: true, position: 'bottom', fontSize: 11 },
        data: v.nodes.map((n) => ({
          id: n.id,
          name: n.name,
          type: n.type,
          symbolSize: n.type === 'transport' ? 38 : 28,
          itemStyle: { color: typeColor[n.type] ?? PRIMARY_COLOR },
        })),
        links: v.edges.map((e) => ({ source: e.source, target: e.target })),
        lineStyle: { color: '#C0C4CC', width: 2 },
        emphasis: { focus: 'adjacency' },
      },
    ],
  }
}

/**
 * 输出指标 → ECharts option（FR-007）。scalar 返回 null，由 OutputChart 渲染数值卡片；
 * 未知/空类型同样返回 null 以保证容错。
 */
export function buildOption(output: OutputValue): EChartsOption | null {
  const v = output.value
  switch (output.type) {
    case 'series':
      return isSeries(v) ? seriesOption(v, output.unit) : null
    case 'compare':
    case 'dist':
      return isItems(v) ? barOption(v, output.unit) : null
    case 'gauge':
      return isItems(v) ? gaugeOption(v, output.unit) : null
    case 'heatmap':
      return isHeatmap(v) ? heatmapOption(v) : null
    case 'topo':
      return isTopo(v) ? topoOption(v) : null
    default:
      return null
  }
}
