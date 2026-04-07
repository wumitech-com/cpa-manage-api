import http from './http'
import type { ApiResp } from './statsApi'

export interface DeviceInspectResp {
  tunnelCommand?: string
  adbConnectCommand?: string
  serverIp?: string
  adbPort?: string | number
  tips?: string[]
}

export interface RetentionRecord {
  taskId?: string
  phoneServerIp?: string
  phoneId?: string
  accountRegisterId?: number
  gaid?: string
  scriptSuccess?: boolean | number
  backupSuccess?: boolean | number
  createdAt?: string
}

export interface RetentionData {
  total: number
  successRate?: number
  scriptSuccessCount?: number
  backupSuccessCount?: number
  backupRate?: number
  retention2faSuccess?: number
  retentionLogout?: number
  cohortTotal?: number
  cohortBlocked?: number
  cohortLogout?: number
  cohortBlockRate?: number
  allCohortTotal?: number
  allCohortBlocked?: number
  allCohortLogout?: number
  allCohortBlockRate?: number
  records: RetentionRecord[]
}

export async function inspectDevice(phoneId: string, gaid: string) {
  const { data } = await http.post<ApiResp<DeviceInspectResp>>('/api/tt-register/device/inspect', { phoneId, gaid })
  return data
}

export async function getRetentionRecords(date: string, page: number, size: number) {
  const query = new URLSearchParams()
  if (date) query.set('date', date)
  query.set('page', String(page))
  query.set('size', String(size))
  const { data } = await http.get<ApiResp<RetentionData>>(`/api/statistics/retention-records?${query.toString()}`)
  return data
}
