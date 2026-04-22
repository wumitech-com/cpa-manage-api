import { requestApi } from '../shared/api/request'
import type { ApiResp } from './statsApi'

export interface DispatchCandidate {
  id: number
  taskId: string
  taskType: 'FAKE_EMAIL' | 'REAL_EMAIL'
  serverIp: string
  phoneId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'STOPPED'
  targetCount: number
  tiktokVersionDir?: string
  country?: string
  sdk?: string
  imagePath?: string
  dynamicIpChannel?: string
  staticIpChannel?: string
  updatedAt?: string
}

export interface DispatchCandidateQuery {
  statuses: Array<'STOPPED' | 'COMPLETED' | 'FAILED'>
  serverIp?: string
  phoneId?: string
  page: number
  size: number
}

export interface DispatchCandidateResp {
  list: DispatchCandidate[]
  total: number
  page: number
  size: number
}

export interface BatchUpdateDispatchPayload {
  taskIds: number[]
  taskType: 'FAKE_EMAIL' | 'REAL_EMAIL'
  tiktokVersionDir?: string
  country?: string
  sdk?: string
  imagePath?: string
  dynamicIpChannel?: string
  staticIpChannel?: string
  continuous: boolean
  targetCount?: number
}

export interface BatchUpdateDispatchResp {
  batchId: string
  successCount: number
  skipCount: number
  details: Array<{ id: number; result: string; message: string }>
}

export interface DispatchLogItem {
  id: number
  batchId: string
  taskId?: string
  registerTaskId?: number
  serverIp?: string
  phoneId?: string
  oldStatus?: string
  newStatus?: string
  taskType?: string
  targetCount?: number
  isContinuous?: number
  result: string
  message?: string
  createdAt: string
}

export interface DispatchLogQuery {
  taskId?: string
  serverIp?: string
  phoneId?: string
  page: number
  size: number
}

export interface DispatchLogResp {
  list: DispatchLogItem[]
  total: number
  page: number
  size: number
}

export async function listDispatchCandidates(payload: DispatchCandidateQuery) {
  return requestApi<DispatchCandidateResp>({
    path: '/api/register-dispatch/candidates',
    method: 'POST',
    data: payload,
    auditAction: 'registerDispatch.candidates'
  })
}

export async function batchUpdateDispatch(payload: BatchUpdateDispatchPayload) {
  return requestApi<BatchUpdateDispatchResp>({
    path: '/api/register-dispatch/batch-update',
    method: 'POST',
    data: payload,
    auditAction: 'registerDispatch.batchUpdate'
  })
}

export async function listDispatchLogs(payload: DispatchLogQuery) {
  return requestApi<DispatchLogResp>({
    path: '/api/register-dispatch/logs',
    method: 'POST',
    data: payload,
    auditAction: 'registerDispatch.logs'
  })
}

export type { ApiResp }
