<script setup lang="ts">
import { inject, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CHART_REGISTRY_KEY, type ChartRegistry } from '@/charts/chartRegistry'
import { buildReportCsv, downloadFile, downloadPng } from '@/utils/report'
import type { RunResult } from '@/types'

/**
 * 导出按钮（T037，FR-012）：图表（ECharts PNG，经父级图表注册表）+ 数据（CSV，
 * 参数快照 + 全量输出数据，R-09）。失败提示并支持重试（Edge Case）。
 */
const props = defineProps<{
  result: RunResult
  scenarioName: string
}>()

const registry = inject<ChartRegistry | null>(CHART_REGISTRY_KEY, null)
const pngLoading = ref(false)
const csvLoading = ref(false)

/** 文件名安全化（去除 Windows 非法字符）。 */
function safeName(): string {
  return (props.scenarioName || '仿真结果').replace(/[\\/:*?"<>|]/g, '_')
}

/** 导出全部已注册图表为 PNG（按输出 key 命名）。 */
async function exportPng() {
  if (!registry || registry.length === 0) {
    ElMessage.warning('当前无图表可导出（仅标量结果时请改用数据导出）')
    return
  }
  pngLoading.value = true
  try {
    registry.forEach(({ key, chart }, i) => {
      const dataUrl = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
      downloadPng(`${safeName()}-${key || `chart${i + 1}`}.png`, dataUrl)
    })
    ElMessage.success(`已导出 ${registry.length} 张图表`)
  } catch (err) {
    ElMessage.error(`图表导出失败：${err instanceof Error ? err.message : '未知错误'}，请重试`)
  } finally {
    pngLoading.value = false
  }
}

/** 导出结果报告 CSV（含参数快照；series 为全量数据）。 */
function exportCsv() {
  csvLoading.value = true
  try {
    downloadFile(`${safeName()}-run${props.result.runId}-数据.csv`, buildReportCsv(props.result, props.scenarioName))
    ElMessage.success('数据报告已导出（CSV）')
  } catch (err) {
    ElMessage.error(`数据导出失败：${err instanceof Error ? err.message : '未知错误'}，请重试`)
  } finally {
    csvLoading.value = false
  }
}
</script>

<template>
  <span class="export-buttons">
    <el-button size="small" :loading="pngLoading" data-test="export-png" @click="exportPng">导出图表</el-button>
    <el-button size="small" :loading="csvLoading" data-test="export-csv" @click="exportCsv">导出数据</el-button>
  </span>
</template>

<style scoped>
.export-buttons {
  display: inline-flex;
  gap: 4px;
}
</style>
