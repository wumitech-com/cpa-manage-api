import http from '../../api/http'

interface PerfPayload {
  name: string
  durationMs: number
  success: boolean
  path: string
}

export async function reportPerf(payload: PerfPayload) {
  try {
    await http.post('/api/console/metrics/perf', payload, {
      headers: { 'X-Console-Silent': '1' }
    })
  } catch {
    // keep telemetry best-effort and non-blocking
  }
}
