export interface ApiResponse<T = unknown> {
  success: boolean
  message?: string
  data?: T
}

export interface PagedResponse<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export function toPagedResponse<T>(
  raw: { list?: T[]; total?: number; page?: number; size?: number },
  fallback: { page: number; size: number }
): PagedResponse<T> {
  const list = Array.isArray(raw.list) ? raw.list : []
  const total = Number(raw.total ?? list.length) || 0
  const page = Number(raw.page ?? fallback.page) || fallback.page
  const size = Number(raw.size ?? fallback.size) || fallback.size
  return { list, total, page, size }
}
