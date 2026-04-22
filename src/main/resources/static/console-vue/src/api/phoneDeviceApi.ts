import { requestApi } from '../shared/api/request'
import type { ApiResp } from './statsApi'

export interface PhoneDeviceItem {
  id: number
  serverIp: string
  phoneId: string
  deviceStatus: 'IDLE' | 'BUSY' | 'OFFLINE' | 'DISABLED'
  note?: string
  createdAt?: string
  updatedAt?: string
}

export interface PhoneDeviceQuery {
  serverIp?: string
  phoneId?: string
  deviceStatus?: string
  page?: number
  size?: number
}

export interface PhoneDeviceListData {
  list: PhoneDeviceItem[]
  total: number
  page: number
  size: number
}

export async function getPhoneDeviceList(query: PhoneDeviceQuery = {}) {
  return requestApi<PhoneDeviceListData>({
    path: '/api/tt-phone-device/list',
    params: {
      serverIp: query.serverIp,
      phoneId: query.phoneId,
      deviceStatus: query.deviceStatus,
      page: query.page || 1,
      size: query.size || 20
    }
  })
}

export async function getRegisterPhoneDeviceList(query: PhoneDeviceQuery = {}) {
  return requestApi<PhoneDeviceListData>({
    path: '/api/tt-phone-device/register-list',
    params: {
      serverIp: query.serverIp,
      phoneId: query.phoneId,
      deviceStatus: query.deviceStatus,
      page: query.page || 1,
      size: query.size || 20
    }
  })
}

export async function createPhoneDevice(payload: Partial<PhoneDeviceItem>) {
  return requestApi<PhoneDeviceItem>({
    path: '/api/tt-phone-device/create',
    method: 'POST',
    data: payload,
    auditAction: 'phoneDevice.create'
  })
}

export async function batchCreatePhoneDeviceByRule(payload: {
  phonePrefix: string
  serverIp: string
  count: number
  note?: string
}) {
  return requestApi<{
    insertCount: number
    skipCount: number
    insertedPhoneIds: string[]
  }>({
    path: '/api/tt-phone-device/batch-create-by-rule',
    method: 'POST',
    data: payload,
    auditAction: 'phoneDevice.batchCreateByRule'
  })
}

export async function countPhoneDeviceByServer(serverIps: string[]) {
  return requestApi<Record<string, number>>({
    path: '/api/tt-phone-device/count-by-server',
    method: 'POST',
    data: { serverIps }
  })
}

export async function updatePhoneDevice(id: number, payload: Partial<PhoneDeviceItem>) {
  return requestApi<PhoneDeviceItem>({
    path: `/api/tt-phone-device/update/${id}`,
    method: 'PUT',
    data: payload,
    auditAction: 'phoneDevice.update'
  })
}

export async function deletePhoneDevice(id: number) {
  return requestApi<unknown>({
    path: `/api/tt-phone-device/delete/${id}`,
    method: 'DELETE',
    auditAction: 'phoneDevice.delete'
  })
}

export type { ApiResp }
