<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

/**
 * The loom, weaving. Warp threads stand under tension while a shuttle passes back and
 * forth, weaving cloth upward — DevLoom's mark, animated. For slow operations pass
 * `steps` (cycled as reassuring stage labels) and `estMs` (drives an optimistic progress
 * bar that eases toward ~92% and completes when the real result arrives / the loader unmounts).
 */
const props = defineProps<{
  label?: string
  steps?: string[]
  estMs?: number
  size?: 'sm' | 'md'
}>()

const stepIdx = ref(0)
const progress = ref(0)
let stepTimer: ReturnType<typeof setInterval> | undefined
let progTimer: ReturnType<typeof setInterval> | undefined

const hasProgress = computed(() => !!props.estMs)
const current = computed(() =>
  props.steps && props.steps.length ? props.steps[stepIdx.value] : (props.label ?? 'Loading…'),
)
// Woven cloth height: driven by progress when timed, otherwise a gentle resting fill.
const wovenHeight = computed(() => (hasProgress.value ? progress.value : 40))

onMounted(() => {
  const est = props.estMs ?? 12000
  if (props.steps && props.steps.length > 1) {
    const per = Math.max(1400, est / props.steps.length)
    stepTimer = setInterval(() => {
      if (stepIdx.value < props.steps!.length - 1) stepIdx.value++
    }, per)
  }
  if (hasProgress.value) {
    const start = performance.now()
    progTimer = setInterval(() => {
      const t = (performance.now() - start) / est
      // ease-out toward 92% — never claims done until the caller unmounts us
      progress.value = Math.min(92, Math.round((1 - Math.exp(-t * 2.2)) * 100))
    }, 120)
  }
})

onUnmounted(() => {
  if (stepTimer) clearInterval(stepTimer)
  if (progTimer) clearInterval(progTimer)
})
</script>

<template>
  <div class="loom" :class="size ?? 'md'" role="status" :aria-label="current">
    <div class="frame" aria-hidden="true">
      <span class="woven" :style="{ height: wovenHeight + '%' }"></span>
      <span class="shuttle" :style="{ bottom: wovenHeight + '%' }"></span>
    </div>
    <div class="txt mono">{{ current }}</div>
    <div v-if="hasProgress" class="bar" aria-hidden="true"><span :style="{ width: progress + '%' }"></span></div>
  </div>
</template>

<style scoped>
.loom { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 8px; }
.frame {
  position: relative; overflow: hidden;
  border: 1px solid var(--warp); border-radius: 10px; background: var(--bg);
  /* standing warp threads */
  background-image: repeating-linear-gradient(90deg, var(--warp-weft) 0 2px, transparent 2px 9px);
}
.md .frame { width: 88px; height: 88px; }
.sm .frame { width: 44px; height: 44px; }

/* woven cloth: a cross-hatch growing from the bottom beam upward */
.woven {
  position: absolute; left: 0; right: 0; bottom: 0;
  background-image:
    repeating-linear-gradient(90deg, var(--warp) 0 2px, transparent 2px 9px),
    repeating-linear-gradient(0deg, var(--warp-hi) 0 2px, transparent 2px 9px);
  transition: height 0.35s var(--ease-out);
}

/* the shuttle: a bright weft pass sliding across the working row */
.shuttle {
  position: absolute; left: -20%; right: -20%; height: 3px;
  background: linear-gradient(90deg, transparent, var(--warp-hi) 45%, var(--warp-hi) 55%, transparent);
  transition: bottom 0.35s var(--ease-out);
  animation: weave 1.1s ease-in-out infinite;
}
@keyframes weave {
  0%, 100% { transform: translateX(-22%); }
  50% { transform: translateX(22%); }
}

/* indeterminate resting weave (no timed progress): breathe the cloth up and down */
.loom:not(:has(.bar)) .woven { animation: breathe 1.8s ease-in-out infinite; }
@keyframes breathe {
  0%, 100% { height: 34%; }
  50% { height: 58%; }
}

.txt { font-size: 12px; color: var(--dim); text-align: center; max-width: 42ch; }
.bar { width: 168px; height: 3px; background: var(--line); border-radius: 2px; overflow: hidden; }
.bar span { display: block; height: 100%; background: var(--warp); transition: width 0.3s var(--ease-out); }

@media (prefers-reduced-motion: reduce) {
  .shuttle { animation: none; }
  .loom:not(:has(.bar)) .woven { animation: none; }
}
</style>
