import { describe, expect, it } from 'vitest'
import { buildOption, downsample, isItems, isSeries, isTopo, PALETTE, PRIMARY_COLOR } from '@/charts/chartFactory'
import type { OutputValue } from '@/types'

/**
 * chartFactory 单元测试（T032，FR-007 + R-09）：
 * 类型映射正确性（series/compare/dist/gauge/heatmap/topo/scalar）与展示层抽稀 ≤2000 点。
 */

function output(partial: Partial<OutputValue>): OutputValue {
  return { key: 'k', label: '指标', type: 'scalar', value: null, ...partial }
}

describe('downsample 抽稀（R-09）', () => {
  it('不超过 maxPoints 时原样返回', () => {
    const x = Array.from({ length: 10 }, (_, i) => i + 1)
    const data = x.map((v) => v * 2)
    const out = downsample(x, data, 2000)
    expect(out.x).toEqual(x)
    expect(out.data).toEqual(data)
  })

  it('超过 maxPoints 时抽稀至 ≤maxPoints 且保留首尾端点', () => {
    const n = 10000
    const x = Array.from({ length: n }, (_, i) => i + 1)
    const data = x.map((v) => Math.sin(v / 50) * 100 + v)
    const out = downsample(x, data, 2000)
    expect(out.data.length).toBeLessThanOrEqual(2000)
    expect(out.x[0]).toBe(1)
    expect(out.data[0]).toBe(data[0])
    expect(out.x[out.x.length - 1]).toBe(n)
    expect(out.data[out.data.length - 1]).toBe(data[n - 1])
  })

  it('包含 null 间隙时仍可抽稀且不抛异常', () => {
    const x = Array.from({ length: 5000 }, (_, i) => i + 1)
    const data: Array<number | null> = x.map((v) => (v % 100 === 0 ? null : v))
    const out = downsample(x, data, 1000)
    expect(out.data.length).toBeLessThanOrEqual(1000)
    expect(out.x.length).toBe(out.data.length)
  })
})

describe('buildOption 类型映射（FR-007）', () => {
  it('scalar 返回 null（由 OutputChart 渲染数值卡片）', () => {
    expect(buildOption(output({ type: 'scalar', value: 42, unit: '元' }))).toBeNull()
  })

  it('series → 折线图，多序列 + 抽稀', () => {
    const value = {
      x: [1, 2, 3, 4, 5],
      series: [
        { name: 'A', data: [1, 2, 3, 4, 5] },
        { name: 'B', data: [5, 4, 3, 2, 1] },
      ],
    }
    const option = buildOption(output({ type: 'series', value }))
    expect(option).not.toBeNull()
    expect((option!.series as unknown[]).length).toBe(2)
    expect((option!.series as Array<{ type: string; data: number[] }>)[0].type).toBe('line')
    expect(option!.legend).toBeDefined()
    expect((option!.xAxis as { data: number[] }).data).toEqual([1, 2, 3, 4, 5])
  })

  it('compare → 柱状图', () => {
    const option = buildOption(
      output({ type: 'compare', value: [{ name: '方案A', value: 100 }, { name: '方案B', value: 80 }], unit: '元' }),
    )
    expect(option).not.toBeNull()
    const series = option!.series as Array<{ type: string; data: number[] }>
    expect(series[0].type).toBe('bar')
    expect(series[0].data).toEqual([100, 80])
  })

  it('dist → 柱状图', () => {
    const option = buildOption(output({ type: 'dist', value: [{ name: '低', value: 3 }, { name: '高', value: 7 }] }))
    expect(option).not.toBeNull()
    expect((option!.series as Array<{ type: string }>)[0].type).toBe('bar')
  })

  it('gauge → 仪表盘（0-100）', () => {
    const option = buildOption(output({ type: 'gauge', value: [{ name: '完成率', value: 87 }], unit: '%' }))
    expect(option).not.toBeNull()
    const series = option!.series as Array<{ type: string; min: number; max: number; data: { name: string; value: number }[] }>
    expect(series[0].type).toBe('gauge')
    expect(series[0].min).toBe(0)
    expect(series[0].max).toBe(100)
    expect(series[0].data[0].value).toBe(87)
  })

  it('heatmap → 热力图（含 visualMap）', () => {
    const option = buildOption(
      output({
        type: 'heatmap',
        value: { rows: ['R1', 'R2'], columns: ['C1', 'C2'], data: [[1, 2], [3, 4]] },
      }),
    )
    expect(option).not.toBeNull()
    expect((option!.series as Array<{ type: string }>)[0].type).toBe('heatmap')
    expect(option!.visualMap).toBeDefined()
  })

  it('topo → 节点连线图（graph）', () => {
    const option = buildOption(
      output({
        type: 'topo',
        value: {
          nodes: [{ id: 'a', name: 'A', type: 'origin' }, { id: 'b', name: 'B', type: 'port' }],
          edges: [{ source: 'a', target: 'b' }],
        },
      }),
    )
    expect(option).not.toBeNull()
    const series = option!.series as Array<{ type: string; data: { id: string }[]; links: { source: string }[] }>
    expect(series[0].type).toBe('graph')
    expect(series[0].data.length).toBe(2)
    expect(series[0].links[0].source).toBe('a')
  })

  it('异常/空值容错返回 null', () => {
    expect(buildOption(output({ type: 'series', value: { bad: true } }))).toBeNull()
    expect(buildOption(output({ type: 'compare', value: 'nope' }))).toBeNull()
    expect(buildOption(output({ type: 'topo', value: null }))).toBeNull()
    expect(buildOption(output({ type: 'unknown-type' as never, value: 1 }))).toBeNull()
  })
})

describe('样式常量', () => {
  it('统一样式：主色与调色板已定义', () => {
    expect(PRIMARY_COLOR).toBe('#409EFF')
    expect(PALETTE.length).toBeGreaterThanOrEqual(6)
  })
})

describe('类型守卫', () => {
  it('isSeries / isItems / isTopo 正确识别形状', () => {
    expect(isSeries({ x: [1], series: [{ name: 'a', data: [1] }] })).toBe(true)
    expect(isSeries({ x: 1 })).toBe(false)
    expect(isItems([{ name: 'a', value: 1 }])).toBe(true)
    expect(isItems([{ name: 'a' }])).toBe(false)
    expect(isTopo({ nodes: [], edges: [] })).toBe(true)
    expect(isTopo({ nodes: [] })).toBe(false)
  })
})
