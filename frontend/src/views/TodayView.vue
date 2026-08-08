<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useDashboardStore } from '../stores/dashboard'
import WarpList from '../components/WarpList.vue'

const store = useDashboardStore()
const { today, loading, error } = storeToRefs(store)

onMounted(() => store.load())
</script>

<template>
  <main class="main">
    <!-- LOADING -->
    <div v-if="loading" class="loadwrap" aria-busy="true">
      <div class="head"><h1>Today</h1></div>
      <div class="skel w40"></div>
      <div class="skel w85"></div>
      <div class="skel w70"></div>
      <div class="skel w78"></div>
      <p class="mono buildmsg">building your view…</p>
    </div>

    <!-- ERROR -->
    <div v-else-if="error" class="errwrap">
      <div class="head"><h1>Today</h1></div>
      <div class="errbar"><span aria-hidden="true">⚠</span><span>{{ error }}</span></div>
      <button class="btn" @click="store.load()">Retry now</button>
    </div>

    <!-- READY -->
    <template v-else-if="today">
      <div class="head">
        <h1>Today</h1>
        <span class="when mono">{{ today.now }}</span>
      </div>

      <section v-if="today.changed" class="changed" aria-label="What changed">
        <span class="eyebrow">What changed</span>
        <span class="txt">{{ today.changed.text }}</span>
        <span class="go">{{ today.changed.since }} ›</span>
      </section>

      <div class="sectlab">
        <span class="eyebrow">Next</span>
        <span class="r">sorted: priority ▾</span>
      </div>

      <WarpList :items="today.next" />

      <div class="more">
        <span>▸ Everything ({{ today.everythingCount }})</span>
        <span>▸ Snoozed ({{ today.snoozedCount }})</span>
      </div>
    </template>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 18px; }
.head h1 { font-size: 26px; }
.when { font-size: 12px; color: var(--faint-text); }

.changed {
  border: 1px solid var(--line); border-radius: var(--r-card); padding: 12px 14px;
  background: linear-gradient(180deg, var(--changed-a), var(--changed-b));
  display: flex; align-items: center; gap: 14px; margin-bottom: 22px;
}
.changed .txt { font-size: 14px; color: var(--dim); }
.changed .go { margin-left: auto; color: var(--warp-hi); font-size: 13px; }

.sectlab { display: flex; align-items: center; gap: 10px; margin: 4px 0 12px; }
.sectlab .r { margin-left: auto; font-size: 12px; color: var(--faint-text); }

.more { display: flex; gap: 20px; margin-top: 16px; color: var(--faint-text); font-size: 13px; }
.more span { cursor: pointer; }

/* states */
.skel {
  height: 14px; border-radius: 6px; margin: 10px 0;
  background: linear-gradient(90deg, var(--line), var(--raised), var(--line));
  background-size: 200% 100%; animation: sh 1.2s linear infinite;
}
.w40 { width: 40%; } .w85 { width: 85%; } .w70 { width: 70%; } .w78 { width: 78%; }
@keyframes sh { from { background-position: 200% 0; } to { background-position: -200% 0; } }
@media (prefers-reduced-motion: reduce) { .skel { animation: none; } }
.buildmsg { color: var(--faint-text); font-size: 11px; margin-top: 14px; }

.errbar {
  border: 1px solid rgba(176, 74, 69, 0.5); background: rgba(176, 74, 69, 0.08);
  border-radius: 8px; padding: 12px 14px; color: #e7b0ac; font-size: 13px;
  display: flex; align-items: center; gap: 10px; margin-bottom: 14px;
}
.btn {
  font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 6px 12px;
  border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink);
}
.btn:hover { border-color: var(--warp); }
</style>
