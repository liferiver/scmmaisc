<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useDiscussionStore } from '@/stores/discussionStore'
import { downloadFile } from '@/utils/report'
import type { DiscussionHistoryItem } from '@/types'

/**
 * 讨论历史页（T041，US4）：D6 历史列表 —— 状态标签/场景名/时间/轮次/发言数 + 分页；
 * 点击回看（复用 DiscussionView 只读模式：?sessionId=&readonly=1 按 D3 展示）；
 * 「导出报告」D7 下载 Markdown 附件（仅 COMPLETED，复用 report.ts downloadFile）。
 */
const router = useRouter()
const store = useDiscussionStore()

const page = ref(1)
const pageSize = 20

const items = computed(() => store.history?.items ?? [])
const total = computed(() => store.history?.total ?? 0)

/** D6：拉取指定页（首次加载/翻页共用）。 */
async function load(targetPage: number) {
  page.value = targetPage
  await store.fetchHistory(targetPage, pageSize)
}

function onPageChange(p: number) {
  load(p)
}

/** 回看：跳转讨论页只读模式（D3 展示，不发起不轮询）。 */
function replay(item: DiscussionHistoryItem) {
  router.push(`/scenarios/${item.moduleId}/discussion?sessionId=${item.sessionId}&readonly=1`)
}

/** D7：导出 Markdown 实验报告附录（仅 COMPLETED 可导出；文件名来自服务端 Content-Disposition）。 */
async function onExport(item: DiscussionHistoryItem) {
  try {
    const { filename, content } = await store.exportMarkdown(item.sessionId)
    downloadFile(filename, content, 'text/markdown;charset=utf-8')
    ElMessage.success('实验报告附录已导出')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '导出失败，请稍后重试')
  }
}

/** 后端 LocalDateTime ISO 串 → 本地可读时间。 */
function formatTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

/** 轮次文案：roundNo=6 表示结论已生成（完成态），0 表示尚未开始。 */
function roundText(item: DiscussionHistoryItem): string {
  if (item.roundNo >= 6) return '已完成 5 轮'
  return `${item.roundNo} / 5 轮`
}

function statusType(status: string): 'info' | 'primary' | 'success' | 'danger' | 'warning' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'ABANDONED') return 'warning'
  if (status === 'QUEUED') return 'info'
  return 'primary'
}

onMounted(() => {
  load(1)
})
</script>

<template>
  <div class="history-view">
    <div class="history-header">
      <el-button link data-test="history-back" @click="router.push('/')">← 返回目录</el-button>
      <h2 class="history-title">讨论历史</h2>
      <span class="history-hint">基于本次运行的四人研讨记录，可回看与导出实验报告附录</span>
    </div>

    <!-- 加载失败：提示 + 重试 -->
    <el-alert
      v-if="store.error && !store.history"
      :title="store.error"
      type="error"
      :closable="false"
      class="history-alert"
      data-test="history-error"
    >
      <template #default>
        <el-button size="small" data-test="history-retry" @click="load(1)">重新加载</el-button>
      </template>
    </el-alert>

    <!-- 空态 -->
    <el-empty
      v-else-if="!store.historyLoading && total === 0"
      description="暂无讨论记录，完成一次模拟运行后点击「开始讨论」即可创建"
      data-test="history-empty"
      :image-size="90"
    />

    <!-- 列表 + 分页 -->
    <template v-else>
      <div v-loading="store.historyLoading" class="history-list">
        <el-card
          v-for="item in items"
          :key="item.sessionId"
          shadow="never"
          class="history-card"
          data-test="history-item"
        >
          <div class="history-row">
            <div class="history-main">
              <div class="history-name">
                <span class="history-module">{{ item.moduleId }}</span>
                <span class="history-scenario" data-test="history-scenario">{{ item.scenarioName }}</span>
              </div>
              <div class="history-meta">
                <el-tag :type="statusType(item.status)" size="small" data-test="history-status">{{ item.status }}</el-tag>
                <span class="history-meta-item">{{ roundText(item) }}</span>
                <span class="history-meta-item">{{ item.utteranceCount }} 条发言</span>
                <span class="history-meta-item history-time" data-test="history-time">{{ formatTime(item.createdAt) }}</span>
              </div>
            </div>
            <div class="history-actions">
              <el-button size="small" data-test="history-replay" @click="replay(item)">回看</el-button>
              <el-button
                size="small"
                type="primary"
                plain
                :disabled="item.status !== 'COMPLETED'"
                data-test="history-export"
                @click="onExport(item)"
              >
                导出报告
              </el-button>
            </div>
          </div>
        </el-card>
      </div>

      <el-pagination
        v-if="total > pageSize"
        class="history-pagination"
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        data-test="history-pagination"
        @current-change="onPageChange"
      />
    </template>
  </div>
</template>

<style scoped>
.history-view {
  max-width: 960px;
  margin: 0 auto;
  padding: 16px;
}
.history-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.history-title {
  margin: 0;
  font-size: 20px;
  color: #303133;
}
.history-hint {
  color: #909399;
  font-size: 13px;
}
.history-alert {
  margin-bottom: 12px;
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 120px;
}
.history-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
}
.history-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.history-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 240px;
}
.history-name {
  display: flex;
  align-items: center;
  gap: 8px;
}
.history-module {
  font-family: Consolas, monospace;
  color: #909399;
  font-size: 13px;
  background: #f4f4f5;
  border-radius: 4px;
  padding: 1px 6px;
}
.history-scenario {
  font-weight: 600;
  color: #303133;
}
.history-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.history-meta-item {
  color: #606266;
  font-size: 13px;
}
.history-time {
  color: #909399;
}
.history-actions {
  display: flex;
  gap: 8px;
}
.history-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
