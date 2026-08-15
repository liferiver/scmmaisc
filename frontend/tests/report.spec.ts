import { describe, expect, it } from 'vitest'
import { buildCompareCsv, buildReportCsv, compareRows, formatValue } from '@/utils/report'
import type { OutputValue, ParamSet, RunResult } from '@/types'

/**
 * 报告导出工具单元测试（T035，FR-012 + R-09）：
 * formatValue 展示规则、单次运行 CSV（BOM/参数快照/全量 series/转义）、多方案对比矩阵。
 */
function result(partial: Partial<RunResult>): RunResult {
  return { runId: 1, status: 'COMPLETED', params: {}, seed: 42, durationMs: 1234, outputs: [], steps: [], ...partial }
}

function output(partial: Partial<OutputValue>): OutputValue {
  return { key: 'k', label: '指标', type: 'scalar', value: 1, ...partial }
}

function set(name: string, partial: Partial<ParamSet> = {}): ParamSet {
  return {
    id: name,
    scenarioId: 5,
    scenarioName: 'EOQ 经济订货批量',
    name,
    params: { annual_demand: 10000 },
    seed: 42,
    result: result({ runId: 1 }),
    savedAt: '2026-08-13T10:00:00.000Z',
    ...partial,
  }
}

describe('formatValue 展示规则', () => {
  it('空值显示 -', () => {
    expect(formatValue(null)).toBe('-')
    expect(formatValue(undefined)).toBe('-')
    expect(formatValue('')).toBe('-')
  })

  it('整数直显、小数保留两位', () => {
    expect(formatValue(1000)).toBe('1000')
    expect(formatValue(42.5)).toBe('42.5')
    expect(formatValue(42.555)).toBe('42.56')
  })

  it('非数值原样字符串化', () => {
    expect(formatValue('road')).toBe('road')
    expect(formatValue(false)).toBe('false')
  })
})

describe('buildReportCsv 单次运行报告（R-09）', () => {
  it('含 BOM、运行信息与参数快照，逗号/引号正确转义', () => {
    const csv = buildReportCsv(result({ params: { annual_demand: 10000, note: 'a,b"c' } }), 'EOQ 经济订货批量')
    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv).toContain('场景,EOQ 经济订货批量')
    expect(csv).toContain('运行ID,1')
    expect(csv).toContain('--- 参数快照 ---')
    expect(csv).toContain('annual_demand,10000')
    expect(csv).toContain('"a,b""c"')
    expect(csv).toContain('--- 输出指标 ---')
  })

  it('series 输出导出全量数据（不做展示层抽稀，R-09）', () => {
    const n = 3000
    const x = Array.from({ length: n }, (_, i) => i + 1)
    const data = x.map((v) => v * 2)
    const csv = buildReportCsv(
      result({ outputs: [output({ key: 'curve', label: '成本曲线', type: 'series', value: { x, series: [{ name: '总成本', data }] } })] }),
      '场景',
    )
    expect(csv).toContain('X,总成本')
    expect(csv).toContain('1,2')
    expect(csv).toContain('3000,6000')
  })

  it('scalar / compare / heatmap / topo 数据块结构正确', () => {
    const csv = buildReportCsv(
      result({
        outputs: [
          output({ key: 'q', label: '最优订货量', type: 'scalar', value: 1000, unit: '件' }),
          output({ key: 'c', label: '方案对比', type: 'compare', value: [{ name: 'A', value: 1 }, { name: 'B', value: 2 }] }),
          output({ key: 'h', label: '热力', type: 'heatmap', value: { rows: ['R1'], columns: ['C1'], data: [[5]] } }),
          output({
            key: 't',
            label: '拓扑',
            type: 'topo',
            value: { nodes: [{ id: 'n1', name: 'N1', type: 'port' }], edges: [{ source: 'n1', target: 'n2' }] },
          }),
        ],
      }),
      '场景',
    )
    expect(csv).toContain('[最优订货量（件）]')
    expect(csv).toContain('指标,值,单位')
    expect(csv).toContain('最优订货量,1000,件')
    expect(csv).toContain('A,1')
    expect(csv).toContain('R1,C1,5')
    expect(csv).toContain('节点ID,名称,类型')
    expect(csv).toContain('n1,N1,port')
    expect(csv).toContain('边,source,target')
  })
})

describe('compareRows / buildCompareCsv 多方案对比（FR-011）', () => {
  it('同名指标按方案顺序取值，缺失方案为 null', () => {
    const a = set('A', {
      result: result({ outputs: [output({ key: 'q', label: '最优订货量', value: 1000, unit: '件' })] }),
    })
    const b = set('B', { result: result({ outputs: [] }) })
    const rows = compareRows([a, b])
    expect(rows).toHaveLength(1)
    expect(rows[0].key).toBe('q')
    expect(rows[0].values).toEqual(['1000', null])
  })

  it('结构化输出在对比表中显示紧凑摘要（避免 [object Object]）', () => {
    const a = set('A', {
      result: result({
        outputs: [
          output({ key: 'curve', label: '成本曲线', type: 'series', value: { x: [1, 2, 3], series: [] } }),
          output({ key: 't', label: '拓扑', type: 'topo', value: { nodes: [{ id: 'n1' }], edges: [{ source: 'n1', target: 'n2' }] } }),
          output({ key: 'c', label: '对比', type: 'compare', value: [{ name: 'A', value: 1 }, { name: 'B', value: 2 }] }),
        ],
      }),
    })
    const b = set('B', { result: result({ outputs: [] }) })
    const rows = compareRows([a, b])
    const byKey = Object.fromEntries(rows.map((r) => [r.key, r]))
    expect(byKey.curve.values[0]).toBe('曲线（3 点）')
    expect(byKey.t.values[0]).toBe('拓扑（1 节点 / 1 边）')
    expect(byKey.c.values[0]).toBe('A=1，B=2')
    expect(byKey.curve.values[1]).toBeNull()
  })

  it('对比 CSV：参数快照矩阵 + 同名指标矩阵', () => {
    const a = set('A', {
      params: { annual_demand: 10000 },
      result: result({ outputs: [output({ key: 'q', label: '最优订货量', value: 1000 })] }),
    })
    const b = set('B', {
      params: { annual_demand: 8000 },
      result: result({ outputs: [output({ key: 'q', label: '最优订货量', value: 800 })] }),
    })
    const csv = buildCompareCsv([a, b])
    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv).toContain('--- 参数快照对比 ---')
    expect(csv).toContain('参数key,A,B')
    expect(csv).toContain('annual_demand,10000,8000')
    expect(csv).toContain('--- 同名输出指标对比 ---')
    expect(csv).toContain('指标,A,B')
    expect(csv).toContain('最优订货量,1000,800')
  })
})
