<script setup lang="ts">
import type { Conclusion, ConclusionSection } from '@/types'

/**
 * 三维结论卡片（T023）：理论/实操/前沿三段 × 4 要素渲染（D3 契约 12 要素）；
 * 缺失要素显示占位「——」，结论为空显示空态，conclusionNote 展示降级说明。
 */
defineProps<{ conclusion: Conclusion | null; note?: string | null }>()

const SECTIONS: { key: keyof Conclusion; title: string; labels: { key: string; label: string }[] }[] = [
  {
    key: 'theory',
    title: '理论维度',
    labels: [
      { key: 'coreModel', label: '核心模型' },
      { key: 'derivation', label: '推导要点' },
      { key: 'assumptions', label: '假设边界' },
      { key: 'knowledgeLocation', label: '知识定位' },
    ],
  },
  {
    key: 'practice',
    title: '实操维度',
    labels: [
      { key: 'paramBusiness', label: '参数业务含义' },
      { key: 'caseBenchmark', label: '案例对标' },
      { key: 'simRealityGap', label: '仿真与现实差距' },
      { key: 'suggestions', label: '落地建议' },
    ],
  },
  {
    key: 'frontier',
    title: '前沿维度',
    labels: [
      { key: 'industry', label: '产业前沿' },
      { key: 'academic', label: '学术前沿' },
      { key: 'studentAdvice', label: '学习建议' },
      { key: 'voteItem', label: '兴趣投票' },
    ],
  },
]

/** 单要素取值：缺失/空白 → 占位符（缺要素不崩溃，T040 边界）。 */
function valueOf(section: ConclusionSection | undefined, key: string): string {
  const v = section?.[key]
  return v && v.trim() ? v.trim() : '——'
}
</script>

<template>
  <div class="conclusion-cards" data-test="conclusion-cards">
    <el-alert
      v-if="note"
      :title="note"
      type="warning"
      :closable="false"
      class="conclusion-note"
      data-test="conclusion-note"
    />
    <el-empty
      v-if="!conclusion"
      description="讨论完成后生成三维结论"
      :image-size="60"
      data-test="conclusion-empty"
    />
    <el-row v-else :gutter="12">
      <el-col v-for="sec in SECTIONS" :key="sec.key" :xs="24" :md="8">
        <el-card shadow="never" class="conclusion-card" data-test="conclusion-card">
          <template #header>{{ sec.title }}</template>
          <div v-for="item in sec.labels" :key="item.key" class="conclusion-item" data-test="conclusion-item">
            <div class="item-label">{{ item.label }}</div>
            <div class="item-value">{{ valueOf(conclusion[sec.key], item.key) }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.conclusion-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.conclusion-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  margin-bottom: 12px;
}
.conclusion-item {
  padding: 6px 0;
  border-bottom: 1px dashed #f0f2f5;
}
.conclusion-item:last-child {
  border-bottom: none;
}
.item-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 2px;
}
.item-value {
  font-size: 14px;
  color: #303133;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
