<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { StepEvent } from '@/types'

/**
 * 分步回放时间线（T033，FR-009/FR-014）：上一步/下一步/自动播放（1.5s/步），
 * 点击时间线节点跳转；当前步骤高亮并展示消息与数据快照。
 */
const props = defineProps<{
  steps: StepEvent[]
  /** 当前步骤索引（0-based，双向绑定）。 */
  modelValue?: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: number): void
  (e: 'change', v: number): void
}>()

const current = ref(props.modelValue ?? 0)
const playing = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

watch(
  () => props.modelValue,
  (v) => {
    if (v !== undefined && v !== current.value) current.value = v
  },
)
watch(current, (v) => {
  emit('update:modelValue', v)
  emit('change', v)
})
watch(
  () => props.steps.length,
  () => {
    if (current.value > props.steps.length - 1) current.value = Math.max(0, props.steps.length - 1)
  },
)

const step = computed<StepEvent | null>(() => props.steps[current.value] ?? null)
const hasPrev = computed(() => current.value > 0)
const hasNext = computed(() => current.value < props.steps.length - 1)

function prev() {
  if (hasPrev.value) current.value -= 1
}

function next() {
  if (hasNext.value) current.value += 1
}

function togglePlay() {
  if (playing.value) stop()
  else start()
}

function start() {
  if (props.steps.length === 0) return
  playing.value = true
  timer = setInterval(() => {
    if (!hasNext.value) {
      stop()
      return
    }
    current.value += 1
  }, 1500)
}

function stop() {
  playing.value = false
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onBeforeUnmount(stop)

function typeTag(t: string): 'primary' | 'info' | 'warning' | 'danger' {
  if (t === 'STEP') return 'primary'
  if (t === 'WARN') return 'warning'
  if (t === 'ERROR') return 'danger'
  return 'info'
}

function dataEntries(data: Record<string, unknown> | undefined): [string, string][] {
  if (!data) return []
  return Object.entries(data).map(([k, v]) => [
    k,
    v === null || v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v),
  ])
}
</script>

<template>
  <div class="step-timeline" data-test="step-timeline">
    <div class="timeline-header">
      <span class="timeline-title" data-test="step-indicator">
        {{ steps.length === 0 ? '暂无步骤' : `步骤 ${current + 1} / ${steps.length}` }}
      </span>
      <div class="timeline-actions">
        <el-button size="small" :disabled="!hasPrev" data-test="step-prev" @click="prev">
          上一步
        </el-button>
        <el-button size="small" :disabled="!hasNext" data-test="step-next" @click="next">
          下一步
        </el-button>
        <el-button
          size="small"
          :type="playing ? 'warning' : 'primary'"
          :disabled="steps.length === 0"
          data-test="step-play"
          @click="togglePlay"
        >
          {{ playing ? '暂停' : '自动播放' }}
        </el-button>
      </div>
    </div>

    <div v-if="steps.length === 0" class="timeline-empty">
      <el-empty description="运行完成后可在此分步回放" :image-size="60" />
    </div>

    <div v-else class="timeline-body">
      <!-- 左侧：可点击的时间线 -->
      <el-scrollbar class="timeline-scroll">
        <el-timeline class="timeline-list">
          <el-timeline-item
            v-for="(s, i) in steps"
            :key="s.stepNo"
            :type="i === current ? 'primary' : 'info'"
            :hollow="i !== current"
            @click="current = i"
          >
            <div
              class="timeline-node"
              :class="{ active: i === current }"
              :data-test="`step-item-${i}`"
            >
              <span class="node-no">#{{ s.stepNo }}</span>
              <el-tag size="small" :type="typeTag(s.eventType)" class="node-tag">{{ s.eventType }}</el-tag>
              <span class="node-msg">{{ s.message }}</span>
            </div>
          </el-timeline-item>
        </el-timeline>
      </el-scrollbar>

      <!-- 右侧：当前步骤详情 -->
      <div class="timeline-detail" data-test="step-detail">
        <template v-if="step">
          <div class="detail-message" data-test="step-message">{{ step.message }}</div>
          <div class="detail-meta">
            <el-tag size="small" :type="typeTag(step.eventType)">{{ step.eventType }}</el-tag>
            <span class="detail-stepno">第 {{ step.stepNo }} 步</span>
          </div>
          <div v-if="dataEntries(step.data).length > 0" class="detail-data">
            <div v-for="[k, v] in dataEntries(step.data)" :key="k" class="detail-row">
              <span class="detail-key">{{ k }}</span>
              <span class="detail-val">{{ v }}</span>
            </div>
          </div>
          <div v-else class="detail-data empty">该步骤无数据快照</div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.step-timeline {
  width: 100%;
}
.timeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  flex-wrap: wrap;
  gap: 8px;
}
.timeline-title {
  font-weight: 600;
  color: #303133;
  font-size: 14px;
}
.timeline-actions {
  display: flex;
  gap: 8px;
}
.timeline-empty {
  padding: 8px 0;
}
.timeline-body {
  display: flex;
  gap: 16px;
  align-items: stretch;
}
.timeline-scroll {
  flex: 1;
  max-height: 380px;
}
.timeline-list {
  padding: 4px 0 0 4px;
}
.timeline-node {
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 6px;
  border: 1px solid transparent;
  line-height: 1.6;
}
.timeline-node.active {
  border-color: #409eff;
  background: #ecf5ff;
}
.node-no {
  font-family: Consolas, monospace;
  color: #909399;
  font-size: 12px;
  margin-right: 6px;
}
.node-tag {
  margin-right: 6px;
}
.node-msg {
  font-size: 13px;
  color: #606266;
}
.timeline-detail {
  flex: 1;
  min-width: 240px;
  padding: 12px 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #ebeef5;
  align-self: flex-start;
}
.detail-message {
  font-size: 14px;
  color: #303133;
  line-height: 1.7;
  margin-bottom: 10px;
}
.detail-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.detail-stepno {
  color: #909399;
  font-size: 12px;
}
.detail-data {
  border-top: 1px dashed #dcdfe6;
  padding-top: 10px;
}
.detail-row {
  display: flex;
  gap: 12px;
  font-size: 12px;
  padding: 3px 0;
}
.detail-key {
  color: #909399;
  min-width: 120px;
  font-family: Consolas, monospace;
}
.detail-val {
  color: #303133;
  word-break: break-all;
}
.detail-data.empty {
  color: #c0c4cc;
}
</style>
