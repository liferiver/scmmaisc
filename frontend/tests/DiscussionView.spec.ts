import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { MessageBoxData } from 'element-plus'
import type { Conclusion, DiscussionRecord, DiscussionStatusValue } from '@/types'

/**
 * DiscussionView 测试（T024/T036）：状态渲染 —— QUEUED 排队位置 / RUNNING 轮次进度+发言流 /
 * COMPLETED 三维结论+回看 / FAILED 重新发起 / 初始态 D1 发起 / D5 放弃确认弹窗；
 * US3 插话交互（提交 / 空提示 / 截断提示）。
 * 用 vi.hoisted 共享 storeMock 与 routeMock，对齐 ScenarioDetailView.spec 的 mock 工厂模式。
 */
const { storeMock, routeMock, pushMock } = vi.hoisted(() => {
  const storeMock = {
    sessionId: null as number | null,
    runId: null as number | null,
    status: null as DiscussionStatusValue | null,
    roundNo: 0,
    queuePosition: null as number | null,
    utteranceCount: 0,
    questionCount: 0,
    abandonable: false,
    record: null as DiscussionRecord | null,
    running: false,
    timer: null as ReturnType<typeof setInterval> | null,
    error: '',
    start: vi.fn(),
    startPolling: vi.fn(),
    poll: vi.fn(),
    fetchRecord: vi.fn(),
    submitQuestion: vi.fn(),
    abandon: vi.fn(),
    stopPolling: vi.fn(),
    clearPolling: vi.fn(),
    reset: vi.fn(),
  }
  const routeMock = { query: {} as Record<string, string> }
  const pushMock = vi.fn()
  return { storeMock, routeMock, pushMock }
})

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('@/stores/discussionStore', () => ({
  useDiscussionStore: () => storeMock,
}))

import DiscussionView from '@/views/DiscussionView.vue'

/** D3 契约 12 要素结论。 */
function fullConclusion(): Conclusion {
  return {
    theory: {
      coreModel: '经济订货批量模型',
      derivation: 'Q*=√(2DS/H)',
      assumptions: '需求恒定',
      knowledgeLocation: '第2章',
    },
    practice: {
      paramBusiness: '年需求量',
      caseBenchmark: '京东案例',
      simRealityGap: '仿真简化',
      suggestions: '分批次采购',
    },
    frontier: {
      industry: '智能补货',
      academic: '绿色供应链',
      studentAdvice: '建议学习',
      voteItem: '投票：智慧物流',
    },
  }
}

/** D3 完整记录：2 轮 × 2 发言。 */
function fullRecord(status: DiscussionStatusValue): DiscussionRecord {
  return {
    sessionId: 55,
    status,
    roundNo: status === 'COMPLETED' ? 6 : 2,
    conclusionNote: null,
    snapshot: { params: { annual_demand: 10000 }, seed: 42, outputs: [] },
    rounds: [
      {
        roundNo: 1,
        title: '现象解读',
        utterances: [
          { id: 1, agentRole: 'JING', content: '景同学发言一', replyQuestionId: null },
          { id: 2, agentRole: 'HUO', content: '霍教授发言一', replyQuestionId: null },
        ],
      },
      {
        roundNo: 2,
        title: '深度剖析',
        utterances: [
          { id: 3, agentRole: 'LIU', content: '柳经理发言二', replyQuestionId: null },
          { id: 4, agentRole: 'ZHONG', content: '钟同学发言二', replyQuestionId: null },
        ],
      },
    ],
    questions: [],
    conclusion: status === 'COMPLETED' ? fullConclusion() : null,
    moduleId: 'CH2-003',
    scenarioName: 'EOQ经济订货批量',
  }
}

describe('DiscussionView 状态渲染', () => {
  beforeEach(() => {
    Object.assign(storeMock, {
      sessionId: null,
      runId: null,
      status: null,
      roundNo: 0,
      queuePosition: null,
      utteranceCount: 0,
      questionCount: 0,
      abandonable: false,
      record: null,
      running: false,
      error: '',
    })
    vi.clearAllMocks()
    routeMock.query = { runId: '9' }
  })

  it('QUEUED：展示排队位置与状态标签（FR-015 有界并发）', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'QUEUED'
    storeMock.queuePosition = 2
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    const queue = wrapper.find('[data-test="discussion-queue"]')
    expect(queue.exists()).toBe(true)
    expect(queue.text()).toContain('第 2 位')
    expect(wrapper.find('[data-test="discussion-status"]').text()).toBe('QUEUED')
    expect(wrapper.find('[data-test="discussion-abandon"]').exists()).toBe(true)
  })

  it('RUNNING：轮次进度文案 + 发言流渲染；页面恢复继续轮询（D2/D3）', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'RUNNING'
    storeMock.roundNo = 2
    storeMock.running = true
    storeMock.record = fullRecord('RUNNING')
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    expect(wrapper.find('[data-test="discussion-progress"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('第 2 / 5 轮')
    expect(wrapper.findAll('[data-test="discussion-round"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-test="utterance-item"]')).toHaveLength(4)
    expect(wrapper.text()).toContain('景同学')
    expect(wrapper.text()).toContain('柳经理发言二')
    // 会话已存在且运行中 → 直接恢复轮询（不重新发起）
    expect(storeMock.startPolling).toHaveBeenCalledWith(55)
    expect(storeMock.start).not.toHaveBeenCalled()
  })

  it('COMPLETED：三维结论 12 要素 + 五轮回看 + 返回运行页', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'COMPLETED'
    storeMock.roundNo = 6
    storeMock.record = fullRecord('COMPLETED')
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    expect(wrapper.find('[data-test="discussion-completed"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="conclusion-cards"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test="conclusion-item"]')).toHaveLength(12)
    expect(wrapper.findAll('[data-test="discussion-round"]')).toHaveLength(2)

    await wrapper.find('[data-test="discussion-back"]').trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/scenarios/CH2-003/run')
  })

  it('US2 角色徽标：四角色名字/编码/头像齐全且区分（T029）', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'RUNNING'
    storeMock.roundNo = 1
    storeMock.running = true
    storeMock.record = fullRecord('RUNNING')
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    const roles = wrapper.findAll('[data-test="utterance-role"]')
    expect(roles).toHaveLength(4)
    const text = roles.map((n) => n.text()).join('|')
    for (const name of ['景同学', '霍教授', '柳经理', '钟同学']) {
      expect(text).toContain(name)
    }
    for (const code of ['JING', 'HUO', 'LIU', 'ZHONG']) {
      expect(text).toContain(code)
    }
    expect(wrapper.findAll('[data-test="utterance-avatar"]')).toHaveLength(4)
  })

  it('初始态：显示开始按钮，点击调用 store.start（D1）', async () => {
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()
    expect(wrapper.find('[data-test="discussion-idle"]').exists()).toBe(true)

    await wrapper.find('[data-test="discussion-start"]').trigger('click')
    await flushPromises()
    expect(storeMock.start).toHaveBeenCalledWith(9)
  })

  it('放弃讨论：确认弹窗后调用 store.abandon；取消不调用（D5）', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'RUNNING'
    storeMock.running = true
    storeMock.abandonable = true
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as unknown as MessageBoxData)
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    await wrapper.find('[data-test="discussion-abandon"]').trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(storeMock.abandon).toHaveBeenCalledTimes(1)

    confirmSpy.mockRejectedValue('cancel')
    await wrapper.find('[data-test="discussion-abandon"]').trigger('click')
    await flushPromises()
    expect(storeMock.abandon).toHaveBeenCalledTimes(1) // 取消不动作
  })

  it('FAILED：错误提示 + 重新发起', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'FAILED'
    storeMock.error = '讨论执行失败，请重新发起'
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    expect(wrapper.find('[data-test="discussion-failed"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('讨论执行失败')

    await wrapper.find('[data-test="discussion-restart"]').trigger('click')
    await flushPromises()
    expect(storeMock.start).toHaveBeenCalledWith(9)
  })

  it('US3 插话：第 2 轮起显示「我也想问…」，提交成功调用接口并清空输入（D4）', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'RUNNING'
    storeMock.roundNo = 2
    storeMock.running = true
    storeMock.record = fullRecord('RUNNING')
    const successSpy = vi.spyOn(ElMessage, 'success')
    storeMock.submitQuestion.mockResolvedValue({ questionId: 77, roundNo: 2, truncated: false })
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    const input = wrapper.find('[data-test="question-input"]')
    expect(input.exists()).toBe(true)
    expect(wrapper.find('[data-test="discussion-question"]').text()).toContain('我也想问')
    await input.setValue('安全库存怎么定？')
    await wrapper.find('[data-test="question-submit"]').trigger('click')
    await flushPromises()

    expect(storeMock.submitQuestion).toHaveBeenCalledWith('安全库存怎么定？')
    expect((wrapper.find('[data-test="question-input"]').element as HTMLTextAreaElement).value).toBe('')
    expect(successSpy).toHaveBeenCalledWith('已提交，角色们将在下一轮回应')
  })

  it('US3 插话：空白提交 → 提示「问题不能为空」，不调用接口', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'RUNNING'
    storeMock.roundNo = 2
    storeMock.running = true
    storeMock.record = fullRecord('RUNNING')
    const warningSpy = vi.spyOn(ElMessage, 'warning')
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    await wrapper.find('[data-test="question-input"]').setValue('   ')
    await wrapper.find('[data-test="question-submit"]').trigger('click')
    await flushPromises()

    expect(warningSpy).toHaveBeenCalledWith('问题不能为空')
    expect(storeMock.submitQuestion).not.toHaveBeenCalled()
  })

  it('US3 插话：后端返回 truncated=true → 截断提示并清空输入', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'RUNNING'
    storeMock.roundNo = 3
    storeMock.running = true
    storeMock.record = fullRecord('RUNNING')
    const warningSpy = vi.spyOn(ElMessage, 'warning')
    storeMock.submitQuestion.mockResolvedValue({ questionId: 78, roundNo: 3, truncated: true })
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()

    await wrapper.find('[data-test="question-input"]').setValue('超过两百字的问题')
    await wrapper.find('[data-test="question-submit"]').trigger('click')
    await flushPromises()

    expect(storeMock.submitQuestion).toHaveBeenCalledWith('超过两百字的问题')
    expect(warningSpy).toHaveBeenCalledWith(expect.stringContaining('截断'))
    expect((wrapper.find('[data-test="question-input"]').element as HTMLTextAreaElement).value).toBe('')
  })

  it('US3 插话：第 1 轮与末轮不显示输入框（问题需在后续轮次被回应）', async () => {
    storeMock.sessionId = 55
    storeMock.status = 'RUNNING'
    storeMock.roundNo = 1
    storeMock.running = true
    storeMock.record = fullRecord('RUNNING')
    const wrapper = mount(DiscussionView, { props: { moduleId: 'CH2-003' } })
    await flushPromises()
    expect(wrapper.find('[data-test="discussion-question"]').exists()).toBe(false)

    storeMock.roundNo = 5
    await flushPromises()
    expect(wrapper.find('[data-test="discussion-question"]').exists()).toBe(false)
  })
})
