<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  title: string
  option: echarts.EChartsOption
  height?: number
}>()

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

function render() {
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption(props.option, true)
}

onMounted(render)
watch(() => props.option, render, { deep: true })
onBeforeUnmount(() => {
  if (chart) chart.dispose()
})
</script>

<template>
  <div class="card">
    <div class="panel-title">{{ title }}</div>
    <div ref="chartRef" :style="{ height: `${height || 320}px` }"></div>
  </div>
</template>
