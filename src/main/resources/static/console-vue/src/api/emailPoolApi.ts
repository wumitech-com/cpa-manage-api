import http from './http'
import { requestApi } from '../shared/api/request'
import type { ApiResp } from './statsApi'

export interface EmailPoolItem {
  id: number
  email: string
  password: string
  clientId?: string
  refreshToken?: string
  channel?: string
  usageStatus: 'UNUSED' | 'IN_USE' | 'USED'
  note?: string
  createdAt?: string
  updatedAt?: string
}

export interface EmailPoolListData {
  list: EmailPoolItem[]
  total: number
  page: number
  size: number
}

export async function getEmailPoolList(query: {
  email?: string
  channel?: string
  usageStatus?: string
  page?: number
  size?: number
}) {
  return requestApi<EmailPoolListData>({
    path: '/api/tt-email-pool/list',
    params: {
      email: query.email,
      channel: query.channel,
      usageStatus: query.usageStatus,
      page: query.page || 1,
      size: query.size || 20
    },
    timeoutMs: 60000
  })
}

export async function importEmailTxt(file: File, channel?: string) {
  const form = new FormData()
  form.append('file', file)
  if (channel?.trim()) {
    form.append('channel', channel.trim())
  }
  const { data } = await http.post<ApiResp<{
    insertCount: number
    updateCount: number
    skipCount: number
    errors?: string[]
  }>>('/api/tt-email-pool/import-txt', form, {
    timeout: 300000
  })
  return data
}
