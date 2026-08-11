<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useDashboardStore } from '../stores/dashboard'
import { fleetRuns, launchRun, cancelRun, fetchRepos } from '../api'
import type { AgentRun, RepoView, RunLaunch } from '../types'

const store = useDashboardStore()
const { models } = storeToRefs(store)

const runs = ref<AgentRun[]>([])
const repos = ref<RepoView[]>([])
const loading = ref(true)
const flash = ref('')
const expanded = ref<string>('')
let timer: number | undefined

// Attention-routed groups (the board proper arrives in the next slice).
const needsReview = computed(() => runs.value.filter((r) => r.status === 'review' || r.status === 'failed'))
const running = computed(() => runs.value.filter((r) => r.status === 'running'))
const recent = computed(() => runs.value.filter((r) => ['done', 'canceled', 'ended'].includes(r.status)))

async function refresh() {
  try { runs.value = await fleetRuns() } catch { /* keep last */ }
}
onMounted(async () => {
  store.ensureLoaded() // populate the model list for the launch dialog
  await refresh()
  try { repos.value = (await fetchRepos()).repos } catch { /* agent offline */ }
  loading.value = false
  timer = window.setInterval(refresh, 4000)
})
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })

function repoName(path: string): string {
  const p = (path || '').replace(/\\/g, '/')
  return p.substring(p.lastIndexOf('/') + 1)
}
function elapsed(r: AgentRun): string {
  const start = r.startedAt ? new Date(r.startedAt).getTime() : new Date(r.createdAt).getTime()
  const end = r.finishedAt ? new Date(r.finishedAt).getTime() : Date.now()
  const s = Math.max(0, Math.round((end - start) / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  return m < 60 ? `${m}m ${s % 60}s` : `${Math.floor(m / 60)}h ${m % 60}m`
}
const statusLabel: Record<string, string> = {
  running: 'running', review: 'needs review', done: 'done', failed: 'failed',
  canceled: 'canceled', active: 'active', ended: 'ended',
}

// ---- launch dialog ----
const dlg = ref(false)
const busy = ref(false)
const form = ref<RunLaunch>({ repoId: '', prompt: '', model: undefined, permission: 'readonly', allowTests: false, isolate: false })
function openLaunch() {
  form.value = { repoId: repos.value[0]?.id ?? '', prompt: '', model: undefined, permission: 'readonly', allowTests: false, isolate: false }
  flash.value = ''
  dlg.value = true
}
async function submit() {
  if (busy.value || !form.value.repoId || !form.value.prompt.trim()) return
  busy.value = true; flash.value = ''
  try {
    const run = await launchRun({ ...form.value, model: form.value.model || undefined })
    dlg.value = false
    runs.value = [run, ...runs.value]
  } catch {
    flash.value = form.value.permission === 'edit'
      ? 'Launch failed — an edit run needs a clean working tree (commit or stash first). Worktree isolation lands in a later update.'
      : 'Launch failed — is the host agent running?'
  } finally { busy.value = false }
}
async function stop(r: AgentRun) {
  try { const u = await cancelRun(r.id); runs.value = runs.value.map((x) => (x.id === u.id ? u : x)) } catch { /* ignore */ }
}
</script>

<template>
  <main class="main">
    <div class="head">
      <h1>Fleet</h1>
      <span v-if="needsReview.length" class="attn mono">{{ needsReview.length }} need review</span>
      <span class="grow"></span>
      <button class="btn pri" @click="openLaunch">＋ New run</button>
    </div>
    <p class="sub">Fan out background AI runs and check back. Each runs headless in its repo; read-only proposes, edit changes files (never pushes).</p>

    <div v-if="flash" class="flash mono">{{ flash }}</div>
    <div v-if="loading" class="mono empty">loading…</div>

    <template v-else>
      <section v-if="needsReview.length" class="grp">
        <div class="glab mono">Needs review</div>
        <div v-for="r in needsReview" :key="r.id" class="run" :class="r.status">
          <div class="rtop">
            <span class="rstatus mono" :class="r.status">{{ statusLabel[r.status] }}</span>
            <span class="rtitle">{{ r.title }}</span>
            <span class="rbadges mono">
              <span class="rb">{{ repoName(r.repoPath) }}</span>
              <span class="rb" :class="r.permission || ''">{{ r.permission }}</span>
              <span v-if="r.model" class="rb">{{ r.model }}</span>
              <span class="rb">{{ elapsed(r) }}</span>
            </span>
            <button class="link mono" @click="expanded = expanded === r.id ? '' : r.id">{{ expanded === r.id ? 'hide' : 'view' }}</button>
          </div>
          <pre v-if="expanded === r.id" class="rbody mono">{{ r.error || r.resultSummary || '(no output)' }}</pre>
        </div>
      </section>

      <section v-if="running.length" class="grp">
        <div class="glab mono">Running</div>
        <div v-for="r in running" :key="r.id" class="run running">
          <div class="rtop">
            <span class="rstatus mono running"><span class="spin"></span>running</span>
            <span class="rtitle">{{ r.title }}</span>
            <span class="rbadges mono">
              <span class="rb">{{ repoName(r.repoPath) }}</span>
              <span class="rb" :class="r.permission || ''">{{ r.permission }}</span>
              <span v-if="r.model" class="rb">{{ r.model }}</span>
              <span class="rb">{{ elapsed(r) }}</span>
            </span>
            <button class="link mono danger" @click="stop(r)">cancel</button>
          </div>
        </div>
      </section>

      <section v-if="recent.length" class="grp">
        <div class="glab mono">Recent</div>
        <div v-for="r in recent" :key="r.id" class="run recent">
          <div class="rtop">
            <span class="rstatus mono" :class="r.status">{{ statusLabel[r.status] }}</span>
            <span class="rtitle">{{ r.title }}</span>
            <span class="rbadges mono">
              <span class="rb">{{ repoName(r.repoPath) }}</span>
              <span v-if="r.model" class="rb">{{ r.model }}</span>
              <span class="rb">{{ elapsed(r) }}</span>
            </span>
            <button class="link mono" @click="expanded = expanded === r.id ? '' : r.id">{{ expanded === r.id ? 'hide' : 'view' }}</button>
          </div>
          <pre v-if="expanded === r.id" class="rbody mono">{{ r.error || r.resultSummary || '(no output)' }}</pre>
        </div>
      </section>

      <div v-if="!runs.length" class="mono empty">No runs yet — launch one to fan out background work.</div>
    </template>

    <!-- launch dialog -->
    <div v-if="dlg" class="over" @click.self="dlg = false">
      <div class="box">
        <div class="bh mono">New background run</div>
        <label class="fld"><span class="flab mono">Repository</span>
          <select v-model="form.repoId" class="in mono">
            <option v-for="r in repos" :key="r.id" :value="r.id">{{ r.name }} · {{ r.branch }}</option>
          </select>
        </label>
        <label class="fld col"><span class="flab mono">Task</span>
          <textarea v-model="form.prompt" class="in mono ta" rows="4" placeholder="e.g. Add input validation to the config loader and a test for it"></textarea>
        </label>
        <label class="fld"><span class="flab mono">Model</span>
          <select v-model="form.model" class="in mono">
            <option :value="undefined">Claude (default)</option>
            <option v-for="m in models" :key="m" :value="m">{{ m }}</option>
          </select>
        </label>
        <div class="fld"><span class="flab mono">Permission</span>
          <div class="perm">
            <label class="pr"><input type="radio" value="readonly" v-model="form.permission" /> Read-only<span class="mono">proposes a plan; writes nothing</span></label>
            <label class="pr"><input type="radio" value="edit" v-model="form.permission" /> Edit in repo<span class="mono">changes files; never pushes/deploys</span></label>
          </div>
        </div>
        <label v-if="form.permission === 'edit'" class="fld ck"><input type="checkbox" v-model="form.allowTests" /> <span>Let it run tests</span></label>
        <p v-if="form.permission === 'edit'" class="warn mono">Edit runs need a clean working tree (worktree isolation comes in a later update).</p>
        <div class="bf">
          <button class="btn ghost" @click="dlg = false">Cancel</button>
          <button class="btn pri" :disabled="busy || !form.repoId || !form.prompt.trim()" @click="submit">{{ busy ? 'Launching…' : 'Launch run ▸' }}</button>
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
.head h1 { font-size: 26px; }
.grow { flex: 1; }
.attn { font-size: 12px; color: var(--on-warp); background: var(--warp); border-radius: 6px; padding: 2px 9px; }
.sub { color: var(--dim); font-size: 13px; margin: 0 0 16px; max-width: 76ch; }
.empty { color: var(--faint-text); padding: 20px 0; }
.flash { color: var(--warp-hi); margin-bottom: 10px; font-size: 12px; }
.grp { margin-bottom: 18px; }
.glab { font-size: 10px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--faint-text); margin-bottom: 8px; }
.run { border: 1px solid var(--line); border-radius: 10px; background: var(--surface); padding: 10px 14px; margin-bottom: 8px; }
.run.review { border-left: 2px solid var(--warp); }
.run.failed { border-left: 2px solid var(--failed, #a55); }
.run.running { border-left: 2px solid var(--warp-hi); }
.rtop { display: flex; align-items: center; gap: 12px; }
.rtitle { flex: 1; font-size: 14px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rstatus { font-size: 10px; text-transform: uppercase; letter-spacing: 0.08em; padding: 2px 7px; border-radius: 5px; border: 1px solid var(--line); color: var(--dim); display: inline-flex; align-items: center; gap: 5px; }
.rstatus.review { color: var(--warp-hi); border-color: var(--warp); }
.rstatus.failed { color: var(--chip-fail, #d88); border-color: var(--failed, #a55); }
.rstatus.running { color: var(--warp-hi); border-color: var(--warp); }
.rstatus.done { color: var(--healthy); }
.rbadges { display: flex; gap: 6px; }
.rb { font-size: 10px; color: var(--faint-text); border: 1px solid var(--line); border-radius: 5px; padding: 1px 6px; }
.rb.edit { color: var(--warp-hi); border-color: var(--warp); }
.rb.readonly { color: var(--healthy); }
.link { background: transparent; border: 0; color: var(--dim); font-size: 12px; cursor: pointer; }
.link:hover { color: var(--ink); }
.link.danger { color: var(--chip-fail, #d88); }
.rbody { margin: 10px 0 0; padding: 10px 12px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; font-size: 12px; color: var(--dim); white-space: pre-wrap; max-height: 320px; overflow: auto; }
.spin { width: 8px; height: 8px; border-radius: 50%; border: 2px solid var(--warp); border-top-color: transparent; display: inline-block; animation: sp 0.7s linear infinite; }
@keyframes sp { to { transform: rotate(360deg); } }
/* dialog */
.over { position: fixed; inset: 0; z-index: 60; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; }
.box { width: min(560px, 92vw); background: var(--surface); border: 1px solid var(--line); border-radius: 12px; padding: 16px; }
.bh { font-size: 12px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--warp-hi); margin-bottom: 12px; }
.fld { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.fld.col { flex-direction: column; align-items: stretch; gap: 6px; }
.fld.ck { gap: 8px; }
.flab { font-size: 12px; color: var(--dim); min-width: 96px; }
.in { flex: 1; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; color: var(--ink); padding: 7px 10px; font-size: 13px; }
.ta { resize: vertical; font-family: var(--mono); }
.perm { display: flex; flex-direction: column; gap: 6px; }
.pr { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--ink); }
.pr .mono { font-size: 11px; color: var(--faint-text); }
.warn { font-size: 11px; color: var(--warp-hi); margin: 0 0 8px; }
.bf { display: flex; justify-content: flex-end; gap: 8px; margin-top: 6px; }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 7px 13px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
