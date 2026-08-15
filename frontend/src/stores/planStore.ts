import { defineStore } from 'pinia'
import type { ParamSet } from '@/types'

/**
 * planStore（T036，FR-011/FR-012）：已保存方案（参数快照 + 结果）的 localStorage
 * 持久化 —— `param_sets` 键。刷新/重进平台后仍可访问（US3 验收 3）。
 * 损坏数据容错：JSON 解析失败或结构非法时降级为空列表（T035）。
 */
const STORAGE_KEY = 'param_sets'

function readSets(): ParamSet[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (s): s is ParamSet =>
        !!s && typeof s === 'object' && typeof (s as ParamSet).id === 'string' && typeof (s as ParamSet).name === 'string',
    )
  } catch {
    return []
  }
}

function genId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export const usePlanStore = defineStore('plan', {
  state: () => ({ sets: readSets() }),

  actions: {
    /** 保存方案（追加 + 持久化；存储失败回滚并抛出提示）。 */
    save(input: Omit<ParamSet, 'id' | 'savedAt'>): ParamSet {
      const set: ParamSet = { ...input, id: genId(), savedAt: new Date().toISOString() }
      this.sets.push(set)
      try {
        this.persist()
      } catch (err) {
        this.sets.pop()
        throw err
      }
      return set
    },

    /** 删除方案（同步持久化）。 */
    remove(id: string) {
      this.sets = this.sets.filter((s) => s.id !== id)
      this.persist()
    },

    /** 写回 localStorage；配额不足时抛出可读提示（Edge Case：导出/保存失败重试）。 */
    persist() {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(this.sets))
      } catch {
        throw new Error('本地存储失败：浏览器存储空间不足，请删除部分方案后重试')
      }
    },
  },
})
