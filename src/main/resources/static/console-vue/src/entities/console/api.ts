import { requestApi } from '../../shared/api/request'
import type { ConsoleBootstrapData } from './types'

export async function getConsoleBootstrap() {
  return requestApi<ConsoleBootstrapData>({
    path: '/api/console/meta/bootstrap',
    method: 'GET'
  })
}
