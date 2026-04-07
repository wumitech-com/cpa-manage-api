<script setup lang="ts">
defineProps<{ title: string; desc?: string }>()
</script>

<template>
  <div class="card page-block">
    <div class="panel-title">{{ title }}</div>
    <p v-if="desc" class="block-desc">{{ desc }}</p>
    <slot />
  </div>
</template>
