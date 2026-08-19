<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { Parameter, ParamGroupConstraint } from '@/types'

/**
 * 参数面板（T031，FR-004）：按 type 渲染控件（int/float→el-input-number、enum/func→el-select、
 * bool→el-switch、dist→子参数组、matrix/timeseries→表格）；即时校验（越界/非数字红字提示），
 * 存在错误时不可提交；重置需确认（存在未保存修改时）。
 * 二期（V11）：语义化标量组（weight_* 等）组头展示实时合计，并按后端下发的组约束
 * （constraintGroups，如权重和=1）即时校验，违反时红字提示且不可提交。
 */
const props = defineProps<{
  params: Parameter[]
  modelValue: Record<string, unknown>
  submitting?: boolean
  groups?: ParamGroupConstraint[]
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
      if (p.type === 'matrix' || p.type === 'timeseries') {
        // 空矩阵 = 可选未填：省略 key，由后端执行器按缺省合成（与可选矩阵设计一致）
        if (matrixEmpty(p)) {
          delete payload[p.key]
        } else {
          // 单元格归一化为 number（后端 matrixParam 要求 JSON 数值）
          payload[p.key] = matrixValue(p).map((row) =>
            row.map((cell) => (typeof cell === 'string' && cell !== '' ? Number(cell) : cell)),
          )
        }
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
    // 空矩阵（[]/全空行）视为可选未填：后端按缺省合成，前端无需报错
    if (matrixEmpty(p)) return ''
    const rows = matrixValue(p)
    let cols = 0
    for (const row of rows) {
      if (!Array.isArray(row)) return '必须为数字矩阵'
      if (cols === 0) cols = row.length
      if (row.length !== cols) return '各行列数必须一致'
      for (const cell of row) {
        if (cell === null || cell === undefined || cell === '') return '必填'
        const n = Number(cell)
        if (!Number.isFinite(n)) return '必须为数字'
        if (p.min !== undefined && n < p.min) return `不能小于 ${p.min}`
        if (p.max !== undefined && n > p.max) return `不能大于 ${p.max}`
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

/** 参数 key → 所属语义组（V11）。 */
const groupByParam = computed<Record<string, ParamGroupConstraint>>(() => {
  const map: Record<string, ParamGroupConstraint> = {}
  for (const g of props.groups ?? []) {
    for (const k of g.params) map[k] = g
  }
  return map
})

/** 各组首参数 key（用于渲染组头）。 */
const groupStarts = computed<Set<string>>(() => {
  const starts = new Set<string>()
  for (const g of props.groups ?? []) {
    if (g.params.length > 0) starts.add(g.params[0])
  }
  return starts
})

/** 组内当前值合计；任一参数缺失/非法时返回 null（单项必填错误已另行提示）。 */
function groupSum(g: ParamGroupConstraint): number | null {
  let sum = 0
  for (const k of g.params) {
    const v = form[k]
    if (v === null || v === undefined || v === '') return null
    const n = Number(v)
    if (!Number.isFinite(n)) return null
    sum += n
  }
  return sum
}

/** 组约束右端值：常量或目标参数当前值；不可求时返回 null。 */
function groupTarget(g: ParamGroupConstraint): number | null {
  if (g.targetParam) {
    const v = form[g.targetParam]
    const n = v === null || v === undefined || v === '' ? Number.NaN : Number(v)
    return Number.isFinite(n) ? n : null
  }
  return g.target
}

function groupOk(g: ParamGroupConstraint, sum: number, target: number): boolean {
  switch (g.op) {
    case '<':
      return sum < target
    case '<=':
      return sum <= target
    case '>':
      return sum > target
    case '>=':
      return sum >= target
    case '!=':
      return Math.abs(sum - target) >= 1e-6
    default:
      return Math.abs(sum - target) <= 1e-6
  }
}

/** 组约束校验错误（key = 约束 name，V11）。 */
const groupErrors = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const g of props.groups ?? []) {
    const sum = groupSum(g)
    if (sum === null) continue
    const target = groupTarget(g)
    if (target === null) continue
    if (!groupOk(g, sum, target)) {
      map[g.name] = `${g.message}（当前合计 ${round2(sum)}）`
    }
  }
  return map
})

function round2(n: number): string {
  return Number.isInteger(n) ? String(n) : String(Math.round(n * 100) / 100)
}

function groupSumText(g: ParamGroupConstraint): string {
  const sum = groupSum(g)
  return sum === null ? '-' : round2(sum)
}

const valid = computed(
  () => Object.keys(errors.value).length === 0 && Object.keys(groupErrors.value).length === 0,
)

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
  if (type === 'matrix' || type === 'timeseries') return []
  return ''
}

/** 矩阵参数最大列数（行数由后端按场景约束，列上限仅防误操作）。 */
const MATRIX_MAX_COLS = 20

/** 当前矩阵值（只读；未填时为 []，不写回 form）。 */
function matrixValue(p: Parameter): unknown[][] {
  const v = form[p.key]
  return Array.isArray(v) ? (v as unknown[][]) : []
}

/** 当前矩阵行（写模式：form 无 key 时初始化为空数组）。 */
function matrixRows(p: Parameter): unknown[][] {
  const v = form[p.key]
  if (!Array.isArray(v)) {
    form[p.key] = []
  }
  return form[p.key] as unknown[][]
}

/** 空矩阵判定：[]、[[]] 或全部为空行均视为可选未填。 */
function matrixEmpty(p: Parameter): boolean {
  const rows = matrixValue(p)
  return rows.length === 0 || rows.every((r) => !Array.isArray(r) || r.length === 0)
}

function matrixCols(p: Parameter): number {
  return matrixValue(p)[0]?.length ?? 0
}

function matrixAddRow(p: Parameter) {
  matrixRows(p).push(new Array(matrixCols(p)).fill(''))
}

function matrixDelRow(p: Parameter) {
  const rows = matrixRows(p)
  if (rows.length > 1) rows.pop()
}

function matrixAddCol(p: Parameter) {
  const rows = matrixRows(p)
  if (rows.length === 0) rows.push([])
  for (const row of rows) (row as unknown[]).push('')
}

function matrixDelCol(p: Parameter) {
  const rows = matrixRows(p)
  if (matrixCols(p) > 1) {
    for (const row of rows) (row as unknown[]).pop()
  }
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
        <!-- 语义化标量组头（V11）：组名 + 实时合计 + 组约束错误 -->
        <div v-if="groupStarts.has(p.key)" class="param-group-label" :data-test="`param-group-${groupByParam[p.key].name}`">
          {{ groupByParam[p.key].message }}
          <span class="param-group-sum">当前合计：{{ groupSumText(groupByParam[p.key]) }}</span>
          <span
            v-if="groupErrors[groupByParam[p.key].name]"
            class="param-group-error"
            :data-test="`param-group-error-${groupByParam[p.key].name}`"
          >
            {{ groupErrors[groupByParam[p.key].name] }}
          </span>
        </div>
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
        <!-- matrix / timeseries：可编辑表格（工具栏增删行/列，空矩阵=可选未填） -->
        <div v-else-if="p.type === 'matrix' || p.type === 'timeseries'" class="param-control matrix-wrap" :data-test="`param-${p.key}`">
          <div class="matrix-toolbar">
            <span class="matrix-shape" :data-test="`matrix-shape-${p.key}`">{{ matrixValue(p).length }} 行 × {{ matrixCols(p) }} 列</span>
            <el-button-group size="small">
              <el-button size="small" :data-test="`matrix-add-row-${p.key}`" @click="matrixAddRow(p)">添加一行</el-button>
              <el-button size="small" :data-test="`matrix-del-row-${p.key}`" :disabled="matrixValue(p).length <= 1" @click="matrixDelRow(p)">删除末行</el-button>
              <el-button size="small" :data-test="`matrix-add-col-${p.key}`" :disabled="matrixCols(p) >= MATRIX_MAX_COLS" @click="matrixAddCol(p)">添加一列</el-button>
              <el-button size="small" :data-test="`matrix-del-col-${p.key}`" :disabled="matrixCols(p) <= 1" @click="matrixDelCol(p)">删除末列</el-button>
            </el-button-group>
          </div>
          <el-table :data="matrixValue(p)" size="small" border>
            <el-table-column
              v-for="(_, ci) in matrixCols(p)"
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
.matrix-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.matrix-shape {
  color: #909399;
  font-size: 12px;
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
.param-group-label {
  font-size: 12px;
  color: #409eff;
  margin-bottom: 4px;
}
.param-group-sum {
  color: #909399;
  margin-left: 8px;
}
.param-group-error {
  color: #f56c6c;
  margin-left: 8px;
}
.param-actions {
  margin-top: 8px;
}
</style>
