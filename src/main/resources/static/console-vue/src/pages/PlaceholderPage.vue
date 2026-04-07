<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <section class="card placeholder">
    <h2>{{ String(route.meta.title || '页面') }}</h2>
    <p>{{ String(route.meta.tip || '页面建设中') }}</p>
  </section>
</template>
