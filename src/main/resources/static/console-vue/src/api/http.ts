import axios from 'axios'
import { AUTH_TOKEN_KEY, clearAuth } from './authApi'

const http = axios.create({
  baseURL: '/',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(AUTH_TOKEN_KEY)
  if (token) {
    config.headers = config.headers || {}
    config.headers['X-Auth-Token'] = token
  }
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  (err) => {
    if (err?.response?.status === 401) {
      clearAuth()
      if (!window.location.pathname.endsWith('/login')) {
        window.location.href = '/console-vue/login'
      }
    }
    return Promise.reject(err)
  }
)

export default http
