import { defineStore } from 'pinia'
import { getChapters, getScenarioDetail, getScenarios } from '@/api/scenarios'
import type { Chapter, ScenarioDetail, ScenarioSummary } from '@/types'

/**
 * scenarioStore（T013）：章节/场景目录与当前场景定义（C1/C2/C3）。
 */
export const useScenarioStore = defineStore('scenario', {
  state: () => ({
    chapters: [] as Chapter[],
    /** 当前选中的场景详情（C3）。 */
    current: null as ScenarioDetail | null,
    loading: false,
    error: '',
  }),

  actions: {
    async fetchChapters() {
      this.loading = true
      this.error = ''
      try {
        this.chapters = await getChapters()
      } catch (err) {
        this.error = err instanceof Error ? err.message : '加载章节失败'
        throw err
      } finally {
        this.loading = false
      }
    },

    /** 按 moduleId 拉取场景详情。 */
    async fetchCurrent(moduleId: string) {
      this.loading = true
      this.error = ''
      try {
        this.current = await getScenarioDetail(moduleId)
      } catch (err) {
        this.error = err instanceof Error ? err.message : '加载场景失败'
        throw err
      } finally {
        this.loading = false
      }
    },

    /** 全部场景概要（目录页分组用）。 */
    async fetchAllScenarios(): Promise<ScenarioSummary[]> {
      return getScenarios()
    },

    async fetchScenariosByChapter(chapterId: number): Promise<ScenarioSummary[]> {
      return getScenarios(chapterId)
    },
  },
})
