/**
 * API 请求封装（demo）
 *
 * 关键点：
 *   - API_BASE 必须与后端控制器路径前缀对齐：/api/plugin/{gameCode}
 *   - 鉴权 token 优先取 Wujie 注入的 props.auth.token，其次取同源 localStorage
 *   - 统一响应格式：{ code, message, data, timestamp }，code !== 200 抛错
 *   - GET 请求的 undefined / null 值会被跳过，避免污染后端过滤条件
 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

const API_BASE = '/api/plugin/mygame'

function getAuthToken(): string | null {
  // 1. 优先取 Wujie 注入的 token（主应用 PluginContainer 传递）
  try {
    const wujieProps = (window as any)?.$wujie?.props
    const wujieToken = wujieProps?.auth?.token || wujieProps?.token
    if (typeof wujieToken === 'string' && wujieToken) {
      return wujieToken
    }
  } catch { /* ignore */ }
  // 2. 同源 localStorage（dev 模式与主应用共享同一 origin）
  try {
    const ls = localStorage.getItem('token')
    if (ls) return ls
  } catch { /* ignore */ }
  return null
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const token = getAuthToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options?.headers as Record<string, string> | undefined)
  }
  if (token && !headers['Authorization']) {
    headers['Authorization'] = `Bearer ${token}`
  }

  const response = await fetch(`${API_BASE}${url}`, { headers, ...options })
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }

  const result: ApiResponse<T> = await response.json()
  if (result.code !== 200) {
    throw new Error(result.message || 'Request failed')
  }
  return result.data
}

export function get<T>(url: string, params?: Record<string, any>): Promise<T> {
  const queryString = params
    ? '?' + new URLSearchParams(
        Object.entries(params).reduce((acc, [key, value]) => {
          if (value !== undefined && value !== null) {
            acc[key] = String(value)
          }
          return acc
        }, {} as Record<string, string>)
      ).toString()
    : ''
  return request<T>(`${url}${queryString}`)
}

export function post<T>(url: string, data?: any): Promise<T> {
  return request<T>(url, {
    method: 'POST',
    body: data ? JSON.stringify(data) : undefined
  })
}

export function put<T>(url: string, data?: any): Promise<T> {
  return request<T>(url, {
    method: 'PUT',
    body: data ? JSON.stringify(data) : undefined
  })
}

export function del<T>(url: string): Promise<T> {
  return request<T>(url, { method: 'DELETE' })
}

export default { get, post, put, del }
