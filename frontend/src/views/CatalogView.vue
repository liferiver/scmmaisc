<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useScenarioStore } from '@/stores/scenarioStore'
import type { ScenarioSummary } from '@/types'

/**
 * 场景目录页（T018，US1）：章节折叠面板 → 场景卡片列表。
 * 加载/空/错误三态；依赖标注；点击卡片进入详情路由。
 */
const router = useRouter()
const store = useScenarioStore()

const loading = ref(false)
const error = ref('')
const scenarios = ref<ScenarioSummary[]>([])

/** 按章节分组（保持 C2 返回顺序）。 */
const grouped = computed(() => {
  const map = new Map<number, ScenarioSummary[]>()
  for (const s of scenarios.value) {
    const list = map.get(s.chapterId) ?? []
    list.push(s)
    map.set(s.chapterId, list)
  }
  return map
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    await store.fetchChapters()
    scenarios.value = await store.fetchAllScenarios()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function openScenario(moduleId: string) {
  router.push(`/scenarios/${moduleId}`)
}

/**
 * 难度标签文案（FR-016）：intro/basic/advanced/comprehensive 四级。
 */
function difficultyText(d: string) {
  return d === 'intro' ? '入门' : d === 'basic' ? '基础' : d === 'comprehensive' ? '综合' : '进阶'
}

function difficultyType(d: string) {
  return d === 'intro' ? 'success' : d === 'basic' ? 'warning' : d === 'comprehensive' ? 'primary' : 'danger'
}

onMounted(load)
</script>

<template>
  <div class="catalog">
    <!-- 加载态 -->
    <div v-if="loading" data-test="catalog-loading">
      <el-skeleton :rows="8" animated />
    </div>

    <!-- 错误态：可重试 -->
    <div v-else-if="error" data-test="catalog-error" class="catalog-state">
      <el-empty :description="error">
        <el-button type="primary" data-test="catalog-retry" @click="load">重试</el-button>
      </el-empty>
    </div>

    <!-- 空态 -->
    <div v-else-if="store.chapters.length === 0" data-test="catalog-empty" class="catalog-state">
      <el-empty description="暂无场景数据" />
    </div>

    <!-- 内容态：章节折叠面板 → 场景卡片 -->
    <div v-else data-test="catalog-content">
      <h2 class="catalog-title">场景目录</h2>
      <el-collapse>
        <el-collapse-item
          v-for="chapter in store.chapters"
          :key="chapter.id"
          :name="chapter.code"
        >
          <template #title>
            <span class="chapter-title">{{ chapter.name }}</span>
            <el-tag size="small" type="info" class="chapter-count">
              {{ chapter.scenarioCount }} 个场景
            </el-tag>
          </template>
          <div class="scenario-grid">
            <div
              v-for="s in grouped.get(chapter.id) ?? []"
              :key="s.id"
              class="scenario-card"
              data-test="scenario-card"
              role="button"
              tabindex="0"
              @click="openScenario(s.moduleId)"
              @keydown.enter="openScenario(s.moduleId)"
            >
              <div class="scenario-module">{{ s.moduleId }}</div>
              <div class="scenario-name">{{ s.name }}</div>
              <el-tag size="small" :type="difficultyType(s.difficulty)">
                {{ difficultyText(s.difficulty) }}
              </el-tag>
              <div v-if="s.isRolePlay" class="scenario-roleplay">角色扮演</div>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
  </div>
</template>

<style scoped>
.catalog-title {
  margin: 0 0 16px;
  font-size: 20px;
  color: #303133;
}
.chapter-title {
  font-weight: 600;
}
.chapter-count {
  margin-left: 12px;
}
.scenario-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
  padding: 4px 0;
}
.scenario-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px 14px;
  cursor: pointer;
  background: #fff;
  transition: box-shadow 0.2s;
}
.scenario-card:hover {
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  border-color: #409eff;
}
.scenario-module {
  font-size: 12px;
  color: #909399;
  font-family: Consolas, monospace;
}
.scenario-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin: 4px 0 8px;
}
.scenario-roleplay {
  margin-top: 6px;
  font-size: 12px;
  color: #e6a23c;
}
.catalog-state {
  padding: 40px 0;
}
</style>
