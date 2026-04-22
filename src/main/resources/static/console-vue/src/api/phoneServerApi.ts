import { requestApi } from '../shared/api/request'
import type { ApiResp } from './statsApi'

export interface PhoneServerItem {
  id: number
  serverIp: string
  xrayServerIp?: string
  appiumServer?: string
  maxConcurrency: number
  status: number
  usageScope?: 'NONE' | 'REGISTER' | 'RETENTION' | 'MIXED'
  note?: string
  createdAt?: string
  updatedAt?: string
}

export interface PhoneServerListData {
  list: PhoneServerItem[]
  total: number
  page: number
  size: number
}

export interface PhoneServerQuery {
  serverIp?: string
  status?: number
  usageScope?: string
  page?: number
  size?: number
}

export interface PhoneServerUpsert {
  serverIp: string
  xrayServerIp?: string
  appiumServer?: string
  maxConcurrency?: number
  status?: number
  usageScope?: 'NONE' | 'REGISTER' | 'RETENTION' | 'MIXED'
  note?: string
}

export async function getPhoneServerList(query: PhoneServerQuery = {}) {
  return requestApi<PhoneServerListData>({
    path: '/api/tt-phone-server/list',
    params: {
      serverIp: query.serverIp,
      status: query.status,
      usageScope: query.usageScope,
      page: query.page || 1,
      size: query.size || 20
    }
  })
}

export async function getRegisterServerList() {
  return requestApi<PhoneServerItem[]>({
    path: '/api/tt-phone-server/register-list'
  })
}

export async function createPhoneServer(payload: PhoneServerUpsert) {
  return requestApi<PhoneServerItem>({
    path: '/api/tt-phone-server/create',
    method: 'POST',
    data: payload,
    auditAction: 'phoneServer.create'
  })
}

export async function batchCreatePhoneServer(payload: PhoneServerUpsert[]) {
  return requestApi<{
    insertCount: number
    skipCount: number
    skippedServerIps: string[]
  }>({
    path: '/api/tt-phone-server/batch-create',
    method: 'POST',
    data: payload,
    auditAction: 'phoneServer.batchCreate'
  })
}

export async function updatePhoneServer(id: number, payload: Partial<PhoneServerUpsert>) {
  return requestApi<PhoneServerItem>({
    path: `/api/tt-phone-server/update/${id}`,
    method: 'PUT',
    data: payload,
    auditAction: 'phoneServer.update'
  })
}

export async function enablePhoneServer(id: number) {
  return requestApi<unknown>({
    path: `/api/tt-phone-server/enable/${id}`,
    method: 'PUT',
    auditAction: 'phoneServer.enable'
  })
}

export async function disablePhoneServer(id: number) {
  return requestApi<unknown>({
    path: `/api/tt-phone-server/disable/${id}`,
    method: 'PUT',
    auditAction: 'phoneServer.disable'
  })
}

export type { ApiResp }
