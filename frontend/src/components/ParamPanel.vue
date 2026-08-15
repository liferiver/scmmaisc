<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { Parameter } from '@/types'

/**
 * 参数面板（T031，FR-004）：按 type 渲染控件（int/float→el-input-number、enum/func→el-select、
 * bool→el-switch、dist→子参数组、matrix/timeseries→表格）；即时校验（越界/非数字红字提示），
 * 存在错误时不可提交；重置需确认（存在未保存修改时）。
 */
const props = defineProps<{
  params: Parameter[]
  modelValue: Record<string, unknown>
  submitting?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, unknown>): void
  (e: 'submit'): void
  (e: 'reset'): void
}>()

const form = reactive<Record<string, unknown>>({})
for (const p of props.params) {
  form[p.key] = props.modelValue[p.key] ?? clone(p.default ?? defaultFor(p.type))
}

let syncing = false
watch(
  () => props.modelValue,
  (v) => {
    if (syncing) return
    // 父级回写值与当前表单内容一致时无需重建（深层 watch 会再次触发，形成递归更新死循环）
    if (JSON.stringify(v) === JSON.stringify(form)) return
    syncing = true
    for (const key of Object.keys(form)) delete form[key]
    Object.assign(form, v)
    syncing = false
  },
)
watch(
  form,
  (v) => {
    if (syncing) return
    const payload: Record<string, unknown> = { ...v }
    // 提交给后端前将合法数字串归一化为 number（后端按 JSON 数值校验）
    for (const p of props.params) {
      const raw = payload[p.key]
      if ((p.type === 'int' || p.type === 'float') && typeof raw === 'string' && raw !== '') {
        const n = Number(raw)
        if (Number.isFinite(n)) payload[p.key] = n
      }
    }
    // 与父级当前值一致时跳过（父级回写同样会触发本 watcher，避免无限递归更新）
    if (JSON.stringify(payload) === JSON.stringify(props.modelValue)) return
    emit('update:modelValue', payload)
  },
  { deep: true },
)

/** 单参数即时校验：返回错误文案，空串 = 通过。 */
function validate(p: Parameter, value: unknown): string {
  if (p.type === 'bool') return ''
  if (p.type === 'enum' || p.type === 'func') {
    return value === null || value === undefined || value === '' ? '请选择' : ''
  }
  if (p.type === 'int' || p.type === 'float') {
    if (value === null || value === undefined || value === '') return '必填'
    const n = Number(value)
    if (!Number.isFinite(n)) return p.type === 'int' ? '必须为整数' : '必须为数字'
    if (p.type === 'int' && !Number.isInteger(n)) return '必须为整数'
    if (p.min !== undefined && n < p.min) return `不能小于 ${p.min}`
    if (p.max !== undefined && n > p.max) return `不能大于 ${p.max}`
    return ''
  }
  if (p.type === 'dist') {
    const group = (value ?? {}) as Record<string, unknown>
    for (const f of p.fields ?? []) {
      const err = validate(f, group[f.key])
      if (err) return `${f.label}${err}`
    }
    return ''
  }
  if (p.type === 'matrix' || p.type === 'timeseries') {
    const rows = Array.isArray(value) ? value : []
    for (const row of rows) {
      if (!Array.isArray(row)) return '必须为数字矩阵'
      for (const cell of row) {
        if (cell === null || cell === undefined || cell === '') return '必填'
        if (!Number.isFinite(Number(cell))) return '必须为数字'
      }
    }
    return ''
  }
  return ''
}

const errors = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const p of props.params) {
    const err = validate(p, form[p.key])
    if (err) map[p.key] = err
  }
  return map
})

const valid = computed(() => Object.keys(errors.value).length === 0)

function hasChanges(): boolean {
  for (const p of props.params) {
    const def = clone(p.default ?? defaultFor(p.type))
    if (JSON.stringify(form[p.key]) !== JSON.stringify(def)) return true
  }
  return false
}

function onReset() {
  if (hasChanges()) {
    ElMessageBox.confirm('当前参数有未保存的修改，确定恢复默认值？', '重置确认', {
      type: 'warning',
      confirmButtonText: '重置',
      cancelButtonText: '取消',
    })
      .then(() => doReset())
      .catch(() => {})
  } else {
    doReset()
  }
}

function doReset() {
  for (const p of props.params) {
    form[p.key] = clone(p.default ?? defaultFor(p.type))
  }
  emit('reset')
}

function defaultFor(type: string): unknown {
  if (type === 'bool') return false
  if (type === 'dist') return {}
  if (type === 'matrix' || type === 'timeseries') return [[]]
  return ''
}

function clone(v: unknown): unknown {
  return JSON.parse(JSON.stringify(v ?? null))
}

function submit() {
  if (valid.value && !props.submitting) emit('submit')
}
</script>

<template>
  <div class="param-panel">
    <el-form label-position="top" size="default">
      <el-form-item v-for="p in params" :key="p.key" :label="p.label">
        <!-- int / float：数字输入（保留原始字符串即时校验，FR-004） -->
        <div v-if="p.type === 'int' || p.type === 'float'" class="param-control" :data-test="`param-${p.key}`">
          <el-input v-model="(form[p.key] as string)" placeholder="请输入数值" />
        </div>
        <!-- enum / func：下拉选择 -->
        <div v-else-if="p.type === 'enum' || p.type === 'func'" class="param-control" :data-test="`param-${p.key}`">
          <el-select v-model="form[p.key]">
            <el-option v-for="o in p.options" :key="String(o.value)" :label="o.label" :value="o.value" />
          </el-select>
        </div>
        <!-- bool：开关 -->
        <div v-else-if="p.type === 'bool'" class="param-control" :data-test="`param-${p.key}`">
          <el-switch v-model="form[p.key] as boolean" />
        </div>
        <!-- dist：子参数组 -->
        <div v-else-if="p.type === 'dist'" class="dist-group" :data-test="`param-${p.key}`">
          <div v-for="f in p.fields ?? []" :key="f.key" class="dist-field">
            <span class="dist-label">{{ f.label }}</span>
            <el-input-number
              v-model="(form[p.key] as Record<string, unknown>)[f.key] as number"
              :min="f.min"
              :max="f.max"
              :step="f.step ?? 0.1"
              :controls="false"
              size="small"
            />
          </div>
        </div>
        <!-- matrix / timeseries：可编辑表格 -->
        <div v-else-if="p.type === 'matrix' || p.type === 'timeseries'" class="param-control matrix-wrap" :data-test="`param-${p.key}`">
          <el-table :data="form[p.key] as unknown[][]" size="small" border>
            <el-table-column
              v-for="(_, ci) in (form[p.key] as unknown[][])[0] ?? []"
              :key="ci"
              :label="`列 ${ci + 1}`"
            >
              <template #default="{ row }">
                <el-input
                  :model-value="String((row as unknown[])[ci] ?? '')"
                  @update:model-value="(v: string) => ((row as unknown[])[ci] = v)"
                />
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div v-if="p.description" class="param-desc">{{ p.description }}</div>
        <!-- 即时红字校验提示（FR-004） -->
        <div v-if="errors[p.key]" class="param-error" :data-test="`param-error-${p.key}`">
          {{ errors[p.key] }}
        </div>
      </el-form-item>
    </el-form>

    <div class="param-actions">
      <el-button type="primary" :disabled="!valid || submitting" :loading="submitting" data-test="run-submit" @click="submit">
        运行模拟
      </el-button>
      <el-button data-test="param-reset" @click="onReset">重置</el-button>
    </div>
  </div>
</template>

<style scoped>
.param-control {
  width: 260px;
}
.param-control :deep(.el-input) {
  width: 100%;
}
.param-control :deep(.el-select) {
  width: 100%;
}
.matrix-wrap {
  width: 100%;
}
.param-desc {
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 2px;
}
.param-error {
  color: #f56c6c;
  font-size: 12px;
  line-height: 1.5;
  margin-top: 2px;
}
.dist-group {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.dist-field {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dist-label {
  color: #606266;
  font-size: 13px;
}
.param-actions {
  margin-top: 8px;
}
</style>
