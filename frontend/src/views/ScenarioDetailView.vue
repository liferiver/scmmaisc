<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useScenarioStore } from '@/stores/scenarioStore'
import type { Parameter, ScenarioDetail } from '@/types'

/**
 * 场景说明页（T019，US1）：核心概念/流程描述/参数表/输出表/依赖跳转/角色扮演标识 + "进入模拟"入口。
 * 边界处理（T040）：依赖模块未实现时标注"尚未开放"（FR-013 提示而非阻断）；
 * 场景数据缺失（参数缺默认值）时给出降级提示而非崩溃。
 */
const props = defineProps<{ moduleId: string }>()

const router = useRouter()
const store = useScenarioStore()

const detail = ref<ScenarioDetail | null>(null)
const loading = ref(false)
const error = ref('')

/** 已开放模块集合（目录接口拉取，失败时降级为全部视为可跳转）。 */
const availableModules = ref<Set<string>>(new Set())
/** 目录是否已成功加载：失败时不误标"尚未开放"。 */
const catalogLoaded = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  loadCatalog()
  try {
    await store.fetchCurrent(props.moduleId)
    detail.value = store.current
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

/** 拉取全部模块清单用于判断依赖是否已实现（失败不影响详情展示）。 */
async function loadCatalog() {
  try {
    const all = await store.fetchAllScenarios()
    availableModules.value = new Set(all.map((s) => s.moduleId))
    catalogLoaded.value = true
  } catch {
    // 目录不可用时 catalogLoaded 保持 false，依赖一律按可跳转处理，避免误标"尚未开放"
  }
}

/** 依赖模块已实现（可跳转）与否。 */
function isDepOpen(dep: string): boolean {
  return !catalogLoaded.value || availableModules.value.has(dep)
}

/** 缺少默认值的参数（bool 有默认 false，排除）；用于降级提示。 */
const missingDefaultParams = computed(() =>
  (detail.value?.params ?? []).filter((p) => p.default === undefined && p.type !== 'bool'),
)

/**
 * 难度标签文案（FR-016）：intro/basic/advanced/comprehensive 四级。
 */
function difficultyText(d: string) {
  return d === 'intro' ? '入门' : d === 'basic' ? '基础' : d === 'comprehensive' ? '综合' : '进阶'
}

function difficultyType(d: string) {
  return d === 'intro' ? 'success' : d === 'basic' ? 'warning' : d === 'comprehensive' ? 'primary' : 'danger'
}

function paramTypeText(t: string) {
  const map: Record<string, string> = {
    int: '整数',
    float: '浮点数',
    enum: '枚举',
    dist: '随机分布',
    bool: '布尔',
    matrix: '矩阵',
    timeseries: '时间序列',
    func: '函数',
  }
  return map[t] ?? t
}

function outputTypeText(t: string) {
  const map: Record<string, string> = {
    scalar: '数值',
    series: '序列曲线',
    compare: '对比',
    dist: '分布',
    topo: '拓扑关系',
    heatmap: '热力图',
    gauge: '仪表盘',
    matrix: '矩阵',
    timeseries: '时间序列',
  }
  return map[t] ?? t
}

function paramRange(p: Parameter) {
  if (p.type === 'enum') {
    return p.options?.map((o) => o.label).join(' / ') ?? '-'
  }
  if (p.min !== undefined && p.max !== undefined) {
    return `${p.min} ~ ${p.max}`
  }
  if (p.min !== undefined) return `≥ ${p.min}`
  if (p.max !== undefined) return `≤ ${p.max}`
  return '-'
}

function paramDefault(p: Parameter) {
  if (p.default === undefined || p.default === null) return '-'
  if (typeof p.default === 'object') return JSON.stringify(p.default)
  return String(p.default)
}

function enterRun() {
  router.push(`/scenarios/${props.moduleId}/run`)
}

function jumpToDep(dep: string) {
  router.push(`/scenarios/${dep}`)
}

onMounted(load)
</script>

<template>
  <div class="detail-page">
    <!-- 加载态 -->
    <div v-if="loading" data-test="detail-loading">
      <el-skeleton :rows="10" animated />
    </div>

    <!-- 错误态 -->
    <div v-else-if="error" data-test="detail-error" class="detail-state">
      <el-empty :description="error">
        <el-button type="primary" data-test="detail-retry" @click="load">重试</el-button>
      </el-empty>
    </div>

    <div v-else-if="detail" data-test="detail-content">
      <!-- 头部：编号/名称/难度/课时/角色扮演 -->
      <div class="detail-header">
        <div class="detail-title-line">
          <span class="detail-module">{{ detail.moduleId }}</span>
          <h2 class="detail-name">{{ detail.name }}</h2>
          <el-tag :type="difficultyType(detail.difficulty)">{{ difficultyText(detail.difficulty) }}</el-tag>
          <el-tag v-if="detail.isRolePlay" type="warning" class="roleplay-tag">角色扮演</el-tag>
        </div>
        <div v-if="detail.classHours" class="detail-meta">建议课时：{{ detail.classHours }} 学时</div>
        <el-button type="primary" size="large" data-test="enter-run" @click="enterRun">
          进入模拟
        </el-button>
      </div>

      <!-- 依赖模块（FR-013）：标注 + 跳转；未实现的模块标注"尚未开放"（T040，提示而非阻断） -->
      <el-alert
        v-if="detail.deps && detail.deps.length > 0"
        type="info"
        :closable="false"
        class="deps-alert"
      >
        <template #title>
          <span>前置模块：</span>
          <template v-for="dep in detail.deps" :key="dep">
            <a
              v-if="isDepOpen(dep)"
              class="dep-link"
              data-test="dep-link"
              @click.prevent="jumpToDep(dep)"
            >
              {{ dep }}
            </a>
            <el-tag v-else size="small" type="info" class="dep-pending-tag" data-test="dep-pending">
              {{ dep }} · 尚未开放
            </el-tag>
          </template>
        </template>
      </el-alert>

      <!-- 场景数据缺失降级提示（T040，spec Edge Cases）：参数缺默认值时提示需手动填写 -->
      <el-alert
        v-if="missingDefaultParams.length > 0"
        type="warning"
        :closable="false"
        class="deps-alert"
        data-test="missing-defaults-alert"
      >
        <template #title>
          该场景 {{ missingDefaultParams.length }} 个参数缺少默认值（{{
            missingDefaultParams.map((p) => p.key).join('、')
          }}），进入模拟后需手动填写，未填写前无法运行。
        </template>
      </el-alert>

      <!-- 核心概念 -->
      <el-card shadow="never" class="section-card">
        <template #header>核心概念</template>
        <p class="section-text">{{ detail.concept }}</p>
      </el-card>

      <!-- 流程描述 -->
      <el-card shadow="never" class="section-card">
        <template #header>流程描述</template>
        <p class="section-text">{{ detail.description }}</p>
      </el-card>

      <!-- 输入参数表（FR-003） -->
      <el-card shadow="never" class="section-card">
        <template #header>输入参数</template>
        <el-table :data="detail.params" size="small" data-test="params-table">
          <el-table-column prop="key" label="参数名" width="150" />
          <el-table-column prop="label" label="名称" width="160" />
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ paramTypeText(row.type) }}</template>
          </el-table-column>
          <el-table-column label="取值范围" min-width="150">
            <template #default="{ row }">{{ paramRange(row) }}</template>
          </el-table-column>
          <el-table-column label="默认值" width="120">
            <template #default="{ row }">{{ paramDefault(row) }}</template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="100" />
          <el-table-column prop="description" label="说明" min-width="180" />
        </el-table>
      </el-card>

      <!-- 输出指标表 -->
      <el-card shadow="never" class="section-card">
        <template #header>输出指标</template>
        <el-table :data="detail.outputs" size="small" data-test="outputs-table">
          <el-table-column prop="key" label="指标名" width="180" />
          <el-table-column prop="label" label="名称" min-width="160" />
          <el-table-column label="类型" width="120">
            <template #default="{ row }">{{ outputTypeText(row.type) }}</template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="100" />
        </el-table>
      </el-card>

      <!-- 约束条件（FR-005 提示） -->
      <el-card v-if="detail.constraints && detail.constraints.length > 0" shadow="never" class="section-card">
        <template #header>运行约束</template>
        <ul class="constraint-list">
          <li v-for="c in detail.constraints" :key="c.name" data-test="constraint-item">
            {{ c.message }}（{{ c.expression }}）
          </li>
        </ul>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.detail-state {
  padding: 40px 0;
}
.detail-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.detail-title-line {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 280px;
}
.detail-module {
  font-family: Consolas, monospace;
  color: #909399;
  font-size: 14px;
}
.detail-name {
  margin: 0;
  font-size: 22px;
  color: #303133;
}
.detail-meta {
  color: #909399;
  font-size: 13px;
  width: 100%;
}
.deps-alert {
  margin-bottom: 16px;
}
.dep-link {
  color: #409eff;
  margin-right: 12px;
  cursor: pointer;
  font-family: Consolas, monospace;
}
.dep-pending-tag {
  margin-right: 12px;
  font-family: Consolas, monospace;
}
.section-card {
  margin-bottom: 16px;
}
.section-text {
  margin: 0;
  line-height: 1.8;
  color: #606266;
  white-space: pre-wrap;
}
.constraint-list {
  margin: 0;
  padding-left: 20px;
  color: #606266;
  line-height: 1.9;
}
</style>
