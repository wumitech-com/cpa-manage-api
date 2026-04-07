import http from './http'
import type { ApiResp } from './statsApi'

export const AUTH_TOKEN_KEY = 'tt_console_token'
export const AUTH_USER_KEY = 'tt_console_user'

export async function login(username: string, password: string) {
  const { data } = await http.post<ApiResp<{ token: string; username: string; expireAt: number }>>('/api/auth/login', {
    username,
    password
  })
  return data
}

export async function logout() {
  const { data } = await http.post<ApiResp<unknown>>('/api/auth/logout')
  return data
}

export function getAuthToken() {
  return localStorage.getItem(AUTH_TOKEN_KEY) || ''
}

export function setAuth(token: string, username: string) {
  localStorage.setItem(AUTH_TOKEN_KEY, token)
  localStorage.setItem(AUTH_USER_KEY, username)
}

export function clearAuth() {
  localStorage.removeItem(AUTH_TOKEN_KEY)
  localStorage.removeItem(AUTH_USER_KEY)
}

