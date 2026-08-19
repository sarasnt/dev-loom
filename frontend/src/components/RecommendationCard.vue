<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import type { Recommendation } from '../types'
import { useDashboardStore } from '../stores/dashboard'
import Mono from './Mono.vue'

const props = defineProps<{ item: Recommendation }>()

const router = useRouter()
const store = useDashboardStore()
const { models, agentModels } = storeToRefs(store)

const primaryAction = (actions: string[]) => actions[0]
const restActions = (actions: string[]) => actions.slice(1)

const whyOpen = ref(false)
const amenu = ref(false)
// Build analysis uses local + keyed-remote models (never the Brainstorm-only agent modes).
const analyzeModels = computed(() => models.value.filter((m) => !agentModels.value.includes(m)))

// Resolve a GitHub URL from the "owner/repo#number" id when the backend didn't supply one.
function itemUrl(item: Recommendation): string | null {
  if (item.url) return item.url
  if (item.source === 'GitHub' && item.id.includes('#')) {
    const [repo, num] = item.id.split('#')
    return `https://github.com/${repo}/${item.type === 'pr' ? 'pull' : 'issues'}/${num}`
  }
  return null
}
function openItem(item: Recommendation) {
  const u = itemUrl(item)
  if (u) window.open(u, '_blank', 'noopener')
}

// Primary: a build → its on-machine analysis (default model); anything else → open it.
function runPrimary(item: Recommendation) {
  if (item.type === 'build') { router.push(`/builds/${item.id}`); return }
  openItem(item)
}
function analyzeWith(item: Recommendation, model: string) {
  amenu.value = false
  router.push({ path: `/builds/${item.id}`, query: { model } })
}
// Wire a secondary action button by its label.
function runAction(item: Recommendation, action: string) {
  if (action === 'Why') whyOpen.value = !whyOpen.value
  else if (action === 'Snooze') store.snoozeItem(item.id)
  else if (action === 'Open') openItem(item)
  else runPrimary(item)
}
// Briefing memory: mark handled / toggle into today's plan.
function toggleHandled(item: Recommendation) { store.handleItem(item.id) }
function togglePlan(item: Recommendation) { store.planItem(item.id) }
</script>

<template>
  <!-- Stale items render as a quiet mini-row, not a full card -->
  <div v-if="item.type === 'stale'" class="mini">
    <span class="mini-title">{{ item.title }}</span>
    <Mono class="chip stale">{{ item.chips[0]?.label }}</Mono>
    <span class="mini-why">{{ item.why }}</span>
    <button class="btn ghost mini-act" @click="runPrimary(item)">{{ primaryAction(item.actions) }}</button>
  </div>

  <article v-else :class="['card', { lead: item.lead, elevated: amenu || whyOpen }]">
    <header class="ct">
      <h3>{{ item.title }}</h3>
      <Mono class="src">⎇ {{ item.source }}</Mono>
    </header>

    <!-- reasoning = grotesque + marker (DESIGN.md §1) -->
    <p class="why">
      {{ item.why }}
      <span v-if="item.isHypothesis" class="reason-mark" aria-label="model reasoning">reasoning°</span>
    </p>

    <div class="chips">
      <Mono
        v-for="(c, i) in item.chips"
        :key="i"
        :class="['chip', c.tone && c.tone !== 'neutral' ? c.tone : '']"
        >{{ c.label }}</Mono
      >
      <!-- Plan as visible state, not just a button label — pressing "+ Plan" otherwise looks
           like it did nothing until you find the Briefing tab. -->
      <Mono v-if="item.planned" class="chip plannedchip">planned</Mono>
    </div>

    <!-- Why: the priority signals + evidence behind the ranking -->
    <div v-if="whyOpen" class="whybox mono">
      <div v-for="s in item.signals ?? []" :key="s.name" class="sig-row">
        <span class="sig-name">{{ s.name }}</span>
        <span class="sig-disp">{{ s.value }}</span>
        <span class="sig-bar"><i :style="{ width: Math.round((s.normalized ?? 0) * 100) + '%' }"></i></span>
        <span class="sig-w">×{{ s.weight }}</span>
      </div>
      <div v-if="item.evidence?.length" class="ev">evidence → {{ item.evidence.map((e) => e.id).join(' · ') }}</div>
      <div v-if="!item.signals?.length && !item.evidence?.length" class="ev">{{ item.why }}</div>
    </div>

    <div class="acts">
      <button class="btn pri" @click="runPrimary(item)">{{ primaryAction(item.actions) }} ▸</button>
      <!-- Analyze with… (build only): pick a model for this run -->
      <div v-if="item.type === 'build'" class="awrap">
        <button class="btn" @click="amenu = !amenu">Analyze with ▾</button>
        <div v-if="amenu" class="amenu" @click.self="amenu = false">
          <div class="amlab mono">choose a model</div>
          <button v-for="m in analyzeModels" :key="m" class="ami mono" @click="analyzeWith(item, m)">{{ m }}</button>
          <div v-if="!analyzeModels.length" class="ami empty mono">no models available</div>
        </div>
      </div>
      <button v-for="a in restActions(item.actions)" :key="a" class="btn ghost" @click="runAction(item, a)">{{ a }}</button>
      <span class="spacer"></span>
      <button class="btn ghost bplan" :class="{ on: item.planned }" @click="togglePlan(item)">{{ item.planned ? '− Plan' : '+ Plan' }}</button>
      <button class="btn ghost bhandled" :class="{ on: item.handled }" @click="toggleHandled(item)">{{ item.handled ? '↩ Unhandle' : '✓ Handled' }}</button>
    </div>
  </article>
</template>

<style scoped>
.card {
  border: 1px solid var(--line);
  border-radius: var(--r-card);
  background: var(--surface);
  padding: 14px 16px;
  transition: border-color var(--motion) var(--ease-out);
}
.card:hover { border-color: var(--line-hi); }
/* Lift the card (and its open dropdown) above sibling cards below it. */
.card.elevated { position: relative; z-index: 50; }
.card.lead {
  box-shadow: inset 2px 0 0 var(--warp);
  background: linear-gradient(90deg, var(--warp-weft), transparent 42%), var(--surface);
}
.ct { display: flex; align-items: center; gap: 10px; }
.ct h3 { font-size: 17px; }
.src { margin-left: auto; font-size: 11px; color: var(--faint-text); }
.why { color: var(--dim); font-size: 14px; margin: 7px 0 10px; max-width: 62ch; }
.reason-mark {
  font-family: var(--mono); font-size: 10px; color: var(--warp);
  border: 1px solid var(--warp); border-radius: 4px; padding: 1px 5px; margin-left: 8px;
  white-space: nowrap; vertical-align: 2px;
}
.chips { display: flex; flex-wrap: wrap; gap: 7px; }
.chip {
  font-size: 11px; color: var(--dim); border: 1px solid var(--line);
  border-radius: 5px; padding: 3px 7px; background: var(--chip-bg);
}
.chip.warn { color: var(--warp-hi); border-color: var(--warp); }
.chip.plannedchip { color: var(--warp-hi); border-color: var(--warp); background: var(--warp-weft); }
.chip.fail { color: var(--chip-fail); border-color: var(--failed); }
.chip.stale { color: var(--chip-stale); border-color: var(--stale); }
.whybox { margin-top: 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); padding: 10px 12px; font-size: 11.5px; color: var(--dim); }
.sig-row { display: flex; align-items: center; gap: 10px; padding: 3px 0; }
.sig-name { width: 66px; color: var(--warp-hi); }
.sig-disp { width: 96px; color: var(--faint-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sig-bar { flex: 1; height: 6px; border-radius: 4px; background: var(--raised); overflow: hidden; }
.sig-bar i { display: block; height: 100%; background: var(--warp); }
.sig-w { width: 34px; text-align: right; color: var(--faint-text); }
.ev { margin-top: 6px; color: var(--warp-hi); }
.acts { display: flex; gap: 8px; margin-top: 12px; align-items: center; }
.awrap { position: relative; }
.amenu { position: absolute; top: calc(100% + 4px); left: 0; z-index: 20; min-width: 200px; max-height: 260px; overflow-y: auto; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,0.35); }
.amlab { font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint-text); padding: 4px 9px 6px; }
.ami { display: block; width: 100%; text-align: left; background: transparent; border: 0; border-radius: 6px; padding: 6px 9px; color: var(--ink); font-size: 12px; cursor: pointer; white-space: nowrap; }
.ami:hover { background: var(--nav-hover); }
.ami.empty { color: var(--faint-text); cursor: default; }
.btn {
  font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 6px 12px;
  border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink);
  transition: border-color var(--motion-fast) var(--ease-out);
}
.btn:hover { border-color: var(--warp); }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.pri:hover { background: var(--warp-hi); }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
.btn.ghost:hover { color: var(--ink); }
.acts .spacer { margin-left: auto; }
.btn.bplan.on { color: var(--warp-hi); }
.btn.bhandled.on { color: var(--healthy); }
.mini {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border: 1px dashed var(--line); border-radius: var(--r-card);
  color: var(--stale);
}
.mini-title { color: var(--ink); font-weight: 500; }
.mini-why { font-size: 13px; }
.mini-act { margin-left: auto; }
</style>
