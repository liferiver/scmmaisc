/**
 * 报告导出工具（T037，FR-012）：结果 → CSV 报告（参数快照 + 全量指标数据，R-09）、
 * 多方案对比矩阵 CSV、文件下载。ECharts PNG 由 ExportButton 配合图表注册表完成。
 */
import type { OutputValue, ParamSet, RunResult } from '@/types'

/** 数值/标量展示格式化（与 OutputChart 数值卡片一致）。 */
export function formatValue(v: unknown): string {
  if (v === null || v === undefined || v === '') return '-'
  if (typeof v === 'number') {
    return Number.isInteger(v) ? String(v) : String(Math.round(v * 100) / 100)
  }
  return String(v)
}

/** CSV 单元格转义（逗号/引号/换行包裹引号）。 */
function csvCell(v: unknown): string {
  if (v === null || v === undefined) return ''
  const s = typeof v === 'object' ? JSON.stringify(v) : String(v)
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}

function csvRow(...vals: unknown[]): string {
  return vals.map(csvCell).join(',') + '\r\n'
}

/** compare/dist/gauge 型输出 → 具名数值列表。 */
function itemsOf(o: OutputValue): { name: string; value: number }[] {
  if (!Array.isArray(o.value)) return []
  return (o.value as { name?: string; value?: number }[]).map((it) => ({
    name: String(it.name ?? ''),
    value: Number(it.value ?? 0),
  }))
}

/**
 * 单次运行结果 → 报告 CSV（含 BOM，Excel 中文兼容）。
 * 结构：运行信息 + 参数快照 + 各输出指标数据块（series 为后端全量数据，不做抽稀）。
 */
export function buildReportCsv(result: RunResult, scenarioName: string): string {
  const out: string[] = []
  out.push(csvRow('场景', scenarioName))
  out.push(csvRow('运行ID', result.runId))
  out.push(csvRow('随机种子', result.seed))
  out.push(csvRow('耗时(秒)', (result.durationMs / 1000).toFixed(3)))
  out.push(csvRow('导出时间', new Date().toISOString()))
  out.push(csvRow(''))
  out.push(csvRow('--- 参数快照 ---'))
  out.push(csvRow('参数key', '值'))
  for (const [k, v] of Object.entries(result.params ?? {})) {
    out.push(csvRow(k, v))
  }
  out.push(csvRow(''))
  out.push(csvRow('--- 输出指标 ---'))
  for (const o of result.outputs ?? []) {
    const unit = o.unit ? `（${o.unit}）` : ''
    out.push(csvRow(`[${o.label}${unit}]`))
    if (o.type === 'scalar') {
      out.push(csvRow('指标', '值', '单位'))
      out.push(csvRow(o.label, o.value, o.unit ?? ''))
    } else if (o.type === 'compare' || o.type === 'dist' || o.type === 'gauge') {
      out.push(csvRow('名称', '值'))
      for (const it of itemsOf(o)) out.push(csvRow(it.name, it.value))
    } else if (o.type === 'series') {
      const v = o.value as { x?: unknown[]; series?: { name?: string; data?: unknown[] }[] } | undefined
      const x = v?.x ?? []
      const series = v?.series ?? []
      out.push(csvRow('X', ...series.map((s) => s.name ?? '')))
      const maxLen = Math.max(x.length, ...series.map((s) => s.data?.length ?? 0))
      for (let i = 0; i < maxLen; i++) {
        out.push(csvRow(x[i], ...series.map((s) => s.data?.[i])))
      }
    } else if (o.type === 'heatmap') {
      const v = o.value as { rows?: string[]; columns?: string[]; data?: number[][] } | undefined
      out.push(csvRow('行', '列', '值'))
      for (let r = 0; r < (v?.data?.length ?? 0); r++) {
        const row = v!.data![r]
        for (let c = 0; c < row.length; c++) {
          out.push(csvRow(v?.rows?.[r] ?? r, v?.columns?.[c] ?? c, row[c]))
        }
      }
    } else if (o.type === 'topo') {
      const v = o.value as {
        nodes?: { id?: string; name?: string; type?: string }[]
        edges?: { source?: string; target?: string }[]
      } | undefined
      out.push(csvRow('节点ID', '名称', '类型'))
      for (const n of v?.nodes ?? []) out.push(csvRow(n.id, n.name, n.type))
      out.push(csvRow('边', 'source', 'target'))
      for (const e of v?.edges ?? []) out.push(csvRow('', e.source, e.target))
    }
    out.push(csvRow(''))
  }
  return '\uFEFF' + out.join('')
}

/** 对比表行：同名输出指标 × 各方案值。 */
export interface CompareRow {
  key: string
  label: string
  type: string
  unit?: string
  values: (string | null)[]
}

/** 结构化输出在对比表中的紧凑摘要（避免直接字符串化对象显示 "[object Object]"）。 */
function structuredSummary(o: OutputValue): string {
  if (o.type === 'series') {
    const v = o.value as { x?: unknown[] } | undefined
    return `曲线（${v?.x?.length ?? 0} 点）`
  }
  if (o.type === 'topo') {
    const v = o.value as { nodes?: unknown[]; edges?: unknown[] } | undefined
    return `拓扑（${v?.nodes?.length ?? 0} 节点 / ${v?.edges?.length ?? 0} 边）`
  }
  if (o.type === 'heatmap') {
    const v = o.value as { rows?: unknown[]; columns?: unknown[] } | undefined
    return `热力图（${v?.rows?.length ?? 0}×${v?.columns?.length ?? 0}）`
  }
  if (o.type === 'compare' || o.type === 'dist' || o.type === 'gauge') {
    const items = itemsOf(o)
    return items.length > 0 ? items.map((it) => `${it.name}=${it.value}`).join('，') : '-'
  }
  return formatValue(o.value)
}

/** 多方案同名指标行（以第一组方案的输出定义为准）。 */
export function compareRows(selected: ParamSet[]): CompareRow[] {
  const first = selected[0]
  if (!first) return []
  const rows: CompareRow[] = []
  for (const o of first.result.outputs ?? []) {
    const values = selected.map((s) => {
      const found = s.result.outputs.find((x) => x.key === o.key)
      return found ? structuredSummary(found) : null
    })
    rows.push({ key: o.key, label: o.label, type: o.type, unit: o.unit, values })
  }
  return rows
}

/** 多方案对比 → CSV（参数快照矩阵 + 同名指标矩阵，FR-011）。 */
export function buildCompareCsv(selected: ParamSet[]): string {
  const out: string[] = []
  out.push(csvRow('导出时间', new Date().toISOString()))
  out.push(csvRow(''))
  out.push(csvRow('--- 参数快照对比 ---'))
  out.push(csvRow('参数key', ...selected.map((s) => s.name)))
  const paramKeys = new Set<string>()
  for (const s of selected) {
    for (const k of Object.keys(s.params ?? {})) paramKeys.add(k)
  }
  for (const k of paramKeys) {
    out.push(csvRow(k, ...selected.map((s) => s.params?.[k])))
  }
  out.push(csvRow(''))
  out.push(csvRow('--- 同名输出指标对比 ---'))
  out.push(csvRow('指标', ...selected.map((s) => s.name)))
  for (const r of compareRows(selected)) {
    out.push(csvRow(`${r.label}${r.unit ? `（${r.unit}）` : ''}`, ...r.values))
  }
  return '\uFEFF' + out.join('')
}

/** 触发浏览器下载。 */
export function downloadFile(filename: string, content: string | Blob, mime = 'text/csv;charset=utf-8') {
  const blob = typeof content === 'string' ? new Blob([content], { type: mime }) : content
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** 触发 PNG 下载（ECharts getDataURL 结果）。 */
export function downloadPng(filename: string, dataUrl: string) {
  const a = document.createElement('a')
  a.href = dataUrl
  a.download = filename
  a.click()
}
