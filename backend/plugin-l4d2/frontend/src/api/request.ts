/**
 * API 请求封装
 */
import type { ApiResponse } from '@/types'

const API_BASE = '/api/plugin/l4d2'
/** 主应用 API 基址（轮询主应用任务中心等宿主接口用） */
const MAIN_API_BASE = '/api'

/**
 * 获取鉴权 Token
 * 优先取 Wujie 注入的 token（兼容 props.auth.token 和旧版 props.token）；
 * 其次取同源 localStorage（与主前端共享同一 origin）。
 */
function getAuthToken(): string | null {
  try {
    const wujieProps = (window as any)?.$wujie?.props
    // 主应用 PluginContainer 传递 auth: { token, user }，兼容旧版顶层 token
    const wujieToken = wujieProps?.auth?.token || wujieProps?.token
    if (typeof wujieToken === 'string' && wujieToken) {
      return wujieToken
    }
  } catch { /* ignore */ }
  try {
    const ls = localStorage.getItem('token')
    if (ls) return ls
  } catch { /* ignore */ }
  return null
}

/**
 * 带 HTTP/业务码的错误对象
 * code 为响应体业务码（如 1550 未配置仓库）或 HTTP 状态码（响应体不可解析时）
 */
export class ApiError extends Error {
  code: number

  constructor(message: string, code: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/** 非 2xx 响应：尽力读取统一响应体里的 {code,message}，替代裸 "HTTP error! status: xxx" */
async function errorFromResponse(response: Response): Promise<ApiError> {
  let code = response.status
  let message = `HTTP error! status: ${response.status}`
  try {
    const body = await response.json()
    if (typeof body?.code === 'number') code = body.code
    if (body?.message) message = body.message
  } catch { /* 响应体非 JSON（如网关错误页），保留 HTTP 兜底文案 */ }
  return new ApiError(message, code)
}

/**
 * 通用请求方法
 */
async function request<T>(
  url: string,
  options?: RequestInit,
  base: string = API_BASE
): Promise<T> {
  const token = getAuthToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options?.headers as Record<string, string> | undefined)
  }
  if (token && !headers['Authorization']) {
    headers['Authorization'] = `Bearer ${token}`
  }

  const fullUrl = `${base}${url}`
  console.log('[request] start', options?.method || 'GET', fullUrl)
  const response = await fetch(fullUrl, {
    headers,
    ...options
  })
  console.log('[request] response', fullUrl, response.status)

  if (!response.ok) {
    throw await errorFromResponse(response)
  }

  const result: ApiResponse<T> = await response.json()
  console.log('[request] parsed', fullUrl, result.code, result.message)

  if (result.code !== 200) {
    throw new ApiError(result.message || 'Request failed', result.code)
  }

  return result.data
}

/**
 * GET 请求
 * 注意：undefined / null 值会被跳过，避免序列化为字符串 "undefined" 污染后端过滤条件
 */
export function get<T>(url: string, params?: Record<string, any>): Promise<T> {
  return getWithBase<T>(url, params, API_BASE)
}

/**
 * GET 请求（主应用 /api 基址）
 * 用于访问宿主主应用接口（如任务中心 /tasks/{taskId}），鉴权头与插件请求一致。
 */
export function getMain<T>(url: string, params?: Record<string, any>): Promise<T> {
  return getWithBase<T>(url, params, MAIN_API_BASE)
}

function getWithBase<T>(url: string, params: Record<string, any> | undefined, base: string): Promise<T> {
  const queryString = params
    ? '?' + new URLSearchParams(
        Object.entries(params).reduce((acc, [key, value]) => {
          // 跳过 undefined / null，避免后端把 "undefined" 当成真实过滤值
          if (value !== undefined && value !== null) {
            acc[key] = String(value)
          }
          return acc
        }, {} as Record<string, string>)
      ).toString()
    : ''

  return request<T>(`${url}${queryString}`, undefined, base)
}

/**
 * POST 请求
 */
export function post<T>(url: string, data?: any): Promise<T> {
  return request<T>(url, {
    method: 'POST',
    body: data ? JSON.stringify(data) : undefined
  })
}

/**
 * PUT 请求
 */
export function put<T>(url: string, data?: any): Promise<T> {
  return request<T>(url, {
    method: 'PUT',
    body: data ? JSON.stringify(data) : undefined
  })
}

/**
 * DELETE 请求
 */
export function del<T>(url: string, data?: Record<string, any>): Promise<T> {
  return request<T>(url, {
    method: 'DELETE',
    body: data ? JSON.stringify(data) : undefined
  })
}

/**
 * 上传文件
 */
export async function upload<T>(url: string, file: File, onProgress?: (percent: number) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    const formData = new FormData()
    formData.append('file', file)


    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable && onProgress) {
        const percent = Math.round((e.loaded / e.total) * 100)
        onProgress(percent)
      }
    })

    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const result: ApiResponse<T> = JSON.parse(xhr.responseText)
          if (result.code === 200) {
            resolve(result.data)
          } else {
            reject(new ApiError(result.message || 'Upload failed', result.code))
          }
        } catch (e) {
          reject(new Error('Invalid response'))
        }
      } else {
        // 非 2xx：尽力解析统一响应体，回退 HTTP 状态文案
        let code = xhr.status
        let message = `HTTP error! status: ${xhr.status}`
        try {
          const body = JSON.parse(xhr.responseText)
          if (typeof body?.code === 'number') code = body.code
          if (body?.message) message = body.message
        } catch { /* 非 JSON 响应体 */ }
        reject(new ApiError(message, code))
      }
    })

    xhr.addEventListener('error', () => reject(new Error('Network error')))
    xhr.open('POST', `${API_BASE}${url}`)
    const token = getAuthToken()
    if (token) {
      // 与 request() 保持一致：XHR 上传必须携带鉴权头，否则 Spring Security 403
      xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    }
    xhr.send(formData)
  })
}

// 导出 API 对象
export const api = {
  get,
  post,
  put,
  delete: del,
  upload
}

export default api
