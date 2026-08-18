<script setup lang="ts">
import { computed, onMounted, provide, ref, shallowReactive } from 'vue'
import { useRouter } from 'vue-router'
import ParamPanel from '@/components/ParamPanel.vue'
import OutputChart from '@/components/OutputChart.vue'
import StepTimeline from '@/components/StepTimeline.vue'
import SavePlanDialog from '@/components/SavePlanDialog.vue'
import ExportButton from '@/components/ExportButton.vue'
import { CHART_REGISTRY_KEY, type ChartRegistry } from '@/charts/chartRegistry'
import { useRunStore } from '@/stores/runStore'
import { useScenarioStore } from '@/stores/scenarioStore'

/**
 * 模拟运行页（T034，US2）：参数面板（T031）→ 提交运行（T023 C4）→
 * el-progress 进度反馈 + 取消（FR-015，C7 DELETE）→ 完成后渲染输出指标（T032）与
 * 步骤回放（T033），集成 scenarioStore 加载场景定义；US3（T036/T037）提供
 * 保存方案与导出图表/数据入口。
 */
const props = defineProps<{ moduleId: string }>()

const router = useRouter()
const scenarioStore = useScenarioStore()
const runStore = useRunStore()

/** 图表实例注册表（T037 导出 PNG 用，OutputChart 自动注册）。 */
const chartRegistry = shallowReactive<ChartRegistry>([])
provide(CHART_REGISTRY_KEY, chartRegistry)

const loading = ref(false)
const loadError = ref('')
const params = ref<Record<string, unknown>>({})
const seed = ref(42)
const saveDialogVisible = ref(false)

const detail = computed(() => scenarioStore.current)

function defaultFor(type: string): unknown {
  if (type === 'bool') return false
  if (type === 'dist') return {}
  if (type === 'matrix' || type === 'timeseries') return [[]]
  return ''
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    await scenarioStore.fetchCurrent(props.moduleId)
    const init: Record<string, unknown> = {}
    for (const p of scenarioStore.current?.params ?? []) {
      init[p.key] = p.default ?? defaultFor(p.type)
    }
    params.value = init
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载场景失败'
  } finally {
    loading.value = false
  }
}

/** C4：提交参数运行（参数校验由 ParamPanel 即时 + 后端兜底）。 */
function onSubmit() {
  if (!detail.value || runStore.running) return
  runStore.submit(detail.value.id, params.value, seed.value)
}

/** C7：取消运行（服务端在下一步前停止，轮询直至终态）。 */
function onCancel() {
  runStore.cancel()
}

/** 再次运行：清空状态回到参数面板。 */
function onRerun() {
  runStore.reset()
}

function backToDetail() {
  runStore.reset()
  router.push(`/scenarios/${props.moduleId}`)
}

const statusText = computed(() => {
  if (runStore.status === 'RUNNING') return '模拟运行中…'
  if (runStore.status === 'COMPLETED') return '运行完成'
  if (runStore.status === 'CANCELLED') return '已取消'
  if (runStore.status === 'FAILED') return '运行失败'
  return ''
})

const outputs = computed(() => runStore.result?.outputs ?? [])
const steps = computed(() => runStore.result?.steps ?? [])
const durationText = computed(() => {
  const ms = runStore.result?.durationMs ?? 0
  return ms > 0 ? `耗时 ${(ms / 1000).toFixed(2)} 秒` : ''
})

onMounted(load)
</script>

<template>
  <div class="run-page">
    <!-- 加载态 -->
    <div v-if="loading" data-test="run-loading">
      <el-skeleton :rows="8" animated />
    </div>

    <!-- 场景加载失败 -->
    <div v-else-if="loadError" data-test="run-error" class="run-state">
      <el-empty :description="loadError">
        <el-button type="primary" data-test="run-retry" @click="load">重试</el-button>
      </el-empty>
    </div>

    <template v-else-if="detail">
      <!-- 头部 -->
      <div class="run-header">
        <el-button link data-test="run-back" @click="backToDetail">← 返回场景说明</el-button>
        <span class="run-module">{{ detail.moduleId }}</span>
        <h2 class="run-title">{{ detail.name }}</h2>
        <div class="run-seed">
          <span class="seed-label">随机种子</span>
          <el-input-number v-model="seed" :min="0" :max="99999" :controls="false" size="small" data-test="run-seed" />
        </div>
      </div>

      <!-- 约束提示（FR-005，仅提示） -->
      <el-alert
        v-if="detail.constraints && detail.constraints.length > 0"
        type="info"
        :closable="false"
        class="run-constraints"
      >
        <template #title>
          <span>运行约束：{{ detail.constraints.map((c) => c.message).join('；') }}</span>
        </template>
      </el-alert>

      <el-row :gutter="16" class="run-body">
        <!-- 左：参数面板（T031） -->
        <el-col :xs="24" :md="9">
          <el-card shadow="never" class="run-card" data-test="run-params">
            <template #header>运行参数</template>
            <ParamPanel
              v-if="detail.params.length > 0"
              v-model="params"
              :params="detail.params"
              :groups="detail.constraintGroups ?? []"
              :submitting="runStore.running"
              data-test="param-panel"
              @submit="onSubmit"
            />
            <el-empty v-else description="该场景无需参数" :image-size="50" />
          </el-card>
        </el-col>

        <!-- 右：状态 / 结果 -->
        <el-col :xs="24" :md="15">
          <el-card shadow="never" class="run-card" data-test="run-result">
            <template #header>运行结果</template>

            <!-- 提交失败（400 校验）或轮询错误 -->
            <el-alert
              v-if="runStore.error && !runStore.running && !runStore.result"
              :title="runStore.error"
              type="error"
              :closable="false"
              class="run-alert"
              data-test="run-error-alert"
            />

            <!-- 运行中：进度 + 取消（FR-015） -->
            <div v-if="runStore.running" class="run-progress" data-test="run-progress">
              <div class="progress-status">{{ statusText }}</div>
              <el-progress
                :percentage="runStore.progress"
                :indeterminate="runStore.stepTotal == null"
                :duration="1"
                :stroke-width="12"
                data-test="run-progress-bar"
              />
              <div class="progress-meta">
                已完成 {{ runStore.stepCount }} 步{{ runStore.stepTotal ? ` / 预计 ${runStore.stepTotal} 步` : '' }}
              </div>
              <el-button type="danger" plain data-test="run-cancel" @click="onCancel">取消运行</el-button>
            </div>

            <!-- 终态提示：取消/失败 -->
            <el-alert
              v-else-if="runStore.status === 'CANCELLED'"
              title="运行已取消"
              type="warning"
              :closable="false"
              class="run-alert"
              data-test="run-cancelled"
            />
            <el-alert
              v-else-if="runStore.status === 'FAILED'"
              :title="runStore.error || '运行失败'"
              type="error"
              :closable="false"
              class="run-alert"
              data-test="run-failed"
            />

            <!-- 完成：输出指标（T032）+ 步骤回放（T033） -->
            <template v-else-if="runStore.result">
              <div class="result-meta" data-test="run-completed">
                <el-tag type="success">运行完成</el-tag>
                <span class="result-info">{{ durationText }}</span>
                <ExportButton :result="runStore.result" :scenario-name="detail.name" />
                <el-button size="small" type="primary" plain data-test="save-plan" @click="saveDialogVisible = true">
                  保存方案
                </el-button>
                <el-button size="small" data-test="run-again" @click="onRerun">再次运行</el-button>
              </div>

              <div v-if="outputs.length > 0" class="output-grid">
                <el-card v-for="o in outputs" :key="o.key" shadow="never" class="output-card" data-test="output-card">
                  <OutputChart :output="o" />
                </el-card>
              </div>
              <el-empty v-else description="本次运行无输出指标" :image-size="60" />

              <div class="timeline-section">
                <div class="section-title">步骤回放</div>
                <StepTimeline :steps="steps" data-test="step-timeline" />
              </div>
            </template>

            <!-- 初始状态 -->
            <el-empty
              v-else
              description="设置参数后点击「运行模拟」开始"
              :image-size="90"
              data-test="run-idle"
            />
          </el-card>
        </el-col>
      </el-row>

      <!-- 仅运行完成后渲染（result 非空），避免向 SavePlanDialog 传 null 触发 prop 类型告警 -->
      <SavePlanDialog
        v-if="runStore.result"
        v-model="saveDialogVisible"
        :scenario-id="detail.id"
        :scenario-name="detail.name"
        :params="runStore.result.params ?? {}"
        :seed="runStore.result.seed ?? seed"
        :result="runStore.result"
      />
    </template>
  </div>
</template>

<style scoped>
.run-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px;
}
.run-state {
  padding: 40px 0;
}
.run-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.run-module {
  font-family: Consolas, monospace;
  color: #909399;
  font-size: 14px;
}
.run-title {
  margin: 0;
  font-size: 20px;
  color: #303133;
  flex: 1;
  min-width: 200px;
}
.run-seed {
  display: flex;
  align-items: center;
  gap: 8px;
}
.seed-label {
  color: #909399;
  font-size: 13px;
}
.run-constraints {
  margin-bottom: 12px;
}
.run-body {
  align-items: flex-start;
}
.run-card {
  margin-bottom: 16px;
}
.run-alert {
  margin-bottom: 12px;
}
.run-progress {
  padding: 24px 8px;
  text-align: center;
}
.progress-status {
  font-size: 15px;
  color: #303133;
  margin-bottom: 16px;
}
.progress-meta {
  color: #909399;
  font-size: 13px;
  margin: 12px 0 16px;
}
.result-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.result-info {
  color: #909399;
  font-size: 13px;
  flex: 1;
}
.output-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.output-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
}
.timeline-section {
  margin-top: 4px;
}
.section-title {
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}
</style>
