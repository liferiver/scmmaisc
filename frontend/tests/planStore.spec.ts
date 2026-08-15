import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePlanStore } from '@/stores/planStore'
import type { ParamSet } from '@/types'

/**
 * planStore 单元测试（T035，FR-011）：localStorage CRUD、刷新后恢复、
 * 损坏数据容错（Edge Case）、配额不足回滚。
 */
const STORAGE_KEY = 'param_sets'

function input(partial?: Partial<Omit<ParamSet, 'id' | 'savedAt'>>): Omit<ParamSet, 'id' | 'savedAt'> {
  return {
    scenarioId: 5,
    scenarioName: 'EOQ 经济订货批量',
    name: '方案A',
    params: { annual_demand: 10000 },
    seed: 42,
    result: { runId: 1, status: 'COMPLETED', params: {}, seed: 42, durationMs: 10, outputs: [], steps: [] },
    ...partial,
  }
}

describe('planStore 方案持久化（T036）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('空存储：初始为空列表', () => {
    const store = usePlanStore()
    expect(store.sets).toEqual([])
  })

  it('save 追加方案并写入 localStorage（自动补 id 与 savedAt）', () => {
    const store = usePlanStore()
    const saved = store.save(input())
    expect(saved.id).toBeTruthy()
    expect(saved.savedAt).toBeTruthy()
    expect(store.sets).toHaveLength(1)
    const raw = JSON.parse(localStorage.getItem(STORAGE_KEY)!)
    expect(raw).toHaveLength(1)
    expect(raw[0].name).toBe('方案A')
    expect(raw[0].scenarioId).toBe(5)
  })

  it('多次保存生成互不相同的 id', () => {
    const store = usePlanStore()
    const a = store.save(input({ name: 'A' }))
    const b = store.save(input({ name: 'B' }))
    expect(a.id).not.toBe(b.id)
    expect(store.sets).toHaveLength(2)
  })

  it('重新创建 store（模拟刷新页面）后仍可从 localStorage 恢复', () => {
    const store = usePlanStore()
    store.save(input({ name: '方案A' }))
    store.save(input({ name: '方案B', seed: 7 }))

    setActivePinia(createPinia())
    const fresh = usePlanStore()
    expect(fresh.sets).toHaveLength(2)
    expect(fresh.sets.map((s) => s.name)).toEqual(['方案A', '方案B'])
    expect(fresh.sets[1].seed).toBe(7)
  })

  it('损坏 JSON：容错降级为空列表且不抛异常', () => {
    localStorage.setItem(STORAGE_KEY, '{oops not json')
    const store = usePlanStore()
    expect(store.sets).toEqual([])
  })

  it('非数组结构：降级为空列表', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ a: 1 }))
    const store = usePlanStore()
    expect(store.sets).toEqual([])
  })

  it('结构非法的元素被过滤，合法元素保留', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify([
        { id: 'broken' },
        { name: 'no-id' },
        {
          id: 'ok',
          name: '合法方案',
          scenarioId: 5,
          scenarioName: 'EOQ',
          params: {},
          seed: 1,
          result: {},
          savedAt: '2026-08-13T10:00:00.000Z',
        },
      ]),
    )
    const store = usePlanStore()
    expect(store.sets).toHaveLength(1)
    expect(store.sets[0].id).toBe('ok')
  })

  it('remove 删除方案并同步存储', () => {
    const store = usePlanStore()
    const a = store.save(input({ name: 'A' }))
    store.save(input({ name: 'B' }))
    store.remove(a.id)
    expect(store.sets.map((s) => s.name)).toEqual(['B'])
    const raw = JSON.parse(localStorage.getItem(STORAGE_KEY)!)
    expect(raw).toHaveLength(1)
    expect(raw[0].name).toBe('B')
  })

  it('配额不足：抛出可读提示并回滚，恢复后可正常保存', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })
    const store = usePlanStore()
    expect(() => store.save(input())).toThrow(/存储空间不足/)
    expect(store.sets).toHaveLength(0)

    spy.mockRestore()
    expect(store.save(input({ name: '恢复后' })).id).toBeTruthy()
    expect(store.sets).toHaveLength(1)
  })
})
