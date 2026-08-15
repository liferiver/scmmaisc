<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { buildOption } from '@/charts/chartFactory'
import { CHART_REGISTRY_KEY, type ChartRegistry } from '@/charts/chartRegistry'
import { formatValue } from '@/utils/report'
import type { OutputValue } from '@/types'

/**
 * 输出指标可视化组件（T032，FR-007）：scalar → 数值卡片；
 * series/compare/dist/gauge/heatmap/topo → ECharts（chartFactory 映射），
 * 自适应窗口缩放，卸载时销毁实例；实例注册到父级注册表供 T037 导出 PNG。
 */
const props = defineProps<{ output: OutputValue }>()

const el = ref<HTMLDivElement | null>(null)
const registry = inject<ChartRegistry | null>(CHART_REGISTRY_KEY, null)
let chart: echarts.ECharts | null = null
let renderedKey = ''

function render() {
  if (!el.value) return
  const option = buildOption(props.output)
  if (!option) return
  if (!chart) {
    chart = echarts.init(el.value)
    syncRegistry()
  }
  chart.setOption(option, true)
  renderedKey = props.output.key
}

/** 向父级注册表同步当前实例（key 变化时更新条目）。 */
function syncRegistry() {
  if (!registry || !chart) return
  const idx = registry.findIndex((e) => e.key === props.output.key)
  if (idx >= 0) registry[idx] = { key: props.output.key, chart }
  else registry.push({ key: props.output.key, chart })
}

function resize() {
  chart?.resize()
}

function dispose() {
  chart?.dispose()
  chart = null
}

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (registry) {
    const idx = registry.findIndex((e) => e.key === props.output.key)
    if (idx >= 0) registry.splice(idx, 1)
  }
  dispose()
})

watch(
  () => props.output,
  () => {
    // 输出 key/类型变化时重建实例，避免旧图表残留
    if (chart && renderedKey !== props.output.key) dispose()
    render()
  },
  { deep: true },
)

/** scalar 数值卡片（FR-007：数值直显）。 */
const isScalar = computed(() => props.output.type === 'scalar')
</script>

<template>
  <div class="output-chart" data-test="output-chart">
    <div v-if="isScalar" class="scalar-card" data-test="output-scalar">
      <div class="scalar-label">{{ output.label }}</div>
      <div class="scalar-value">
        <span class="scalar-number" data-test="output-scalar-value">{{ formatValue(output.value) }}</span>
        <span v-if="output.unit" class="scalar-unit">{{ output.unit }}</span>
      </div>
    </div>
    <div v-else ref="el" class="chart-box" :data-test="`output-${output.type}`" />
  </div>
</template>

<style scoped>
.output-chart {
  width: 100%;
}
.chart-box {
  width: 100%;
  height: 280px;
}
.scalar-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  padding: 16px;
  background: linear-gradient(135deg, #f0f6ff 0%, #ffffff 100%);
  border: 1px solid #e4ecf7;
  border-radius: 8px;
}
.scalar-label {
  color: #909399;
  font-size: 13px;
  margin-bottom: 12px;
}
.scalar-value {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.scalar-number {
  font-size: 34px;
  font-weight: 600;
  color: #303133;
}
.scalar-unit {
  color: #909399;
  font-size: 14px;
}
</style>
