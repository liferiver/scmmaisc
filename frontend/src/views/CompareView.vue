<script setup lang="ts">
import { computed, provide, ref, shallowReactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CHART_REGISTRY_KEY, type ChartRegistry } from '@/charts/chartRegistry'
import OutputChart from '@/components/OutputChart.vue'
import ExportButton from '@/components/ExportButton.vue'
import { usePlanStore } from '@/stores/planStore'
import { buildCompareCsv, compareRows, downloadFile } from '@/utils/report'
import type { OutputValue, ParamSet } from '@/types'

/**
 * 多方案对比页（T038，US3 / FR-011）：已保存方案列表（勾选 ≥2 组）→
 * 同名输出指标并排对比表 + 对比图；删除需确认；导出对比数据（T037 工具）。
 */
const planStore = usePlanStore()
const chartRegistry = shallowReactive<ChartRegistry>([])
provide(CHART_REGISTRY_KEY, chartRegistry)

const selected = ref<ParamSet[]>([])
const indicatorKey = ref('')
const exporting = ref(false)

/** 方案列表变化时保持勾选（默认全选），被删除的方案自动取消勾选。 */
watch(
  () => planStore.sets,
  (sets) => {
    const ids = new Set(selected.value.map((s) => s.id))
    const kept = sets.filter((s) => ids.has(s.id))
    selected.value = kept.length > 0 ? kept : [...sets]
  },
  { immediate: true },
)

function onSelectionChange(v: ParamSet[]) {
  selected.value = v
}

const comparable = computed(() => selected.value.length >= 2)

/** 可对比指标：所有选中方案都存在的非结构型输出（结构图不适合并排对比）。 */
const indicatorOptions = computed(() => {
  if (selected.value.length === 0) return []
  const first = selected.value[0]
  return (first.result.outputs ?? [])
    .filter((o) => o.type !== 'heatmap' && o.type !== 'topo')
    .filter((o) => selected.value.every((s) => s.result.outputs.some((x) => x.key === o.key)))
    .map((o) => ({ key: o.key, label: o.label, type: o.type, unit: o.unit }))
})

watch(
  indicatorOptions,
  (opts) => {
    if (indicatorKey.value && opts.some((o) => o.key === indicatorKey.value)) return
    indicatorKey.value = opts[0]?.key ?? ''
  },
  // immediate：初始渲染前即已选中 ≥2 组方案时也要立即默认首个指标（否则对比图需交互后才出现）
  { immediate: true },
)

const rows = computed(() => compareRows(selected.value))

/** 选中指标的对比图（合成 OutputValue：series 叠加 / 其余转柱状，复用 chartFactory）。 */
const compareOutput = computed<OutputValue | null>(() => {
  if (!comparable.value || !indicatorKey.value) return null
  const def = indicatorOptions.value.find((o) => o.key === indicatorKey.value)
  if (!def) return null
  if (def.type === 'series') {
    const first = selected.value[0]
    const src = first.result.outputs.find((o) => o.key === def.key)?.value as
      | { x?: unknown[]; series?: { name?: string; data?: unknown[] }[] }
      | undefined
    const x = src?.x ?? []
    const series = selected.value.map((s) => {
      const v = s.result.outputs.find((o) => o.key === def.key)?.value as
        | { series?: { name?: string; data?: unknown[] }[] }
        | undefined
      return { name: s.name, data: (v?.series?.[0]?.data ?? []).slice(0, x.length) }
    })
    return { key: def.key, label: `${def.label}（多方案对比）`, type: 'series', value: { x, series }, unit: def.unit }
  }
  const items = selected.value.map((s) => {
    const v = s.result.outputs.find((o) => o.key === def.key)?.value
    return { name: s.name, value: typeof v === 'number' ? v : Number(v ?? NaN) }
  })
  return { key: def.key, label: `${def.label}（多方案对比）`, type: 'compare', value: items, unit: def.unit }
})

/** 导出对比矩阵 CSV（参数快照 + 同名指标，T037）。 */
function exportCompareCsv() {
  if (!comparable.value) return
  exporting.value = true
  try {
    downloadFile(`方案对比-${new Date().toISOString().slice(0, 10)}.csv`, buildCompareCsv(selected.value))
    ElMessage.success('对比数据已导出（CSV）')
  } catch (err) {
    ElMessage.error(`导出失败：${err instanceof Error ? err.message : '未知错误'}，请重试`)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="compare-page">
    <div class="compare-header">
      <h2 class="compare-title">方案对比</h2>
      <el-button
        v-if="comparable"
        type="primary"
        plain
        :loading="exporting"
        data-test="compare-export-csv"
        @click="exportCompareCsv"
      >
        导出对比数据
      </el-button>
    </div>

    <el-empty
      v-if="planStore.sets.length === 0"
      description="暂无保存的方案：先运行并「保存方案」，再回来对比"
      data-test="compare-empty"
    >
      <router-link to="/" class="el-button el-button--primary" data-test="compare-goto">去运行场景</router-link>
    </el-empty>

    <template v-else>
      <el-card shadow="never" class="compare-card">
        <template #header>已保存方案（勾选参与对比，至少 2 组）</template>
        <el-table :data="planStore.sets" data-test="compare-table" @selection-change="onSelectionChange">
          <el-table-column type="selection" width="48" />
          <el-table-column prop="name" label="方案名称" min-width="160" />
          <el-table-column prop="scenarioName" label="场景" min-width="180" />
          <el-table-column prop="seed" label="种子" width="80" />
          <el-table-column label="保存时间" width="170">
            <template #default="{ row }">{{ new Date(row.savedAt).toLocaleString() }}</template>
          </el-table-column>
          <el-table-column label="操作" width="260">
            <template #default="{ row }">
              <ExportButton :result="row.result" :scenario-name="row.scenarioName" />
              <el-popconfirm title="确认删除该方案？" width="220" @confirm="planStore.remove(row.id)">
                <template #reference>
                  <el-button size="small" type="danger" plain :data-test="`compare-delete-${row.id}`">
                    删除
                  </el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="comparable" shadow="never" class="compare-card">
        <template #header>
          <div class="compare-card-header">
            <span>同指标对比</span>
            <el-select
              v-model="indicatorKey"
              size="small"
              class="indicator-select"
              data-test="compare-indicator-select"
            >
              <el-option
                v-for="o in indicatorOptions"
                :key="o.key"
                :label="`${o.label}${o.unit ? `（${o.unit}）` : ''}`"
                :value="o.key"
              />
            </el-select>
          </div>
        </template>

        <el-table :data="rows" data-test="compare-compare-table">
          <el-table-column prop="label" label="输出指标" min-width="200">
            <template #default="{ row }">
              {{ row.label }}<span v-if="row.unit" class="row-unit">（{{ row.unit }}）</span>
            </template>
          </el-table-column>
          <el-table-column v-for="s in selected" :key="s.id" :label="s.name" min-width="140">
            <template #default="{ row }">{{ row.values[selected.indexOf(s)] }}</template>
          </el-table-column>
        </el-table>

        <div v-if="compareOutput" class="compare-chart" data-test="compare-chart">
          <OutputChart :output="compareOutput" />
        </div>
      </el-card>

      <el-alert
        v-else-if="planStore.sets.length >= 1"
        title="请勾选至少 2 组方案进行对比"
        type="info"
        :closable="false"
        class="compare-tip"
        data-test="compare-need-two"
      />
    </template>
  </div>
</template>

<style scoped>
.compare-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px;
}
.compare-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.compare-title {
  margin: 0;
  font-size: 20px;
  color: #303133;
}
.compare-card {
  margin-bottom: 16px;
}
.compare-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.indicator-select {
  width: 260px;
}
.row-unit {
  color: #909399;
  font-size: 12px;
}
.compare-chart {
  margin-top: 16px;
  border-top: 1px dashed #ebeef5;
  padding-top: 16px;
}
.compare-tip {
  margin-bottom: 16px;
}
</style>
