<script setup lang="ts">
import type { Utterance } from '@/types'

/**
 * 发言流组件（T029，US2）：角色徽标（头像/专属色/名字）+ 内容渲染，
 * 与 StepTimeline 同视觉语言（Element Plus 徽章 + 浅色底块）；
 * DiscussionView 按轮次分组后传入单轮发言。
 */
const ROLES: Record<string, { name: string; color: string; avatar: string }> = {
  JING: { name: '景同学', color: '#409eff', avatar: '景' },
  HUO: { name: '霍教授', color: '#9254de', avatar: '霍' },
  LIU: { name: '柳经理', color: '#fa8c16', avatar: '柳' },
  ZHONG: { name: '钟同学', color: '#13c2c2', avatar: '钟' },
}

defineProps<{ utterances: Utterance[] }>()

function roleOf(code: string) {
  return ROLES[code] ?? { name: code, color: '#909399', avatar: code.slice(0, 1) }
}
</script>

<template>
  <div class="utterance-list" data-test="utterance-list">
    <div
      v-for="u in utterances"
      :key="u.id"
      class="utterance-item"
      data-test="utterance-item"
    >
      <div class="utterance-head" data-test="utterance-role">
        <el-avatar :size="28" :style="{ background: roleOf(u.agentRole).color }" data-test="utterance-avatar">
          {{ roleOf(u.agentRole).avatar }}
        </el-avatar>
        <span class="utterance-name">{{ roleOf(u.agentRole).name }}</span>
        <span class="utterance-code">{{ u.agentRole }}</span>
      </div>
      <p class="utterance-content">{{ u.content }}</p>
    </div>
    <el-empty v-if="utterances.length === 0" description="暂无发言" :image-size="50" />
  </div>
</template>

<style scoped>
.utterance-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.utterance-item {
  padding: 10px 12px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fafafa;
}
.utterance-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.utterance-name {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}
.utterance-code {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #909399;
}
.utterance-content {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
