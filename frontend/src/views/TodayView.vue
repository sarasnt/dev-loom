<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useDashboardStore } from '../stores/dashboard'
import WarpList from '../components/WarpList.vue'

const store = useDashboardStore()
const { today, loading, error } = storeToRefs(store)

// Today has two lenses: Briefing (the morning framing) and Triage (the full ranked list). The
// last explicit choice wins; otherwise default to Briefing in the morning, Triage later.
const MODE_KEY = 'devloom.todayMode'
function defaultMode(): 'briefing' | 'triage' {
  const saved = localStorage.getItem(MODE_KEY)
  if (saved === 'briefing' || saved === 'triage') return saved
  return new Date().getHours() < 12 ? 'briefing' : 'triage'
}
const resolvedOpen = ref(false)
const mode = ref<'briefing' | 'triage'>(defaultMode())
function setMode(m: 'briefing' | 'triage') {
  mode.value = m
  localStorage.setItem(MODE_KEY, m)
}

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
        <div class="modes" role="tablist" aria-label="Today view">
          <button class="seg" :class="{ on: mode === 'briefing' }" role="tab" :aria-selected="mode === 'briefing'" @click="setMode('briefing')">Briefing</button>
          <button class="seg" :class="{ on: mode === 'triage' }" role="tab" :aria-selected="mode === 'triage'" @click="setMode('triage')">Triage</button>
        </div>
        <span class="when mono">{{ today.now }}</span>
      </div>

      <section v-if="today.changed" class="changed" aria-label="What changed">
        <span class="eyebrow">What changed</span>
        <span class="txt">{{ today.changed.text }}</span>
        <span class="go">{{ today.changed.since }} ›</span>
      </section>

      <!-- TRIAGE: the full ranked list -->
      <template v-if="mode === 'triage'">
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

      <!-- BRIEFING: the morning framing — needs-you, since-yesterday, today's plan -->
      <template v-else>
        <template v-if="today.briefing.needsYou.length">
          <div class="sectlab"><span class="eyebrow">Needs you now</span></div>
          <WarpList :items="today.briefing.needsYou" />
        </template>

        <div class="sectlab"><span class="eyebrow">Since yesterday</span></div>
        <template v-if="today.briefing.newItems.length">
          <div class="subhead mono">New ({{ today.briefing.newItems.length }})</div>
          <WarpList :items="today.briefing.newItems" />
        </template>
        <!-- Resolved is acknowledgement, not work. As full cards it was most of the briefing —
             sixteen closed tickets, each offering "+ Plan" and "Handled" on something already
             done. One line each, folded away, and the morning read stays short. -->
        <template v-if="today.briefing.resolved.length">
          <button class="subhead mono fold" :aria-expanded="resolvedOpen" @click="resolvedOpen = !resolvedOpen">
            <span class="caret">{{ resolvedOpen ? '▾' : '▸' }}</span>
            Resolved ({{ today.briefing.resolved.length }})
          </button>
          <ul v-if="resolvedOpen" class="donelist">
            <li v-for="r in today.briefing.resolved" :key="r.id" class="doneitem">
              <span class="donetick" aria-hidden="true">✓</span>
              <a v-if="r.url" :href="r.url" target="_blank" rel="noopener noreferrer" class="donetitle">{{ r.title }}</a>
              <span v-else class="donetitle">{{ r.title }}</span>
              <span class="donesrc mono">{{ r.source }}</span>
            </li>
          </ul>
        </template>
        <div v-if="!today.briefing.newItems.length && !today.briefing.resolved.length" class="subhead mono quiet">
          nothing new since your last briefing
        </div>

        <template v-if="today.briefing.plan.length">
          <div class="sectlab"><span class="eyebrow">Today's plan</span></div>
          <WarpList :items="today.briefing.plan" />
        </template>

        <div v-if="!today.briefing.needsYou.length && !today.briefing.newItems.length && !today.briefing.plan.length"
             class="mono emptybrief">Nothing needs you yet — enjoy the quiet. Add items to your plan from Triage.</div>
      </template>
    </template>
  </main>
</template>

<style scoped>
.fold { background: none; border: 0; padding: 0; cursor: pointer; display: flex; align-items: center; gap: 6px; }
.fold:hover { color: var(--ink); }
.caret { color: var(--warp-hi); }
.donelist { list-style: none; margin: 4px 0 18px; padding: 0 0 0 26px; display: flex; flex-direction: column; gap: 2px; }
.doneitem { display: flex; align-items: baseline; gap: 9px; font-size: 12.5px; padding: 3px 0; }
.donetick { color: var(--healthy, #6ea87f); font-size: 11px; }
.donetitle { color: var(--dim); text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
a.donetitle:hover { color: var(--ink); text-decoration: underline; }
.donesrc { color: var(--faint-text); font-size: 10.5px; margin-left: auto; }
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

/* Briefing | Triage segmented control */
.modes { display: inline-flex; gap: 2px; margin-left: 16px; padding: 2px; border: 1px solid var(--line); border-radius: 8px; }
.modes .seg { font-size: 12px; padding: 4px 12px; border: 0; border-radius: 6px; background: transparent; color: var(--faint-text); cursor: pointer; }
.modes .seg:hover { color: var(--ink); }
.modes .seg.on { background: var(--warp-weft); color: var(--warp-hi); }
.head { align-items: center; }
.subhead { font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint-text); margin: 6px 0 8px; }
.subhead.quiet { color: var(--faint-text); text-transform: none; letter-spacing: 0; font-size: 12px; }
.emptybrief { color: var(--faint-text); font-size: 13px; padding: 18px 0; }

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
