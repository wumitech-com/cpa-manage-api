import http from '../../api/http'
import type { ApiResponse } from '../contracts/api'
import { reportPerf } from '../observability/perf'

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

interface RequestOptions {
  path: string
  method?: HttpMethod
  params?: Record<string, string | number | boolean | undefined>
  data?: unknown
  auditAction?: string
  timeoutMs?: number
}

function toQueryString(params?: Record<string, string | number | boolean | undefined>) {
  if (!params) return ''
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    query.set(key, String(value))
  })
  const text = query.toString()
  return text ? `?${text}` : ''
}

export async function requestApi<T>(options: RequestOptions): Promise<ApiResponse<T>> {
  const method = options.method || 'GET'
  const url = `${options.path}${toQueryString(options.params)}`
  const begin = performance.now()
  try {
    const { data } = await http.request<ApiResponse<T>>({
      method,
      url,
      data: options.data,
      timeout: options.timeoutMs,
      headers: options.auditAction ? { 'X-Console-Action': options.auditAction } : undefined
    })
    await reportPerf({
      name: options.auditAction || `${method} ${options.path}`,
      durationMs: Math.round(performance.now() - begin),
      success: Boolean(data?.success),
      path: options.path
    })
    return data
  } catch (error) {
    await reportPerf({
      name: options.auditAction || `${method} ${options.path}`,
      durationMs: Math.round(performance.now() - begin),
      success: false,
      path: options.path
    })
    throw error
  }
}
