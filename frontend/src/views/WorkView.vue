<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { WorkRow } from '../types'
import { fetchWork } from '../api/stub'

const rows = ref<WorkRow[]>([])
const loading = ref(true)
const filters = ['All', 'PRs', 'Reviews', 'Tasks', 'Builds', 'Calendar', 'mine', 'stale']
const active = ref('All')

onMounted(async () => {
  rows.value = await fetchWork()
  loading.value = false
})
</script>

<template>
  <main class="main">
    <div class="head">
      <h1>Work</h1>
      <span class="when mono">{{ rows.length }} items · 4 sources</span>
    </div>

    <div class="filters" role="tablist" aria-label="Filter work">
      <button
        v-for="f in filters"
        :key="f"
        class="fchip"
        :class="{ on: active === f }"
        role="tab"
        :aria-selected="active === f"
        @click="active = f"
      >
        {{ f }}
      </button>
      <span class="fsearch mono">⌕ filter…</span>
    </div>

    <div v-if="loading" class="mono empty">loading work…</div>
    <template v-else>
      <div v-for="r in rows" :key="r.id" class="wi">
        <span class="g mono" aria-hidden="true">{{ r.glyph }}</span>
        <span class="ti">{{ r.title }}</span>
        <span class="mt mono">
          <span class="dot" :class="r.statusTone" aria-hidden="true"></span> {{ r.status }}
          <span v-for="m in r.meta" :key="m">{{ m }}</span>
        </span>
      </div>
    </template>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 18px; }
.head h1 { font-size: 26px; }
.when { font-size: 12px; color: var(--faint-text); }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 16px; flex-wrap: wrap; }
.fchip {
  font-size: 12.5px; padding: 5px 11px; border: 1px solid var(--line);
  border-radius: 20px; color: var(--dim); background: transparent;
}
.fchip:hover { border-color: var(--warp); }
.fchip.on { background: var(--warp-weft); border-color: var(--warp); color: var(--ink); }
.fsearch {
  margin-left: auto; font-size: 12px; color: var(--faint-text);
  border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px; min-width: 180px;
}
.wi {
  display: flex; align-items: center; gap: 12px; padding: 12px 14px;
  border: 1px solid var(--line); border-radius: 10px; background: var(--surface); margin-bottom: 8px;
}
.wi:hover { border-color: var(--line-hi); }
.g { width: 20px; text-align: center; color: var(--faint-text); }
.ti { color: var(--ink); font-weight: 500; font-size: 14px; }
.mt {
  font-size: 11px; color: var(--faint-text); margin-left: auto;
  display: flex; gap: 12px; align-items: center; white-space: nowrap;
}
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot.warn { background: var(--warp); }
.dot.fail { background: var(--failed); }
.dot.stale { background: var(--stale); }
.dot.healthy { background: var(--healthy); }
.dot.info { background: var(--info); }
.empty { color: var(--faint-text); padding: 20px 0; }
</style>
