import http from './http'
import type { ApiResp } from './statsApi'

export interface TaskItem {
  taskId: string
  status?: string
  serverIp?: string
  phoneId?: string
  startTime?: string
  endTime?: string
  createdAt?: string
  updatedAt?: string
  country?: string
  sdk?: string
  imagePath?: string
  gaidTag?: string
  dynamicIpChannel?: string
  staticIpChannel?: string
  biz?: string
  targetCount?: number
}

export interface Paged<T> {
  list: T[]
  total: number
  page?: number
  size?: number
}

export interface TaskQuery {
  page: number
  size: number
  status?: string
  serverIp?: string
  phoneId?: string
}

export async function getTaskList(query: TaskQuery) {
  const params = new URLSearchParams()
  params.set('page', String(query.page))
  params.set('size', String(query.size))
  if (query.status && query.status !== 'ALL') params.set('status', query.status)
  if (query.serverIp) params.set('serverIp', query.serverIp)
  if (query.phoneId) params.set('phoneId', query.phoneId)
  const { data } = await http.get<ApiResp<Paged<TaskItem>>>(`/api/tt-register/task/list?${params.toString()}`)
  return data
}

export async function stopTask(taskId: string) {
  const { data } = await http.post<ApiResp<unknown>>(`/api/tt-register/stop/${encodeURIComponent(taskId)}`)
  return data
}

export interface TaskUpdatePayload {
  taskId: string
  country?: string
  sdk?: string
  imagePath?: string
  gaidTag?: string
  dynamicIpChannel?: string
  staticIpChannel?: string
  biz?: string
  targetCount?: string | number
}

export async function updateTask(payload: TaskUpdatePayload) {
  const { data } = await http.post<ApiResp<unknown>>('/api/tt-register/task/update', payload)
  return data
}

export async function resumeTask(taskId: string) {
  const { data } = await http.post<ApiResp<unknown>>(`/api/tt-register/task/resume/${encodeURIComponent(taskId)}`)
  return data
}
