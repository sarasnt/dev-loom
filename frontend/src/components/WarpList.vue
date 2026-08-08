<script setup lang="ts">
import type { Recommendation } from '../types'
import RecommendationCard from './RecommendationCard.vue'

defineProps<{ items: Recommendation[] }>()
</script>

<template>
  <ol class="warp" aria-label="Next — ranked by priority">
    <li v-for="item in items" :key="item.id" class="item">
      <span class="rank" aria-hidden="true">{{ item.rank }}</span>
      <span class="sr-only">Priority {{ item.rank }}:</span>
      <RecommendationCard :item="item" />
    </li>
  </ol>
</template>

<style scoped>
/* the warp: a single spine under tension; items hang off it, numbered by rank */
.warp { position: relative; list-style: none; margin: 0; padding: 0 0 0 26px; }
.warp::before {
  content: ''; position: absolute; left: 9px; top: 6px; bottom: 26px; width: 2px;
  background: linear-gradient(180deg, var(--warp), rgba(198, 144, 47, 0.25));
}
.item { position: relative; margin-bottom: 14px; animation: rise var(--motion) var(--ease-out) both; }
.rank {
  position: absolute; left: -26px; top: 14px; width: 20px; height: 20px; border-radius: 50%;
  background: var(--bg); border: 1.5px solid var(--warp); color: var(--warp-hi);
  font-family: var(--mono); font-size: 11px; display: flex; align-items: center; justify-content: center;
}
@keyframes rise {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) { .item { animation: none; } }
</style>
