import http from './http'
import { requestApi } from '../shared/api/request'
import { toPagedResponse } from '../shared/contracts/api'
import type { ApiResp } from './statsApi'

export interface AccountItem {
  id?: number
  phoneId?: string
  createdAt?: string
  status?: string
  newEmailBindSuccess?: number
  newEmailBindStatus?: string
  newEmail?: string
  username?: string
  password?: string
  email?: string
  accountType?: string
  note?: string
  ip?: string
  state?: string
  city?: string
  country?: string
  model?: string
  androidVersion?: string
  authenticatorKey?: string
  registerSuccess?: boolean
  is2faSetupSuccess?: number
}

export interface WindowItem {
  username?: string
  fanDate?: string
  nurtureDate?: string
  nurtureStrategy?: string
  shopStatus?: string
  registerIp?: string
  registerEnv?: string
  note?: string
}

export interface PagedResp<T> {
  list: T[]
  total: number
  page?: number
  size?: number
  totalAccurate?: boolean
}

export interface ImportResult {
  success: boolean
  message?: string
  insertCount?: number
  updateCount?: number
  skipCount?: number
  errors?: string[]
}

export interface AccountDateSummary {
  registerSuccessCount: number
  twofaSuccessCount: number
  twofaRate: number
}

/** 与当前筛选条件一致的结果集内，各维度数量（用于占比） */
export interface AccountFilterStatsData {
  total: number
  register: { success: number; fail: number }
  key: { success: number; fail: number }
  mature: { mature: number; unmature: number }
  emailBind: { success: number; fail: number; none: number }
  block: { blocked: number; unblocked: number }
  sell: { sold: number; saleable: number; other: number }
  window: { shop: number; matrix: number; other: number }
}

export interface AccountQuery {
  page: number
  size: number
  startDate?: string
  endDate?: string
  username?: string
  country?: string
  // 以下字段主要给旧版/聚合页面（如 AccountWindowPage）使用，新账号页不会再直接依赖它们
  status?: string
  accountType?: string
  region?: string
  note?: string
  registerStatus?: string
  keyStatus?: string
  matureStatus?: string
  emailBindStatus?: string
  blockStatus?: string
  sellStatus?: string
  shopStatus?: string
  sortOrder?: 'asc' | 'desc'
}

export interface WindowQuery {
  page: number
  size: number
  fanStartDate?: string
  fanEndDate?: string
  nurtureStartDate?: string
  nurtureEndDate?: string
  nurtureStrategy?: string
  shopStatus?: string
  nurtureDevice?: string
  country?: string
  account?: string
  note?: string
}

function toQuery(params: Record<string, string | number | undefined>) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === '') return
    query.set(k, String(v))
  })
  return query.toString()
}

export async function getAccountList(params: AccountQuery) {
  const res = await requestApi<PagedResp<AccountItem>>({
    path: '/api/tt-register/account/list',
    params: params as unknown as Record<string, string | number | boolean | undefined>
  })
  if (res.success && res.data) {
    res.data = {
      ...toPagedResponse(res.data, { page: params.page, size: params.size }),
      totalAccurate: res.data.totalAccurate
    }
  }
  return res as ApiResp<PagedResp<AccountItem>>
}

export async function getWindowList(params: WindowQuery) {
  const res = await requestApi<PagedResp<WindowItem>>({
    path: '/api/tt-register/window/list',
    params: params as unknown as Record<string, string | number | boolean | undefined>
  })
  if (res.success && res.data) {
    res.data = toPagedResponse(res.data, { page: params.page, size: params.size })
  }
  return res as ApiResp<PagedResp<WindowItem>>
}

export async function exportAccountList(params: AccountQuery) {
  const qs = toQuery(params as unknown as Record<string, string | number | undefined>)
  const { data } = await http.get<ApiResp<AccountItem[]>>(`/api/tt-register/account/export?${qs}`)
  return data
}

export async function importAccountCsv(file: File) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<ImportResult>('/api/tt-register/account/import', form)
  return data
}

export async function getAccountDetail(id: number) {
  const { data } = await http.get<ApiResp<AccountItem>>(`/api/tt-register/account/${id}`)
  return data
}

export async function updateAccount(payload: (Partial<AccountItem> & { id: number }) & { newEmailBindSuccess?: number | null }) {
  return requestApi<AccountItem>({
    path: '/api/tt-register/account/update',
    method: 'POST',
    data: payload,
    auditAction: 'account.update'
  })
}

export async function getAccountDateSummary(startDate?: string, endDate?: string) {
  const qs = toQuery({ startDate, endDate })
  const { data } = await http.get<ApiResp<AccountDateSummary>>(`/api/tt-register/account/date-summary?${qs}`)
  return data
}

export async function getAccountFilterStats(params: Omit<AccountQuery, 'page' | 'size' | 'sortOrder'>) {
  const { data } = await http.get<ApiResp<AccountFilterStatsData>>(
    `/api/tt-register/account/filter-stats?${toQuery(params as unknown as Record<string, string | number | undefined>)}`
  )
  return data
}

export async function startNurtureAccounts(accountIds: number[]) {
  return requestApi<{
    updatedCount: number
    insertedCount: number
    skipCount: number
    errors?: string[]
  }>({
    path: '/api/tt-register/account/start-nurture',
    method: 'POST',
    data: { accountIds },
    auditAction: 'account.startNurture'
  })
}
