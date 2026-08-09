<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { WorkRow } from '../types'
import { fetchWork } from '../api'
import LoomLoader from '../components/LoomLoader.vue'

const router = useRouter()
const rows = ref<WorkRow[]>([])
const loading = ref(true)
const filters = ['All', 'PRs', 'Reviews', 'Tasks', 'Builds', 'Calendar', 'Notes', 'mine', 'stale']
const active = ref('All')
// Status sub-filter: 'open' (open & ongoing, the default), 'all', or an exact status string.
const statusFilter = ref('open')

// Which parents are expanded (children shown) and which rows show their description.
const expandedChildren = ref<Set<string>>(new Set())
const expandedDesc = ref<Set<string>>(new Set())

onMounted(async () => {
  rows.value = await fetchWork()
  loading.value = false
})

function matchesFilter(r: WorkRow): boolean {
  switch (active.value) {
    case 'All': return true
    case 'PRs': return r.type === 'pr'
    case 'Reviews': return r.type === 'review'
    case 'Tasks': return r.type === 'task'
    case 'Builds': return r.type === 'build'
    case 'Calendar': return r.type === 'calendar' || r.source === 'Calendar'
    case 'Notes': return r.type === 'doc' || r.source === 'Notion'
    case 'mine': return r.status.toLowerCase().includes('mine')
    case 'stale': return r.type === 'stale' || r.statusTone === 'stale'
    default: return true
  }
}

// A row is "done" when its status reads as finished (or its tone is healthy = Jira done).
function isDone(r: WorkRow): boolean {
  return (
    r.statusTone === 'healthy' ||
    /\b(done|closed|resolved|complete|completed|merged|cancelled|canceled)\b/i.test(r.status)
  )
}

const typeFiltered = computed(() => rows.value.filter(matchesFilter))
// Distinct statuses in the current type view, for the status dropdown.
const statuses = computed(() =>
  [...new Set(typeFiltered.value.map((r) => r.status).filter(Boolean))].sort(),
)
function matchesStatus(r: WorkRow): boolean {
  if (statusFilter.value === 'all') return true
  if (statusFilter.value === 'open') return !isDone(r)
  return r.status === statusFilter.value
}

const filtered = computed(() => typeFiltered.value.filter(matchesStatus))
const idsInView = computed(() => new Set(filtered.value.map((r) => r.id)))

// Top-level rows: no parent, or a parent that isn't in the current (filtered) view.
const topLevel = computed(() =>
  filtered.value.filter((r) => !r.parentId || !idsInView.value.has(r.parentId)),
)
function childrenOf(id: string): WorkRow[] {
  return filtered.value.filter((r) => r.parentId === id)
}
function hasChildren(r: WorkRow): boolean {
  return childrenOf(r.id).length > 0
}

const sourceCount = computed(() => new Set(rows.value.map((r) => r.source)).size)

function externalUrl(r: WorkRow): string | null {
  // GitHub item ids are "owner/repo#number". PRs live under /pull, issues under /issues.
  if (r.source === 'GitHub' && r.id.includes('#')) {
    const [repo, num] = r.id.split('#')
    const path = r.type === 'pr' ? 'pull' : 'issues'
    return `https://github.com/${repo}/${path}/${num}`
  }
  return null
}
function actionable(r: WorkRow): boolean {
  return r.type === 'build' || externalUrl(r) !== null || !!r.description
}

function toggleIn(current: Set<string>, id: string): Set<string> {
  const next = new Set(current)
  next.has(id) ? next.delete(id) : next.add(id)
  return next
}
function toggleChildren(id: string) {
  expandedChildren.value = toggleIn(expandedChildren.value, id)
}
function toggleDesc(id: string) {
  expandedDesc.value = toggleIn(expandedDesc.value, id)
}

// Row body click: build → analysis; GitHub PR/issue → GitHub; otherwise reveal description.
function open(r: WorkRow) {
  if (r.type === 'build') {
    router.push(`/builds/${r.id}`)
    return
  }
  const url = externalUrl(r)
  if (url) {
    window.open(url, '_blank', 'noopener')
    return
  }
  if (r.description) toggleDesc(r.id)
}
</script>

<template>
  <main class="main">
    <div class="head">
      <h1>Work</h1>
      <span class="when mono">{{ filtered.length }} items · {{ sourceCount }} sources</span>
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

      <select v-model="statusFilter" class="statussel mono" aria-label="Filter by status">
        <option value="open">Open &amp; ongoing</option>
        <option value="all">All statuses</option>
        <optgroup label="Status">
          <option v-for="s in statuses" :key="s" :value="s">{{ s }}</option>
        </optgroup>
      </select>
    </div>

    <div v-if="loading" class="loadwrap"><LoomLoader label="loading work…" /></div>
    <div v-else-if="!filtered.length" class="mono empty">nothing here in “{{ active }}”.</div>
    <template v-else>
      <template v-for="r in topLevel" :key="r.id">
        <!-- parent / top-level row -->
        <div
          class="wi"
          :class="{ act: actionable(r) }"
          :role="actionable(r) ? 'button' : undefined"
          :tabindex="actionable(r) ? 0 : undefined"
          @click="actionable(r) && open(r)"
          @keydown.enter="actionable(r) && open(r)"
        >
          <button
            v-if="hasChildren(r)"
            class="caret"
            :aria-expanded="expandedChildren.has(r.id)"
            :aria-label="expandedChildren.has(r.id) ? 'Collapse subtasks' : 'Expand subtasks'"
            @click.stop="toggleChildren(r.id)"
          >
            {{ expandedChildren.has(r.id) ? '▾' : '▸' }}
          </button>
          <span v-else class="caret-spacer" aria-hidden="true"></span>

          <span class="g mono" aria-hidden="true">{{ r.glyph }}</span>
          <span class="ti">{{ r.title }}</span>
          <span class="mt mono">
            <span v-if="hasChildren(r)" class="subcount">{{ childrenOf(r.id).length }} subtasks</span>
            <span class="dot" :class="r.statusTone" aria-hidden="true"></span> {{ r.status }}
            <span v-for="m in r.meta" :key="m">{{ m }}</span>
            <span v-if="r.type === 'build'" class="go" aria-hidden="true">analyze ›</span>
            <span v-else-if="externalUrl(r)" class="go" aria-hidden="true">open ↗</span>
            <button
              v-else-if="r.description"
              class="descbtn"
              :aria-expanded="expandedDesc.has(r.id)"
              @click.stop="toggleDesc(r.id)"
            >
              {{ expandedDesc.has(r.id) ? 'collapse description ▴' : 'expand description ▾' }}
            </button>
          </span>
        </div>
        <div v-if="expandedDesc.has(r.id) && r.description" class="desc">{{ r.description }}</div>

        <!-- children (Jira sub-tasks), shown when the parent is expanded -->
        <template v-if="expandedChildren.has(r.id)">
          <template v-for="c in childrenOf(r.id)" :key="c.id">
            <div
              class="wi child"
              :class="{ act: actionable(c) }"
              :role="actionable(c) ? 'button' : undefined"
              :tabindex="actionable(c) ? 0 : undefined"
              @click="actionable(c) && open(c)"
              @keydown.enter="actionable(c) && open(c)"
            >
              <span class="caret-spacer" aria-hidden="true"></span>
              <span class="g mono" aria-hidden="true">{{ c.glyph }}</span>
              <span class="ti">{{ c.title }}</span>
              <span class="mt mono">
                <span class="dot" :class="c.statusTone" aria-hidden="true"></span> {{ c.status }}
                <span v-for="m in c.meta" :key="m">{{ m }}</span>
                <button
                  v-if="c.description"
                  class="descbtn"
                  :aria-expanded="expandedDesc.has(c.id)"
                  @click.stop="toggleDesc(c.id)"
                >
                  {{ expandedDesc.has(c.id) ? 'collapse description ▴' : 'expand description ▾' }}
                </button>
              </span>
            </div>
            <div v-if="expandedDesc.has(c.id) && c.description" class="desc child">{{ c.description }}</div>
          </template>
        </template>
      </template>
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
  border-radius: 20px; color: var(--dim); background: transparent; cursor: pointer;
}
.fchip:hover { border-color: var(--warp); }
.fchip.on { background: var(--warp-weft); border-color: var(--warp); color: var(--ink); }
.statussel {
  margin-left: auto; font-size: 12px; color: var(--ink); background: var(--chip-bg);
  border: 1px solid var(--line); border-radius: 6px; padding: 5px 9px; cursor: pointer;
}
.statussel:hover { border-color: var(--warp); }
.statussel:focus { outline: none; border-color: var(--warp); }
.wi {
  display: flex; align-items: center; gap: 12px; padding: 12px 14px;
  border: 1px solid var(--line); border-radius: 10px; background: var(--surface); margin-bottom: 8px;
}
.wi:hover { border-color: var(--line-hi); }
.wi.act { cursor: pointer; }
.wi.act:hover { border-color: var(--warp); }
.wi.child { margin-left: 26px; background: var(--bg); }
.caret {
  width: 18px; height: 18px; padding: 0; border: none; background: transparent;
  color: var(--warp-hi); cursor: pointer; font-size: 11px; line-height: 1;
}
.caret:hover { color: var(--ink); }
.caret-spacer { width: 18px; display: inline-block; }
.g { width: 20px; text-align: center; color: var(--faint-text); }
.ti { color: var(--ink); font-weight: 500; font-size: 14px; }
.mt {
  font-size: 11px; color: var(--faint-text); margin-left: auto;
  display: flex; gap: 12px; align-items: center; white-space: nowrap;
}
.subcount { color: var(--warp-hi); }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot.warn { background: var(--warp); }
.dot.fail { background: var(--failed); }
.dot.stale { background: var(--stale); }
.dot.healthy { background: var(--healthy); }
.dot.info { background: var(--info); }
.go { color: var(--warp-hi); }
.descbtn {
  font-family: var(--mono); font-size: 11px; color: var(--warp-hi);
  background: transparent; border: 1px solid var(--line); border-radius: 5px;
  padding: 2px 8px; cursor: pointer; white-space: nowrap;
}
.descbtn:hover { border-color: var(--warp); color: var(--ink); }
.desc {
  margin: -2px 0 10px 44px; padding: 10px 14px; border-left: 2px solid var(--warp);
  background: var(--surface); border-radius: 0 8px 8px 0; color: var(--dim);
  font-size: 13px; line-height: 1.55; white-space: pre-wrap;
}
.desc.child { margin-left: 70px; }
.empty { color: var(--faint-text); padding: 20px 0; }
.loadwrap { display: flex; justify-content: center; padding: 56px 0; }
</style>
