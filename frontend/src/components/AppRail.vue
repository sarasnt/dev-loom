<script setup lang="ts">
import { computed } from 'vue'
import type { SyncSource, Boundary } from '../types'
import BoundaryToken from './BoundaryToken.vue'

const props = defineProps<{
  workspace: string
  sync: { sources: SyncSource[]; updated: string }
  model: { name: string; local: boolean }
  boundary: Boundary
  buildBadge?: number
  models?: string[]
  activeModel?: string
  hideModel?: boolean
}>()

const emit = defineEmits<{ (e: 'select-model', name: string): void }>()

function onModelChange(e: Event) {
  emit('select-model', (e.target as HTMLSelectElement).value)
}

// Remote (paid) models leave the machine — reflect that in the flag + boundary token.
// Note: local Ollama's "gpt-oss" must NOT be treated as OpenAI's gpt-*.
function isRemoteModel(m?: string): boolean {
  const s = (m || '').toLowerCase()
  if (s.startsWith('claude')) return true // incl. claude-code (subscription → Anthropic)
  if (s.startsWith('gpt-oss')) return false
  return s.startsWith('gpt-') || s.startsWith('o1') || s.startsWith('o3') || s.startsWith('o4')
}
const modelRemote = computed(() => isRemoteModel(props.activeModel))
const modelBoundary = computed<Boundary>(() =>
  modelRemote.value
    ? { mode: 'remote', label: 'Leaves your machine' }
    : { mode: 'local', label: 'On your machine' },
)

const nav = [
  { to: '/today', label: 'Today', ic: '◉' },
  { to: '/work', label: 'Work', ic: '▤' },
  { to: '/builds', label: 'Builds', ic: '⚡', badge: true },
  { to: '/brainstorm', label: 'Brainstorm', ic: '✎' },
  { to: '/handoffs/h1', label: 'Handoffs', ic: '⇥' },
]
</script>

<template>
  <aside class="rail">
    <div class="brand">
      <span class="mark" aria-hidden="true"></span>
      <span><b>DevLoom</b><small>{{ workspace }}</small></span>
    </div>

    <nav class="nav" aria-label="Primary">
      <RouterLink v-for="n in nav" :key="n.to" :to="n.to" active-class="on">
        <span class="ic" aria-hidden="true">{{ n.ic }}</span> {{ n.label }}
        <span v-if="n.badge && buildBadge" class="badge">{{ buildBadge }}</span>
      </RouterLink>
    </nav>

    <div class="sep"></div>
    <div class="meta">
      <div class="eyebrow">Sync</div>
      <div class="hd" role="img" :aria-label="`Sync: ${sync.sources.map(s => s.label + ' ' + s.state).join(', ')}`">
        <i v-for="s in sync.sources" :key="s.key" :class="s.state" :title="`${s.label}: ${s.state}`"></i>
      </div>
      <div class="upd mono">{{ sync.updated }}</div>
    </div>

    <template v-if="!hideModel">
    <div class="sep"></div>
    <div class="meta">
      <div class="eyebrow">Model</div>
      <div v-if="models && models.length" class="mselect">
        <select
          class="msel"
          :value="activeModel"
          aria-label="Active local model"
          @change="onModelChange"
        >
          <option v-for="m in models" :key="m" :value="m">{{ m }}</option>
        </select>
        <span class="mono tag" :class="{ remote: modelRemote }">· {{ modelRemote ? 'remote' : 'local' }}</span>
      </div>
      <div v-else class="mval">{{ model.name }} <span class="mono tag" :class="{ remote: modelRemote }">· {{ modelRemote ? 'remote' : 'local' }}</span></div>
    </div>
    </template>
    <div class="meta bmeta">
      <div class="eyebrow">Boundary</div>
      <BoundaryToken :boundary="modelBoundary" />
    </div>

    <div class="spring"></div>
    <div class="foot">
      <RouterLink class="set" to="/settings/integrations">⚙ Settings</RouterLink>
      <span class="kbd">⌘K</span>
    </div>
  </aside>
</template>

<style scoped>
.rail {
  background: var(--rail-bg); border-right: 1px solid var(--line);
  padding: 16px 12px; display: flex; flex-direction: column; gap: 6px; min-height: 100%;
}
.brand { display: flex; align-items: center; gap: 9px; padding: 2px 6px 14px; }
.brand .mark {
  width: 22px; height: 22px; border-radius: 6px; border: 1px solid var(--warp);
  background: repeating-linear-gradient(90deg, var(--warp) 0 2px, transparent 2px 5px);
}
.brand b { font-size: 15px; letter-spacing: -0.02em; }
.brand small { display: block; color: var(--faint-text); font-size: 11px; font-weight: 400; }
.nav a {
  display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 8px;
  color: var(--dim); text-decoration: none; font-size: 14px; font-weight: 500;
}
.nav a:hover { color: var(--ink); background: var(--nav-hover); }
.nav a.on { background: var(--warp-weft); color: var(--ink); box-shadow: inset 2px 0 0 var(--warp); }
.nav .ic { width: 18px; text-align: center; }
.nav .badge {
  margin-left: auto; font-family: var(--mono); font-size: 10px; background: var(--failed);
  color: #fff; border-radius: 20px; padding: 0 6px;
}
.sep { height: 1px; background: var(--line); margin: 12px 4px; }
.meta { padding: 2px 8px; }
.hd { display: flex; gap: 6px; margin-top: 5px; }
.hd i { width: 8px; height: 8px; border-radius: 50%; background: var(--healthy); display: inline-block; }
.hd i.syncing { background: var(--warp); }
.hd i.partial { background: var(--warp); }
.hd i.error { background: var(--failed); }
.upd { color: var(--faint-text); font-size: 11px; margin-top: 6px; }
.mval { font-size: 13px; color: var(--ink); margin-top: 3px; }
.mselect { display: flex; align-items: center; gap: 6px; margin-top: 4px; }
.msel {
  flex: 1; min-width: 0; font-size: 12.5px; color: var(--ink);
  background: var(--btn-bg, var(--surface)); border: 1px solid var(--line);
  border-radius: 6px; padding: 4px 6px; cursor: pointer;
}
.msel:hover { border-color: var(--warp); }
.msel:focus { outline: none; border-color: var(--warp); }
.tag { color: var(--faint-text); font-size: 11px; }
.tag.remote { color: var(--warp-hi); }
.bmeta { margin-top: 10px; }
.spring { margin-top: auto; }
.foot { display: flex; align-items: center; justify-content: space-between; padding: 2px 8px; }
.set { color: var(--dim); font-size: 13px; text-decoration: none; }
.set:hover { color: var(--ink); }
.kbd {
  font-family: var(--mono); font-size: 11px; color: var(--faint-text);
  border: 1px solid var(--line); border-radius: 5px; padding: 3px 7px;
}
</style>
