<script setup lang="ts">
// Settings › Capabilities — what the models can actually do: skills, MCP servers and plugins.
// These edit the user's own Claude config through the host agent, so anything set here applies to
// the CLI terminal, background runs and their own `claude` sessions alike.
import { computed, onMounted, ref } from 'vue'
import type { Capabilities } from '../types'
import {
  fetchCapabilities, addMcpServer, removeMcpServer, togglePlugin,
  saveSkill, fetchSkill, removeSkill, installSkillRepo, setSkillsForModels, setMcpForModels,
} from '../api'
import SettingsTabs from '../components/SettingsTabs.vue'

const data = ref<Capabilities | null>(null)
const loading = ref(true)
const busy = ref('')
const flash = ref('')

// Which sections are expanded, remembered across visits — with dozens of skills installed the
// page is otherwise a long scroll to reach MCP or plugins.
type Section = 'skills' | 'mcp' | 'plugins'
const OPEN_KEY = 'devloom.capabilitiesOpen'
function loadOpen(): Record<Section, boolean> {
  try {
    const saved = JSON.parse(localStorage.getItem(OPEN_KEY) || '{}')
    return { skills: saved.skills ?? true, mcp: saved.mcp ?? true, plugins: saved.plugins ?? false }
  } catch {
    return { skills: true, mcp: true, plugins: false }
  }
}
const open = ref<Record<Section, boolean>>(loadOpen())
function toggleSection(s: Section) {
  open.value = { ...open.value, [s]: !open.value[s] }
  try { localStorage.setItem(OPEN_KEY, JSON.stringify(open.value)) } catch { /* ignore */ }
}

async function load() {
  try { data.value = await fetchCapabilities() } catch { data.value = null }
  loading.value = false
}
onMounted(load)

// ---- MCP ----
const mcpForm = ref({ name: '', transport: 'stdio', command: '', args: '', envText: '' })
const mcpOpen = ref(false)
function resetMcp() {
  mcpForm.value = { name: '', transport: 'stdio', command: '', args: '', envText: '' }
}
async function addMcp() {
  const f = mcpForm.value
  if (!f.name.trim() || !f.command.trim() || busy.value) return
  busy.value = 'mcp'; flash.value = ''
  const env: Record<string, string> = {}
  for (const line of f.envText.split('\n')) {
    const i = line.indexOf('=')
    if (i > 0) env[line.slice(0, i).trim()] = line.slice(i + 1).trim()
  }
  try {
    const r = await addMcpServer({
      name: f.name.trim(),
      transport: f.transport,
      command: f.command.trim(),
      args: f.args.split(' ').map((s) => s.trim()).filter(Boolean),
      env,
    })
    flash.value = r.ok ? `Added ${f.name}. New Claude sessions pick it up.` : `Failed: ${r.error ?? 'unknown'}`
    if (r.ok) { resetMcp(); mcpOpen.value = false; await load() }
  } catch { flash.value = 'Could not reach the host agent.' }
  finally { busy.value = '' }
}
async function dropMcp(name: string) {
  if (!confirm(`Remove the MCP server "${name}" from your Claude config?`)) return
  busy.value = 'mcp'
  try { await removeMcpServer(name); await load() } finally { busy.value = '' }
}
// Which servers local/API models may call. Off by default — tool calls have side effects.
function mcpUsedByModels(name: string) {
  return (data.value?.mcpForModels ?? []).includes(name)
}
async function toggleMcpForModels(name: string, on: boolean) {
  const cur = new Set(data.value?.mcpForModels ?? [])
  if (on) cur.add(name); else cur.delete(name)
  busy.value = 'mcp:' + name
  try { data.value = await setMcpForModels([...cur]) }
  catch { flash.value = 'Could not save — is the host agent running?' }
  finally { busy.value = '' }
}

// ---- plugins ----
async function flipPlugin(id: string, enabled: boolean) {
  busy.value = id
  try { await togglePlugin(id, enabled); await load() } finally { busy.value = '' }
}

// ---- skills ----
// Claude Code loads skills itself. For local Ollama models and raw API calls DevLoom injects the
// chosen ones into the system prompt, so capabilities aren't Claude-only.
function usedByModels(key: string) {
  return (data.value?.skillsForModels ?? []).includes(key)
}
async function toggleForModels(key: string, on: boolean) {
  const cur = new Set(data.value?.skillsForModels ?? [])
  if (on) cur.add(key); else cur.delete(key)
  busy.value = key
  try { data.value = await setSkillsForModels([...cur]) }
  catch { flash.value = 'Could not save — is the host agent running?' }
  finally { busy.value = '' }
}
function sourceLabel(s: string) {
  return s === 'personal' ? 'yours' : s.replace(/^plugin:/, '').split('@')[0]
}

// Search + filter: a couple of installed plugins is already dozens of skills, so finding one by
// scrolling doesn't scale. Matches name, description and the source (e.g. type "superpowers").
const skillQuery = ref('')
const onlyEnabled = ref(false)
const allSkills = computed(() => data.value?.skills ?? [])
const enabledCount = computed(() => (data.value?.skillsForModels ?? []).length)
const filteredSkills = computed(() => {
  const q = skillQuery.value.trim().toLowerCase()
  return allSkills.value.filter((s) => {
    if (onlyEnabled.value && !usedByModels(s.key)) return false
    if (!q) return true
    return (
      s.name.toLowerCase().includes(q)
      || s.description.toLowerCase().includes(q)
      || sourceLabel(s.source).toLowerCase().includes(q)
    )
  })
})

const skillOpen = ref(false)
const editing = ref<string | null>(null) // dir being edited, null = new
const skillForm = ref({ dir: '', name: '', description: '', body: '' })
const repoUrl = ref('')

function newSkill() {
  editing.value = null
  skillForm.value = {
    dir: '', name: '', description: '',
    body: '## When to use\n\n## Steps\n\n1. \n',
  }
  skillOpen.value = true
}
async function editSkill(dir: string) {
  busy.value = 'skill'
  try {
    const s = await fetchSkill(dir)
    if (!s.ok) { flash.value = s.error ?? 'Could not read that skill.'; return }
    editing.value = dir
    skillForm.value = { dir: s.dir, name: s.name, description: s.description, body: s.body }
    skillOpen.value = true
  } finally { busy.value = '' }
}
async function storeSkill() {
  const f = skillForm.value
  if (!f.name.trim() || busy.value) return
  busy.value = 'skill'; flash.value = ''
  try {
    const r = await saveSkill({ dir: editing.value ?? f.dir, name: f.name, description: f.description, body: f.body })
    flash.value = r.ok ? `Saved ${f.name}.` : `Failed: ${r.error ?? 'unknown'}`
    if (r.ok) { skillOpen.value = false; await load() }
  } catch { flash.value = 'Could not reach the host agent.' }
  finally { busy.value = '' }
}
async function dropSkill(dir: string) {
  if (!confirm(`Delete the skill "${dir}"? Its folder is removed from ~/.claude/skills.`)) return
  busy.value = 'skill'
  try { await removeSkill(dir); await load() } finally { busy.value = '' }
}
async function installRepo() {
  const url = repoUrl.value.trim()
  if (!url || busy.value) return
  busy.value = 'install'; flash.value = ''
  try {
    const r = await installSkillRepo(url)
    flash.value = r.ok
      ? `Installed into ~/.claude/skills/${r.dir}.`
      : `Failed: ${r.error ?? 'unknown'}`
    if (r.ok) { repoUrl.value = ''; await load() }
  } catch { flash.value = 'Could not reach the host agent.' }
  finally { busy.value = '' }
}
</script>

<template>
  <main class="main">
    <SettingsTabs />
    <div class="head"><h1>Capabilities</h1></div>
    <p class="sub">
      What your models can do. These edit your own Claude configuration
      (<code>~/.claude.json</code>, <code>~/.claude/settings.json</code>, <code>~/.claude/skills</code>),
      so anything here applies to DevLoom's terminals and background runs <em>and</em> to your own
      <code>claude</code> sessions. Changes take effect for newly started sessions.
    </p>

    <div v-if="loading" class="mono empty">loading…</div>
    <template v-else>
      <div v-if="flash" class="flash mono">{{ flash }}</div>
      <div v-if="!data || data.error" class="warnbar mono">
        Host agent unreachable — start it with <b>node agent/devloom-agent.mjs</b> to manage capabilities.
      </div>

      <template v-if="data && !data.error">
        <!-- skills -->
        <section class="block">
          <button class="sechead" :aria-expanded="open.skills" @click="toggleSection('skills')">
            <span class="caret">{{ open.skills ? '▾' : '▸' }}</span>
            <span class="lab mono">Skills</span>
            <span class="count mono">{{ allSkills.length }}</span>
            <span v-if="enabledCount" class="count mono on">{{ enabledCount }} for local models</span>
            <span class="hint mono">reusable instructions, yours and those bundled in plugins</span>
          </button>
          <template v-if="open.skills">
          <p class="explain">
            Claude Code loads these itself. Local Ollama models and API-key models have no such
            mechanism, so tick <b>local models</b> and DevLoom injects that skill's
            instructions into their system prompt — same capabilities whichever model you pick.
            Each one costs context on every turn, so enable the ones that earn it.
          </p>
          <div class="filterbar">
            <input
              v-model="skillQuery"
              class="in mono"
              type="search"
              placeholder="Search skills by name, description or plugin…"
            />
            <label class="tog">
              <input type="checkbox" v-model="onlyEnabled" />
              <span>only enabled</span>
            </label>
            <span class="mono resultn">{{ filteredSkills.length }} / {{ allSkills.length }}</span>
          </div>
          <div class="mlist scroll">
            <div v-for="s in filteredSkills" :key="s.key" class="mrow">
              <div class="grow">
                <div class="mn">{{ s.name }} <span class="tag mono">{{ sourceLabel(s.source) }}</span></div>
                <div class="mdesc">{{ s.description || 'no description' }}</div>
              </div>
              <label class="tog" :title="'Inject into local and API-key models'">
                <input
                  type="checkbox"
                  :checked="usedByModels(s.key)"
                  :disabled="busy !== ''"
                  @change="toggleForModels(s.key, ($event.target as HTMLInputElement).checked)"
                />
                <span>local models</span>
              </label>
              <button v-if="s.editable" class="btn" :disabled="busy !== ''" @click="editSkill(s.dir)">Edit</button>
              <button v-if="s.editable" class="btn ghost" :disabled="busy !== ''" @click="dropSkill(s.dir)">Delete</button>
            </div>
            <div v-if="!allSkills.length" class="empty mono">no skills found</div>
            <div v-else-if="!filteredSkills.length" class="empty mono">
              nothing matches “{{ skillQuery }}”{{ onlyEnabled ? ' among enabled skills' : '' }}
            </div>
          </div>
          <div class="row">
            <button class="btn pri" @click="newSkill">＋ New skill</button>
            <input v-model="repoUrl" class="in mono" placeholder="…or install from a git repo: https://github.com/user/skill" @keydown.enter="installRepo" />
            <button class="btn" :disabled="busy === 'install' || !repoUrl.trim()" @click="installRepo">
              {{ busy === 'install' ? 'Installing…' : 'Install' }}
            </button>
          </div>
          </template>
        </section>

        <!-- MCP servers -->
        <section class="block">
          <button class="sechead" :aria-expanded="open.mcp" @click="toggleSection('mcp')">
            <span class="caret">{{ open.mcp ? '▾' : '▸' }}</span>
            <span class="lab mono">MCP servers</span>
            <span class="count mono">{{ data.mcp.length }}</span>
            <span v-if="data.mcpForModels?.length" class="count mono on">{{ data.mcpForModels.length }} for local models</span>
            <span class="hint mono">tools and data sources the models can call</span>
          </button>
          <template v-if="open.mcp">
          <p class="explain">
            Claude Code speaks MCP natively. Tick <b>local models</b> and DevLoom runs the tool loop
            itself, so an Ollama or API-key model can call that server's tools too. Off by default —
            a tool call has real side effects. Only <b>stdio</b> servers are supported so far;
            a model that can't do tool calling just answers without them.
          </p>
          <div class="mlist">
            <div v-for="m in data.mcp" :key="m.name" class="mrow">
              <div class="grow">
                <div class="mn">{{ m.name }} <span class="tag mono">{{ m.transport }}</span></div>
                <div class="mdesc mono">{{ m.command }} {{ m.args.join(' ') }}<span v-if="m.env.length"> · env: {{ m.env.join(', ') }}</span></div>
              </div>
              <label class="tog" :title="m.transport === 'stdio' ? 'Let local and API-key models call this server' : 'Only stdio servers can be used by local models yet'">
                <input
                  type="checkbox"
                  :checked="mcpUsedByModels(m.name)"
                  :disabled="busy !== '' || m.transport !== 'stdio'"
                  @change="toggleMcpForModels(m.name, ($event.target as HTMLInputElement).checked)"
                />
                <span>local models</span>
              </label>
              <button class="btn ghost" :disabled="busy !== ''" @click="dropMcp(m.name)">Remove</button>
            </div>
            <div v-if="!data.mcp.length" class="empty mono">no MCP servers configured</div>
          </div>
          <button v-if="!mcpOpen" class="btn pri" @click="mcpOpen = true">＋ Add MCP server</button>
          <div v-else class="form">
            <div class="row">
              <input v-model="mcpForm.name" class="in mono narrow" placeholder="name (e.g. context7)" />
              <select v-model="mcpForm.transport" class="in mono narrow">
                <option value="stdio">stdio (command)</option>
                <option value="http">http (url)</option>
                <option value="sse">sse (url)</option>
              </select>
            </div>
            <div class="row">
              <input v-model="mcpForm.command" class="in mono" :placeholder="mcpForm.transport === 'stdio' ? 'command, e.g. npx' : 'https://…'" />
            </div>
            <div v-if="mcpForm.transport === 'stdio'" class="row">
              <input v-model="mcpForm.args" class="in mono" placeholder="args, space separated — e.g. -y @upstash/context7-mcp" />
            </div>
            <div class="row">
              <textarea v-model="mcpForm.envText" class="in mono ta" rows="2" placeholder="env, one KEY=value per line (optional)"></textarea>
            </div>
            <div class="row">
              <button class="btn pri" :disabled="busy === 'mcp' || !mcpForm.name.trim() || !mcpForm.command.trim()" @click="addMcp">Add</button>
              <button class="btn ghost" @click="mcpOpen = false; resetMcp()">Cancel</button>
            </div>
          </div>
          </template>
        </section>

        <!-- plugins -->
        <section class="block">
          <button class="sechead" :aria-expanded="open.plugins" @click="toggleSection('plugins')">
            <span class="caret">{{ open.plugins ? '▾' : '▸' }}</span>
            <span class="lab mono">Plugins</span>
            <span class="count mono">{{ data.plugins.length }}</span>
            <span class="hint mono">installed via Claude Code's marketplace; toggle what's active</span>
          </button>
          <template v-if="open.plugins">
          <p class="explain">
            A plugin bundles skills, commands and hooks. Its <em>skills</em> are listed above and can
            be given to any model; commands and hooks stay Claude Code features.
          </p>
          <div class="mlist">
            <div v-for="p in data.plugins" :key="p.id" class="mrow">
              <div class="grow">
                <div class="mn">{{ p.name }} <span class="tag mono">{{ p.marketplace }}</span>
                  <span v-if="p.missing" class="tag mono warn">not installed</span>
                </div>
                <div class="mdesc mono">{{ p.version || '—' }}</div>
              </div>
              <label class="tog">
                <input type="checkbox" :checked="p.enabled" :disabled="busy === p.id" @change="flipPlugin(p.id, ($event.target as HTMLInputElement).checked)" />
                <span>{{ p.enabled ? 'on' : 'off' }}</span>
              </label>
            </div>
            <div v-if="!data.plugins.length" class="empty mono">no plugins installed</div>
          </div>
          <p class="note mono">Install new plugins with <b>/plugin</b> in a Claude session; they appear here to toggle.</p>
          </template>
        </section>
      </template>
    </template>

    <!-- skill editor -->
    <div v-if="skillOpen" class="modal" @click.self="skillOpen = false">
      <div class="picker">
        <div class="pkhead">
          <span class="mono pkpath">{{ editing ? 'Edit skill · ' + editing : 'New skill' }}</span>
          <button class="btn ghost" @click="skillOpen = false">✕</button>
        </div>
        <div class="pkbody">
          <label class="flab mono">Name</label>
          <input v-model="skillForm.name" class="in mono" placeholder="e.g. release-checklist" />
          <label class="flab mono">Description <span class="hint">— when Claude should reach for it</span></label>
          <input v-model="skillForm.description" class="in mono" placeholder="Use when cutting a release: verifies changelog, tags and CI." />
          <label class="flab mono">Instructions (markdown)</label>
          <textarea v-model="skillForm.body" class="in mono ta tall" rows="14"></textarea>
        </div>
        <div class="pkfoot">
          <span class="mono hint">Saved to ~/.claude/skills/&lt;name&gt;/SKILL.md</span>
          <button class="btn pri" :disabled="busy === 'skill' || !skillForm.name.trim()" @click="storeSkill">Save skill</button>
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head h1 { font-size: 22px; margin-bottom: 10px; }
.sub { color: var(--dim); font-size: 13px; margin: 0 0 18px; max-width: 78ch; line-height: 1.55; }
.sub code, .note b { font-family: var(--mono); font-size: 11.5px; background: var(--bg); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; color: var(--warp-hi); }
.empty { color: var(--faint-text); padding: 12px 0; font-size: 12.5px; }
.flash { font-size: 12.5px; color: var(--warp-hi); border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }
.warnbar { font-size: 12.5px; color: var(--dim); border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }
.block { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 16px; margin-bottom: 14px; }
.lab { font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--faint-text); margin-bottom: 12px; }
.lab .hint { text-transform: none; letter-spacing: 0; color: var(--faint-text); }
.explain { color: var(--dim); font-size: 12.5px; margin: -4px 0 12px; max-width: 76ch; line-height: 1.55; }
/* collapsible section header */
.sechead {
  display: flex; align-items: center; gap: 10px; width: 100%; padding: 0; margin: 0 0 12px;
  background: transparent; border: 0; cursor: pointer; text-align: left; color: inherit;
}
.sechead:hover .lab { color: var(--ink); }
.sechead .lab { margin-bottom: 0; }
.sechead .caret { color: var(--faint-text); font-size: 11px; width: 10px; }
.sechead .count {
  font-size: 10px; color: var(--faint-text); border: 1px solid var(--line);
  border-radius: 20px; padding: 1px 8px;
}
.sechead .count.on { color: var(--warp-hi); border-color: var(--warp); }
.sechead .hint {
  font-size: 11px; color: var(--faint-text); margin-left: auto; text-align: right;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* skill search */
.filterbar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.filterbar .in { flex: 1; }
.resultn { font-size: 11px; color: var(--faint-text); white-space: nowrap; }
.mlist.scroll { max-height: 420px; overflow-y: auto; padding-right: 4px; }
.mlist { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.mrow { display: flex; align-items: center; gap: 12px; border: 1px solid var(--line); border-radius: 8px; padding: 9px 12px; background: var(--bg); }
.mrow .grow { flex: 1; min-width: 0; }
.mn { font-size: 13px; color: var(--ink); display: flex; align-items: center; gap: 8px; }
.mdesc { font-size: 11.5px; color: var(--faint-text); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tag { font-size: 9px; text-transform: uppercase; letter-spacing: 0.07em; border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; color: var(--faint-text); }
.tag.warn { color: var(--chip-fail, #d88); border-color: var(--failed, #a55); }
.tog { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; color: var(--dim); cursor: pointer; }
.row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.form { border-top: 1px solid var(--line); padding-top: 12px; margin-top: 4px; }
.in { flex: 1; min-width: 180px; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: 7px 10px; color: var(--ink); font-size: 12px; }
.in:focus { outline: none; border-color: var(--warp); }
.in.narrow { flex: 0 0 auto; width: 200px; }
.ta { resize: vertical; font-family: var(--mono); width: 100%; box-sizing: border-box; }
.ta.tall { min-height: 220px; }
.note { font-size: 11px; color: var(--faint-text); margin: 4px 0 0; }
.btn { font-size: 13px; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; white-space: nowrap; }
.btn:hover { border-color: var(--warp); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
.modal { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 50; }
.picker { width: 680px; max-width: 94vw; max-height: 86vh; display: flex; flex-direction: column; background: var(--surface); border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.pkhead { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--line); }
.pkpath { flex: 1; font-size: 12px; color: var(--warp-hi); }
.pkbody { flex: 1; overflow: auto; padding: 14px; }
.flab { display: block; font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint-text); margin: 12px 0 5px; }
.flab:first-child { margin-top: 0; }
.flab .hint { text-transform: none; letter-spacing: 0; }
.pkbody .in { width: 100%; box-sizing: border-box; }
.pkfoot { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-top: 1px solid var(--line); }
.pkfoot .hint { flex: 1; font-size: 11.5px; color: var(--faint-text); }
</style>
