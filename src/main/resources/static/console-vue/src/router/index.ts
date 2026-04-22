import { createRouter, createWebHistory } from 'vue-router'
import { getAuthToken } from '../api/authApi'
import { routes } from '../app/router/routes'

const router = createRouter({
  history: createWebHistory('/console-vue/'),
  routes
})

router.beforeEach((to) => {
  const token = getAuthToken()
  const needAuth = Boolean(to.matched.some((m) => m.meta?.requiresAuth))
  const guestOnly = Boolean(to.meta?.guestOnly)

  if (needAuth && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (guestOnly && token) {
    return { path: '/overview' }
  }
  return true
})

export default router
