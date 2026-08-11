<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { Handoff, RepoView } from '../types'
import { fetchHandoff, fetchRepos, createBrainstormSession } from '../api'
import { useDashboardStore } from '../stores/dashboard'
import SourceChip from '../components/SourceChip.vue'
import LoomLoader from '../components/LoomLoader.vue'

const handoffSteps = [
  'Gathering the build evidence…',
  'Assembling the handoff artifact…',
  'Setting safety constraints…',
]

const route = useRoute()
const router = useRouter()
const store = useDashboardStore()
const data = ref<Handoff | null>(null)
const loading = ref(true)
const copied = ref(false)
const menu = ref(false)                                   // Run split-button dropdown
const running = ref(false)
const picker = ref<{ open: boolean; mode: 'chat' | 'cli' }>({ open: false, mode: 'chat' })
const repos = ref<RepoView[]>([])
let reposLoaded = false

// A handoff can be handed off only when it targets a real repo (not the empty state).
const runnable = computed(() => !!data.value && data.value.id !== 'none' && !!data.value.repo && data.value.repo !== '—')

onMounted(async () => {
  data.value = await fetchHandoff(String(route.params.id ?? 'h1'))
  loading.value = false
})

async function copy() {
  if (!data.value) return
  try {
    await navigator.clipboard.writeText(data.value.rendered)
    copied.value = true
    setTimeout(() => (copied.value = false), 1800)
  } catch {
    copied.value = false
  }
}

// Download the artifact as Markdown.
function exportMd() {
  if (!data.value) return
  const safe = (data.value.branch || 'handoff').replace(/[^a-z0-9._-]+/gi, '-')
  const blob = new Blob([data.value.rendered], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `handoff-${safe}.md`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

async function ensureRepos(): Promise<RepoView[]> {
  if (!reposLoaded) {
    try { repos.value = (await fetchRepos()).repos } catch { repos.value = [] }
    reposLoaded = true
  }
  return repos.value
}

// Find the tracked repo that matches the handoff's owner/name slug (exact slug, then bare name).
function bestMatch(slug: string, list: RepoView[]): RepoView | null {
  const s = slug.toLowerCase()
  const name = s.split('/').pop() || s
  return list.find((r) => (r.slug || '').toLowerCase() === s)
    || list.find((r) => ((r.slug || '').toLowerCase().split('/').pop() || r.name.toLowerCase()) === name)
    || null
}

// Run the handoff: create a session bound to the resolved repo, seed it with the artifact, and
// open Brainstorm. 'cli' = interactive Claude Code terminal; 'chat' = a normal brainstorm turn.
async function run(mode: 'chat' | 'cli') {
  menu.value = false
  if (!data.value || !runnable.value) return
  const list = await ensureRepos()
  const match = bestMatch(data.value.repo, list)
  if (match) { await launch(match, mode); return }
  picker.value = { open: true, mode } // no confident match → let the user choose
}

async function launch(repo: RepoView, mode: 'chat' | 'cli') {
  if (!data.value || running.value) return
  running.value = true
  try {
    // A local-only repo may only use local models for chat; claude-cli is always allowed.
    const model = mode === 'cli' ? 'claude-cli' : store.modelFor('brainstorm')
    const title = `Fix CI on ${data.value.branch}`
    const s = await createBrainstormSession(title, repo.path, model)
    store.setPendingSeed(s.id, data.value.rendered, mode)
    picker.value.open = false
    router.push({ path: '/brainstorm', query: { session: s.id } })
  } finally {
    running.value = false
  }
}
</script>

<template>
  <main class="wrap">
    <div v-if="loading" class="loadwrap"><LoomLoader :steps="handoffSteps" :est-ms="16000" /></div>
    <template v-else-if="data">
      <div class="head">
        <h1>Agent handoff — {{ data.title }}</h1>
        <span class="when mono">target: {{ data.target }} · <span class="local">⌂ local</span></span>
      </div>

      <pre class="artifact mono">{{ data.rendered }}</pre>

      <section class="safety">
        <div class="st mono">Safety — locked defaults, editable</div>
        <div v-for="a in data.safety.allow" :key="a" class="yes mono">✓ {{ a }}</div>
        <div v-for="f in data.safety.forbid" :key="f" class="no mono">✗ {{ f }}</div>
      </section>

      <div class="footer">
        <div class="chips">
          <SourceChip v-for="s in data.sources" :key="s.id" :ref-item="s" />
        </div>
        <div class="actions">
          <button class="btn" @click="copy">{{ copied ? 'Copied ✓' : 'Copy' }}</button>
          <button class="btn" @click="exportMd">Export .md</button>
          <div class="runwrap">
            <button
              class="btn pri run"
              :disabled="!runnable || running"
              :title="runnable ? 'Hand this brief to an agent' : 'No target repo — open a real failing build'"
              @click="menu = !menu"
            >{{ running ? 'Starting…' : 'Run ▸' }}</button>
            <div v-if="menu" class="runmenu" @click.self="menu = false">
              <button class="rmi" @click="run('cli')">
                ⌨ Run in Claude CLI<span class="mono">interactive terminal in the repo · you approve edits</span>
              </button>
              <button class="rmi" @click="run('chat')">
                ✎ Run in Brainstorm<span class="mono">chat it through with your selected model</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- repo picker: shown when the handoff's slug doesn't match a tracked repo -->
      <div v-if="picker.open" class="pickover" @click.self="picker.open = false">
        <div class="pickbox">
          <div class="pkhead mono">
            Run in which repository?
            <span class="pksub">handoff targets <b>{{ data.repo }}</b> — not matched to a tracked repo</span>
          </div>
          <div class="pklist">
            <button v-for="r in repos" :key="r.id" class="pkrow" @click="launch(r, picker.mode)">
              <span class="pkname">{{ r.name }}</span>
              <span class="pkslug mono">{{ r.slug || r.path }}</span>
            </button>
            <div v-if="!repos.length" class="pkempty mono">
              No repositories tracked. Add the repo on the Repositories page (or start the host agent), then try again.
            </div>
          </div>
          <div class="pkfoot">
            <button class="btn ghost" @click="picker.open = false">Cancel</button>
          </div>
        </div>
      </div>
    </template>
  </main>
</template>

<style scoped>
.wrap { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 16px; }
.head h1 { font-size: 22px; }
.when { font-size: 12px; color: var(--faint-text); }
.when .local { color: var(--warp-hi); }
.empty { color: var(--faint-text); padding: 24px 0; }
.loadwrap { display: flex; justify-content: center; padding: 64px 0; }
.artifact {
  font-size: 12.5px; line-height: 1.7; background: var(--bg); border: 1px solid var(--line);
  border-radius: var(--r-card); padding: 16px; color: var(--dim); white-space: pre-wrap; margin: 0;
}
.safety { border: 1px solid var(--warp); border-radius: 8px; padding: 12px 14px; margin-top: 12px; background: var(--warp-weft); }
.st { font-size: 11px; letter-spacing: 0.12em; color: var(--warp-hi); text-transform: uppercase; margin-bottom: 8px; }
.safety div { font-size: 12.5px; padding: 2px 0; }
.yes { color: var(--healthy); }
.no { color: var(--chip-fail); }
.footer { display: flex; align-items: center; gap: 10px; margin-top: 14px; }
.chips { display: flex; flex-wrap: wrap; gap: 7px; }
.actions { margin-left: auto; display: flex; gap: 8px; }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); }
.btn:hover { border-color: var(--warp); }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn:disabled:hover { border-color: var(--line); }
/* Run split button + menu */
.runwrap { position: relative; display: inline-flex; }
.runmenu {
  position: absolute; bottom: calc(100% + 6px); right: 0; z-index: 30; min-width: 300px;
  background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 6px;
  box-shadow: 0 10px 28px rgba(0,0,0,0.4);
}
.rmi {
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px; width: 100%;
  text-align: left; background: transparent; border: 0; border-radius: 7px; padding: 9px 11px;
  color: var(--ink); font-size: 13px; cursor: pointer;
}
.rmi:hover { background: var(--nav-hover); }
.rmi .mono { font-size: 11px; color: var(--faint-text); }
/* repo picker overlay */
.pickover { position: fixed; inset: 0; z-index: 60; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; }
.pickbox { width: min(560px, 92vw); max-height: 78vh; display: flex; flex-direction: column; background: var(--surface); border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.pkhead { padding: 14px 16px; border-bottom: 1px solid var(--line); font-size: 13px; display: flex; flex-direction: column; gap: 4px; }
.pkhead .pksub { font-size: 11px; color: var(--faint-text); text-transform: none; letter-spacing: 0; }
.pklist { overflow: auto; padding: 6px; }
.pkrow { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; width: 100%; text-align: left; background: transparent; border: 0; border-radius: 8px; padding: 9px 11px; cursor: pointer; color: var(--ink); }
.pkrow:hover { background: var(--nav-hover); }
.pkname { font-size: 13px; }
.pkslug { font-size: 11px; color: var(--faint-text); }
.pkempty { padding: 18px 12px; color: var(--faint-text); font-size: 12px; }
.pkfoot { padding: 10px 14px; border-top: 1px solid var(--line); display: flex; justify-content: flex-end; }
</style>
