import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types'

/**
 * axios 实例：统一 baseURL（开发期经 Vite 代理 /api → 8080）、
 * 统一 ApiResult 解包与错误提示（对齐 contracts/api.md）。
 */

/** 带业务错误详情的错误（detail 为服务端 400 时的具体原因列表等）。 */
export interface ApiError extends Error {
  code?: number
  detail?: unknown
  httpStatus?: number
}

const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResult<unknown>
    // 业务层错误（HTTP 200 但 code != 0，理论上不发生，防御性处理）
    if (body && typeof body.code === 'number' && body.code !== 0) {
      const err = new Error(body.message || '请求失败') as ApiError
      err.code = body.code
      err.detail = body.data
      return Promise.reject(err)
    }
    return resp
  },
  (error: AxiosError<ApiResult<unknown>>) => {
    const body = error.response?.data
    const err = new Error(
      (body && body.message) || error.message || '网络错误，请稍后重试',
    ) as ApiError
    err.code = body?.code
    err.detail = body?.data
    err.httpStatus = error.response?.status
    return Promise.reject(err)
  },
)

/** 请求成功回调：默认统一错误提示（宪法 III：错误体验一致）。 */
function handleError(err: unknown): never {
  if (err instanceof Error && !(err as ApiError).httpStatus) {
    ElMessage.error(err.message)
  }
  throw err
}

export async function get<T>(url: string, params?: object): Promise<T> {
  try {
    const resp = await http.get<ApiResult<T>>(url, { params })
    return resp.data.data
  } catch (err) {
    return handleError(err)
  }
}

export async function post<T>(url: string, body?: object): Promise<T> {
  try {
    const resp = await http.post<ApiResult<T>>(url, body)
    return resp.data.data
  } catch (err) {
    return handleError(err)
  }
}

export async function del<T>(url: string, params?: object): Promise<T> {
  try {
    const resp = await http.delete<ApiResult<T>>(url, { params })
    return resp.data.data
  } catch (err) {
    return handleError(err)
  }
}

export default http
