import { requestApi } from '../shared/api/request'
import type { ApiResponse as ApiResp } from '../shared/contracts/api'
export type { ApiResp }

export interface DailyOverview {
  todayRegister: number
  todayRegisterSuccess: number
  today2faSuccess: number
  todayNeedRetention: number
  todayRegisterSuccessRate: number
  today2faSetupSuccessRate: number
}

export interface DailyTrendItem {
  date: string
  label?: string
  register: number
  twofa: number
  retention: number
  trafficTotal?: number
  trafficAvg?: number
}

export interface BlockRateItem {
  baseDate: string
  total: number
  blocked: number
  blockRate: number
  label: string
}

export interface DistItem {
  value: string
  count: number
  percent?: number
  totalCount?: number
}

export interface TwofaDetail {
  androidVersionDist?: DistItem[]
  behaviorDist?: DistItem[]
  tiktokVersionDist?: DistItem[]
  countryDist?: DistItem[]
  phoneServerIpDist?: DistItem[]
  serverHourly2fa?: Array<{
    serverIp?: string
    total?: number
    hourly?: Array<{ hour?: string; count?: number }>
  }>
  serverHourlyRegister?: Array<{
    serverIp?: string
    total?: number
    [key: string]: string | number | undefined
  }>
  trafficTotal?: number
  trafficAvgPerSuccess?: number
  trafficTotalAll?: number
  androidVersionRateDist?: DistItem[]
  behaviorRateDist?: DistItem[]
  tiktokVersionRateDist?: DistItem[]
  countryRateDist?: DistItem[]
  phoneServerIpRateDist?: DistItem[]
}

export interface BlockRateData {
  queryDate?: string
  country?: string
  nextDay?: BlockRateItem
  threeDay?: BlockRateItem
  sevenDay?: BlockRateItem
  blockedDetail?: TwofaDetail
  blockedDetailByPeriod?: Record<string, TwofaDetail>
}

export interface BlockRateTrendData {
  trend: Array<{
    date: string
    label?: string
    total?: number
    blocked?: number
    blockRate?: number
  }>
  country?: string
}

export interface BlockRateDailyItem {
  date: string
  total: number
  blocked: number
  blockRate: number
}

export interface BlockRateMatrixCell {
  offset: number
  cohortDate: string
  total: number
  blockedAsOfRow: number
  blockRateAsOfRow: number
  blockedNextDay: number
  nextDayBlockRate: number
}

export interface BlockRateMatrixRow {
  rowDate: string
  cells: BlockRateMatrixCell[]
}

export interface BlockRateMatrixData {
  start: string
  end: string
  country?: string
  rows: BlockRateMatrixRow[]
}

export async function getDailyOverview(date?: string) {
  return requestApi<DailyOverview>({
    path: '/api/statistics/daily-register/overview',
    params: { date }
  })
}

export async function getDailyTrend(date?: string) {
  return requestApi<{ dailyTrend: DailyTrendItem[] }>({
    path: '/api/statistics/daily-register/trend',
    params: { date }
  })
}

export async function getDailyDetail(date?: string) {
  return requestApi<{ twofaDetail?: TwofaDetail }>({
    path: '/api/statistics/daily-register/detail',
    params: { date }
  })
}

function blockRateQuery(date?: string, country?: string) {
  const p = new URLSearchParams()
  if (date) p.set('date', date)
  if (country && country !== 'ALL') p.set('country', country)
  const s = p.toString()
  return s ? `?${s}` : ''
}

export async function getBlockRate(date?: string, country?: string) {
  const params = new URLSearchParams(blockRateQuery(date, country).replace(/^\?/, ''))
  return requestApi<BlockRateData>({
    path: '/api/statistics/block-rate',
    params: Object.fromEntries(params.entries())
  })
}

export async function getBlockRateTrend(date?: string, country?: string) {
  const params = new URLSearchParams(blockRateQuery(date, country).replace(/^\?/, ''))
  return requestApi<BlockRateTrendData>({
    path: '/api/statistics/block-rate-trend',
    params: Object.fromEntries(params.entries())
  })
}

export async function getBlockRateMatrix(start: string, end: string, country?: string) {
  return requestApi<BlockRateMatrixData>({
    path: '/api/statistics/block-rate-matrix',
    params: {
      start,
      end,
      country: country && country !== 'ALL' ? country : undefined
    }
  })
}

export async function getBlockRateDaily(date?: string, days: number = 40) {
  return requestApi<{ queryDate: string; days: number; daily: BlockRateDailyItem[] }>({
    path: '/api/statistics/block-rate-daily',
    params: { date, days }
  })
}
