import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由骨架（对齐 contracts/ui.md）：
 * - /                        场景目录（US1）
 * - /scenarios/:moduleId     场景说明页（US1）
 * - /scenarios/:moduleId/run 参数面板 + 运行 + 结果（US2）
 * - /scenarios/:moduleId/discussion 多智能体讨论（US3）
 * - /discussions            讨论历史 + 导出（US4）
 * - /compare                 多方案对比 + 导出（US3）
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'catalog',
      component: () => import('@/views/CatalogView.vue'),
    },
    {
      path: '/scenarios/:moduleId',
      name: 'scenario-detail',
      component: () => import('@/views/ScenarioDetailView.vue'),
      props: true,
    },
    {
      path: '/scenarios/:moduleId/run',
      name: 'scenario-run',
      component: () => import('@/views/RunView.vue'),
      props: true,
    },
    {
      path: '/scenarios/:moduleId/discussion',
      name: 'scenario-discussion',
      component: () => import('@/views/DiscussionView.vue'),
      props: true,
    },
    {
      path: '/discussions',
      name: 'discussion-history',
      component: () => import('@/views/DiscussionHistoryView.vue'),
    },
    {
      path: '/compare',
      name: 'compare',
      component: () => import('@/views/CompareView.vue'),
    },
  ],
})

export default router
