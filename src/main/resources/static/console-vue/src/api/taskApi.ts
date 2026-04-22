import { requestApi } from '../shared/api/request'
import { toPagedResponse } from '../shared/contracts/api'
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
  const result = await requestApi<Paged<TaskItem>>({
    path: '/api/tt-register/task/list',
    params: {
      page: query.page,
      size: query.size,
      status: query.status && query.status !== 'ALL' ? query.status : undefined,
      serverIp: query.serverIp,
      phoneId: query.phoneId
    }
  })
  if (result.success && result.data) {
    result.data = toPagedResponse(result.data, { page: query.page, size: query.size })
  }
  return result as ApiResp<Paged<TaskItem>>
}

export async function stopTask(taskId: string) {
  return requestApi<unknown>({
    path: `/api/tt-register/stop/${encodeURIComponent(taskId)}`,
    method: 'POST',
    auditAction: 'task.stop'
  })
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

export interface TaskCreatePayload {
  taskType: 'FAKE_EMAIL' | 'REAL_EMAIL'
  serverIp: string
  phoneId: string
  targetCount?: number
  tiktokVersionDir?: string
  country?: string
  sdk?: string
  imagePath?: string
  gaidTag?: string
  dynamicIpChannel?: string
  staticIpChannel?: string
  biz?: string
  appiumServer?: string
  xrayServerIp?: string
}

export async function updateTask(payload: TaskUpdatePayload) {
  return requestApi<unknown>({
    path: '/api/tt-register/task/update',
    method: 'POST',
    data: payload,
    auditAction: 'task.update'
  })
}

export async function resumeTask(taskId: string) {
  return requestApi<unknown>({
    path: `/api/tt-register/task/resume/${encodeURIComponent(taskId)}`,
    method: 'POST',
    auditAction: 'task.resume'
  })
}

export async function createTask(payload: TaskCreatePayload) {
  return requestApi<unknown>({
    path: '/api/tt-register/task/create',
    method: 'POST',
    data: payload,
    auditAction: 'task.create'
  })
}

export async function deleteTask(taskId: string) {
  return requestApi<unknown>({
    path: `/api/tt-register/task/${encodeURIComponent(taskId)}`,
    method: 'DELETE',
    auditAction: 'task.delete'
  })
}
