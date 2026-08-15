import { config } from '@vue/test-utils'
import ElementPlus from 'element-plus'

/**
 * Vitest 全局测试环境：注册 Element Plus（与 main.ts 全量注册对齐），
 * 避免单测中出现 "Failed to resolve component: el-*"。
 */
config.global.plugins = [ElementPlus]
