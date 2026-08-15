<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { usePlanStore } from '@/stores/planStore'
import type { RunResult } from '@/types'

/**
 * 保存方案对话框（T036，FR-011）：把当前参数快照 + 运行结果保存到 localStorage
 * （planStore），供「方案对比」页使用。默认命名「场景名-月日-时分」，可修改。
 */
const props = defineProps<{
  modelValue: boolean
  scenarioId: number
  scenarioName: string
  params: Record<string, unknown>
  seed: number
  result: RunResult
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const planStore = usePlanStore()
const name = ref('')
const saving = ref(false)

function defaultName(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${props.scenarioName}-${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}`
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) name.value = defaultName()
  },
)

async function onConfirm() {
  const trimmed = name.value.trim()
  if (!trimmed) return
  saving.value = true
  try {
    planStore.save({
      scenarioId: props.scenarioId,
      scenarioName: props.scenarioName,
      name: trimmed,
      params: props.params,
      seed: props.seed,
      result: props.result,
    })
    ElMessage.success('方案已保存，可在「方案对比」中查看')
    emit('saved')
    emit('update:modelValue', false)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '保存失败，请重试')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="保存方案"
    width="420px"
    :close-on-click-modal="false"
    append-to-body
    data-test="save-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="save-tip">保存参数快照与运行结果，可在「方案对比」中与其它方案同指标对比。</p>
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="方案名称" required>
        <el-input
          v-model="name"
          maxlength="40"
          placeholder="如：仓库数=3 方案"
          data-test="save-name"
          @keyup.enter="onConfirm"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-test="save-cancel" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="!name.trim()"
        data-test="save-confirm"
        @click="onConfirm"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.save-tip {
  color: #909399;
  font-size: 13px;
  margin: 0 0 4px;
}
</style>
