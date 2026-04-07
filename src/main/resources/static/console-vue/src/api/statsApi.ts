import http from './http'

export interface ApiResp<T> {
  success: boolean
  message?: string
  data?: T
}

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
}

export interface BlockRateDailyItem {
  date: string
  total: number
  blocked: number
  blockRate: number
}

export async function getDailyOverview(date?: string) {
  const qs = date ? `?date=${encodeURIComponent(date)}` : ''
  const { data } = await http.get<ApiResp<DailyOverview>>(`/api/statistics/daily-register/overview${qs}`)
  return data
}

export async function getDailyTrend(date?: string) {
  const qs = date ? `?date=${encodeURIComponent(date)}` : ''
  const { data } = await http.get<ApiResp<{ dailyTrend: DailyTrendItem[] }>>(`/api/statistics/daily-register/trend${qs}`)
  return data
}

export async function getDailyDetail(date?: string) {
  const qs = date ? `?date=${encodeURIComponent(date)}` : ''
  const { data } = await http.get<ApiResp<{ twofaDetail?: TwofaDetail }>>(`/api/statistics/daily-register/detail${qs}`)
  return data
}

export async function getBlockRate(date?: string) {
  const qs = date ? `?date=${encodeURIComponent(date)}` : ''
  const { data } = await http.get<ApiResp<BlockRateData>>(`/api/statistics/block-rate${qs}`)
  return data
}

export async function getBlockRateTrend(date?: string) {
  const qs = date ? `?date=${encodeURIComponent(date)}` : ''
  const { data } = await http.get<ApiResp<BlockRateTrendData>>(`/api/statistics/block-rate-trend${qs}`)
  return data
}

export async function getBlockRateDaily(date?: string, days: number = 40) {
  const qs = []
  if (date) qs.push(`date=${encodeURIComponent(date)}`)
  qs.push(`days=${days}`)
  const query = qs.length ? `?${qs.join('&')}` : ''
  const { data } = await http.get<ApiResp<{ queryDate: string; days: number; daily: BlockRateDailyItem[] }>>(
    `/api/statistics/block-rate-daily${query}`
  )
  return data
}
