<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ConclusionCards from '@/components/ConclusionCards.vue'
import UtteranceList from '@/components/UtteranceList.vue'
import { useDiscussionStore } from '@/stores/discussionStore'

/**
 * 多智能体讨论页（T022，US1）：入口来自运行结果页「开始讨论」（runId 随路由传入）。
 * 状态渲染：QUEUED 排队位置 → RUNNING 轮次进度（第 X/5 轮，roundNo=6 结论生成中）+
 * 发言流（D3 增量刷新）→ COMPLETED 三维结论卡片 + 回看；FAILED/ABANDONED 提示并可重新发起；
 * D5 放弃（确认弹窗 + 失败提示）；D4 学生插话「我也想问…」（US3，第 2~4 轮开放，T035）；
 * 历史回看只读模式（?sessionId=&readonly=1，T041）：仅按 D3 展示快照，不发起不轮询。
 */
const props = defineProps<{ moduleId: string }>()

const route = useRoute()
const router = useRouter()
const store = useDiscussionStore()

const abandoning = ref(false)
const question = ref('')
const submitting = ref(false)

const runId = computed(() => Number(route.query.runId ?? 0))
const scenarioName = computed(() => store.record?.scenarioName ?? '')
const rounds = computed(() => store.record?.rounds ?? [])

/** 历史回看只读模式：会话已终态，按 D3 记录展示（T041）。 */
const readonly = computed(() => route.query.readonly === '1')
const viewSessionId = computed(() => Number(route.query.sessionId ?? 0))
/** 只读模式下状态/轮次以记录快照为准（D3），否则取轮询中的 D2。 */
const status = computed(() => (readonly.value ? (store.record?.status ?? null) : store.status))
const roundNo = computed(() => (readonly.value ? (store.record?.roundNo ?? 0) : store.roundNo))

/** 轮次进度文案：0=启动中、1..5=第 X/5 轮、6=结论生成中（D2 契约）。 */
const roundText = computed(() => {
  if (status.value === 'QUEUED') return '排队中'
  if (roundNo.value >= 6) return '结论生成中'
  if (roundNo.value >= 1) return `第 ${roundNo.value} / 5 轮`
  return '讨论启动中'
})

async function onStart() {
  if (!runId.value) {
    ElMessage.warning('缺少运行记录，请先完成模拟运行')
    return
  }
  await store.start(runId.value)
}

/** D5：放弃讨论（确认弹窗；取消不动作）。 */
async function onAbandon() {
  if (store.sessionId == null) return
  try {
    await ElMessageBox.confirm('放弃后已生成的发言会保留，但讨论将终止。确认放弃？', '放弃讨论', {
      type: 'warning',
      confirmButtonText: '放弃',
      cancelButtonText: '取消',
    })
  } catch {
    return // 用户取消
  }
  abandoning.value = true
  try {
    await store.abandon()
  } finally {
    abandoning.value = false
  }
}

/** D4 插话可用：非只读且讨论进行中且第 2~4 轮（问题需在后续轮次得到回应；末轮/结论阶段不开放）。 */
const questionEnabled = computed(
  () => !readonly.value && status.value === 'RUNNING' && roundNo.value >= 2 && roundNo.value <= 4,
)

/** D4：提交学生插话（空白提示；超长截断提示；成功清空输入）。 */
async function onSubmitQuestion() {
  const content = question.value.trim()
  if (!content) {
    ElMessage.warning('问题不能为空')
    return
  }
  submitting.value = true
  try {
    const resp = await store.submitQuestion(content)
    question.value = ''
    if (resp?.truncated) {
      ElMessage.warning('问题超过 200 字，已截断后提交')
    } else {
      ElMessage.success('已提交，角色们将在下一轮回应')
    }
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '提交失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

function backToRun() {
  router.push(`/scenarios/${props.moduleId}/run`)
}

/** 历史入口（T042，US4）。 */
function goHistory() {
  router.push('/discussions')
}

/** 只读回看：返回历史列表；正常模式：返回运行页。 */
function back() {
  if (readonly.value) goHistory()
  else backToRun()
}

onMounted(() => {
  // 历史回看：仅按 D3 展示快照（不发起、不轮询）
  if (readonly.value && viewSessionId.value > 0) {
    store.fetchRecord(viewSessionId.value)
    return
  }
  // 页面刷新/回退时恢复：会话已存在且未终态 → 继续轮询；否则按 runId 新建
  if (store.sessionId != null && store.running) {
    store.startPolling(store.sessionId)
  } else if (runId.value > 0) {
    onStart()
  }
})

onUnmounted(() => {
  store.clearPolling()
})
</script>

<template>
  <div class="discussion-view">
    <!-- 头部 -->
    <div class="discussion-header">
      <el-button link data-test="discussion-back" @click="back">{{ readonly ? '← 返回历史' : '← 返回运行页' }}</el-button>
      <el-button v-if="!readonly" link data-test="discussion-history" @click="goHistory">讨论历史</el-button>
      <span class="discussion-module">{{ store.record?.moduleId ?? props.moduleId }}</span>
      <h2 class="discussion-title">{{ scenarioName || '多智能体研讨' }}</h2>
      <el-tag v-if="status" :type="status === 'COMPLETED' ? 'success' : status === 'FAILED' ? 'danger' : 'primary'" data-test="discussion-status">
        {{ status }}
      </el-tag>
    </div>

    <!-- 只读回看加载失败 -->
    <el-alert
      v-if="readonly && store.error"
      :title="store.error"
      type="error"
      :closable="false"
      class="discussion-alert"
      data-test="discussion-error"
    />

    <!-- 发起失败（D1 校验错误等） -->
    <el-alert
      v-else-if="store.error && !store.running && store.sessionId == null"
      :title="store.error"
      type="error"
      :closable="false"
      class="discussion-alert"
      data-test="discussion-error"
    >
      <template #default>
        <el-button size="small" data-test="discussion-retry" @click="onStart">重新发起</el-button>
      </template>
    </el-alert>

    <!-- 初始态：等待发起 -->
    <el-empty
      v-else-if="!readonly && store.sessionId == null"
      description="基于本次模拟运行发起四人研讨（理论/实操/学生视角）"
      data-test="discussion-idle"
      :image-size="90"
    >
      <el-button type="primary" data-test="discussion-start" @click="onStart">开始讨论</el-button>
    </el-empty>

    <!-- 只读回看：记录加载中 -->
    <el-skeleton v-else-if="readonly && !store.record" :rows="4" animated class="discussion-card" data-test="discussion-loading" />

    <template v-else>
      <!-- QUEUED：排队位置（FR-015 有界并发） -->
      <el-result
        v-if="status === 'QUEUED'"
        icon="info"
        title="排队中"
        :sub-title="`当前排队位置：第 ${store.queuePosition ?? 1} 位，讨论即将开始`"
        data-test="discussion-queue"
      >
        <template #extra>
          <el-button v-if="!readonly" type="danger" plain :loading="abandoning" data-test="discussion-abandon" @click="onAbandon">
            放弃讨论
          </el-button>
        </template>
      </el-result>

      <!-- RUNNING：轮次进度 + 发言流（D3 增量刷新） -->
      <template v-else-if="status === 'RUNNING'">
        <el-card shadow="never" class="discussion-card" data-test="discussion-progress">
          <div class="progress-line">
            <span class="progress-text">{{ roundText }}</span>
            <el-progress :percentage="Math.min(Math.round((roundNo / 5) * 100), 100)" :stroke-width="10" class="progress-bar" />
            <el-button v-if="!readonly" type="danger" plain size="small" :loading="abandoning" data-test="discussion-abandon" @click="onAbandon">
              放弃讨论
            </el-button>
          </div>
        </el-card>

        <!-- US3 学生插话：第 2~4 轮开放（问题需在后续轮次被回应，D4） -->
        <el-card v-if="questionEnabled" shadow="never" class="discussion-card question-card" data-test="discussion-question">
          <template #header>
            <span class="round-title">我也想问…</span>
          </template>
          <div class="question-line">
            <el-input
              v-model="question"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              resize="none"
              placeholder="向研讨小组提问，下一轮发言将优先回应"
              data-test="question-input"
              :disabled="submitting"
            />
            <el-button type="primary" :loading="submitting" data-test="question-submit" @click="onSubmitQuestion">
              提问
            </el-button>
          </div>
        </el-card>
        <div class="rounds">
          <el-card v-for="r in rounds" :key="r.roundNo" shadow="never" class="round-card" data-test="discussion-round">
            <template #header>
              <span class="round-title">第 {{ r.roundNo }} 轮 · {{ r.title }}</span>
            </template>
            <UtteranceList :utterances="r.utterances" />
          </el-card>
        </div>
      </template>

      <!-- COMPLETED：三维结论 + 五轮回看 -->
      <template v-else-if="status === 'COMPLETED'">
        <el-card shadow="never" class="discussion-card" data-test="discussion-completed">
          <template #header>
            <div class="completed-header">
              <span>讨论完成 · 三维结论</span>
              <el-button size="small" data-test="discussion-again" @click="back">{{ readonly ? '返回历史' : '返回运行页' }}</el-button>
            </div>
          </template>
          <ConclusionCards :conclusion="store.record?.conclusion ?? null" :note="store.record?.conclusionNote ?? null" />
        </el-card>
        <div class="rounds">
          <el-card v-for="r in rounds" :key="r.roundNo" shadow="never" class="round-card" data-test="discussion-round">
            <template #header>
              <span class="round-title">第 {{ r.roundNo }} 轮 · {{ r.title }}</span>
            </template>
            <UtteranceList :utterances="r.utterances" />
          </el-card>
        </div>
      </template>

      <!-- FAILED / ABANDONED：提示 + 重新发起 -->
      <el-result
        v-else-if="status === 'FAILED'"
        icon="error"
        title="讨论执行失败"
        :sub-title="store.error || 'LLM 服务暂不可用，请稍后重新发起'"
        data-test="discussion-failed"
      >
        <template #extra>
          <el-button v-if="!readonly" type="primary" data-test="discussion-restart" @click="onStart">重新发起</el-button>
          <el-button v-if="readonly" data-test="discussion-back" @click="back">返回历史</el-button>
        </template>
      </el-result>
      <el-result
        v-else-if="status === 'ABANDONED'"
        icon="warning"
        title="讨论已放弃"
        sub-title="已生成的发言已保留，可重新发起讨论"
        data-test="discussion-abandoned"
      >
        <template #extra>
          <el-button v-if="!readonly" type="primary" data-test="discussion-restart" @click="onStart">重新发起</el-button>
          <el-button data-test="discussion-back" @click="back">{{ readonly ? '返回历史' : '返回运行页' }}</el-button>
        </template>
      </el-result>
    </template>
  </div>
</template>

<style scoped>
.discussion-view {
  max-width: 960px;
  margin: 0 auto;
  padding: 16px;
}
.discussion-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.discussion-module {
  font-family: Consolas, monospace;
  color: #909399;
  font-size: 14px;
}
.discussion-title {
  margin: 0;
  font-size: 20px;
  color: #303133;
  flex: 1;
  min-width: 200px;
}
.discussion-alert {
  margin-bottom: 12px;
}
.discussion-card {
  margin-bottom: 16px;
}
.progress-line {
  display: flex;
  align-items: center;
  gap: 12px;
}
.progress-text {
  font-weight: 600;
  color: #303133;
  white-space: nowrap;
}
.progress-bar {
  flex: 1;
}
.rounds {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.round-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
}
.round-title {
  font-weight: 600;
}
.completed-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.question-line {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.question-line .el-textarea {
  flex: 1;
}
.question-line .el-button {
  margin-top: 4px;
}
</style>
