<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { Recommendation } from '../types'
import Mono from './Mono.vue'

defineProps<{ item: Recommendation }>()

const router = useRouter()

const primaryAction = (actions: string[]) => actions[0]
const restActions = (actions: string[]) => actions.slice(1)

// Wire the primary action to the real thing: a build → its on-machine analysis; a GitHub
// PR/issue → open on GitHub (/issues/N redirects to the PR when N is a PR).
function runPrimary(item: Recommendation) {
  if (item.type === 'build') {
    router.push(`/builds/${item.id}`)
    return
  }
  if (item.source === 'GitHub' && item.id.includes('#')) {
    window.open(`https://github.com/${item.id.replace('#', '/issues/')}`, '_blank', 'noopener')
  }
}
</script>

<template>
  <!-- Stale items render as a quiet mini-row, not a full card -->
  <div v-if="item.type === 'stale'" class="mini">
    <span class="mini-title">{{ item.title }}</span>
    <Mono class="chip stale">{{ item.chips[0]?.label }}</Mono>
    <span class="mini-why">{{ item.why }}</span>
    <button class="btn ghost mini-act">{{ primaryAction(item.actions) }}</button>
  </div>

  <article v-else :class="['card', { lead: item.lead }]">
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
    </div>

    <div class="acts">
      <button class="btn pri" @click="runPrimary(item)">{{ primaryAction(item.actions) }} ▸</button>
      <button v-for="a in restActions(item.actions)" :key="a" class="btn ghost">{{ a }}</button>
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
.chip.fail { color: var(--chip-fail); border-color: var(--failed); }
.chip.stale { color: var(--chip-stale); border-color: var(--stale); }
.acts { display: flex; gap: 8px; margin-top: 12px; }
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
.mini {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border: 1px dashed var(--line); border-radius: var(--r-card);
  color: var(--stale);
}
.mini-title { color: var(--ink); font-weight: 500; }
.mini-why { font-size: 13px; }
.mini-act { margin-left: auto; }
</style>
