/**
 * API 请求封装
 */
import type { ApiResponse } from '@/types'

const API_BASE = '/api/plugin/l4d2'

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
 * 通用请求方法
 */
async function request<T>(
  url: string,
  options?: RequestInit
): Promise<T> {
  const token = getAuthToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options?.headers as Record<string, string> | undefined)
  }
  if (token && !headers['Authorization']) {
    headers['Authorization'] = `Bearer ${token}`
  }

  const fullUrl = `${API_BASE}${url}`
  console.log('[request] start', options?.method || 'GET', fullUrl)
  const response = await fetch(fullUrl, {
    headers,
    ...options
  })
  console.log('[request] response', fullUrl, response.status)

  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }

  const result: ApiResponse<T> = await response.json()
  console.log('[request] parsed', fullUrl, result.code, result.message)
  
  if (result.code !== 200) {
    throw new Error(result.message || 'Request failed')
  }

  return result.data
}

/**
 * GET 请求
 * 注意：undefined / null 值会被跳过，避免序列化为字符串 "undefined" 污染后端过滤条件
 */
export function get<T>(url: string, params?: Record<string, any>): Promise<T> {
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

  return request<T>(`${url}${queryString}`)
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
            reject(new Error(result.message || 'Upload failed'))
          }
        } catch (e) {
          reject(new Error('Invalid response'))
        }
      } else {
        reject(new Error(`HTTP error! status: ${xhr.status}`))
      }
    })

    xhr.addEventListener('error', () => reject(new Error('Network error')))
    xhr.open('POST', `${API_BASE}${url}`)
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
