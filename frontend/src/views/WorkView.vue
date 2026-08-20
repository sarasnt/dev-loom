<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { WorkRow } from '../types'
import { fetchWork } from '../api'
import { useDashboardStore } from '../stores/dashboard'
import LoomLoader from '../components/LoomLoader.vue'

const router = useRouter()
const store = useDashboardStore()
const rows = ref<WorkRow[]>([])
const loading = ref(true)
const filters = ['All', 'New', 'PRs', 'Reviews', 'Tasks', 'Builds', 'Calendar', 'Notes', 'mine', 'stale']
const active = ref('All')
// updated (default) keeps the fetch order; priority sorts by PriorityEngine score, nulls last —
// same ranking Today's Warp uses, exposed here as an order instead of a separate page (Decision 3).
const sortMode = ref<'updated' | 'priority'>('updated')
// Status sub-filter: 'open' (open & ongoing, the default), 'all', or an exact status string.
const statusFilter = ref('open')
// Source sub-filter: 'all' or an exact source (GitHub, Jira, Notion, Calendar).
const sourceFilter = ref('all')
// PR role sub-filter — only meaningful (and only shown) on the PR/Review lenses.
const roleFilters = ['All', 'Mine', 'To Review', 'Others'] as const
const roleFilter = ref<(typeof roleFilters)[number]>('All')
function matchesRole(r: WorkRow): boolean {
  // A filter whose control is invisible must not act — the chip row only renders on PRs/Reviews,
  // so a stale non-'All' selection left over from those lenses must not silently narrow others.
  if (active.value !== 'PRs' && active.value !== 'Reviews') return true
  if (roleFilter.value === 'All') return true
  if (r.type !== 'pr' && r.type !== 'review') return true
  const role = r.prRole ?? 'other'
  return roleFilter.value === 'Mine' ? role === 'mine'
    : roleFilter.value === 'To Review' ? role === 'review'
    : role === 'other'
}

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
    case 'New': return !!r.isNew
    case 'PRs': return r.type === 'pr'
    case 'Reviews': return r.type === 'review'
    case 'Tasks': return r.type === 'task'
    case 'Builds': return r.type === 'build'
    // Type only. These used to fall back to a source-name match, which is the display name you
    // typed when configuring the source: "|| source === 'Notion'" pulled two Notion *tasks* into
    // Notes, and "|| source === 'Calendar'" matched nothing at all because the source is called
    // "CSW Calendar". Normalising every connector into one type is what the work model is for.
    case 'Calendar': return r.type === 'calendar'
    case 'Notes': return r.type === 'doc'
    case 'mine': return r.prRole === 'mine'
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
// Distinct statuses / sources in the current type view, for the dropdowns.
const statuses = computed(() =>
  [...new Set(typeFiltered.value.map((r) => r.status).filter(Boolean))].sort(),
)
const sources = computed(() =>
  [...new Set(typeFiltered.value.map((r) => r.source).filter(Boolean))].sort(),
)
function matchesStatus(r: WorkRow): boolean {
  if (statusFilter.value === 'all') return true
  if (statusFilter.value === 'open') return !isDone(r)
  return r.status === statusFilter.value
}
function matchesSource(r: WorkRow): boolean {
  return sourceFilter.value === 'all' || r.source === sourceFilter.value
}

// Priority: PriorityEngine's score, descending. Every synced row is scored today — WorkModelService
// ranks the whole set on each fetch — but the nulls-last branch stays as a guard for any future
// source that skips ranking, so a missing score sorts last instead of misreading as "highest priority".
function byPriority(a: WorkRow, b: WorkRow): number {
  const sa = a.score ?? null
  const sb = b.score ?? null
  if (sa === null && sb === null) return 0
  if (sa === null) return 1
  if (sb === null) return -1
  return sb - sa
}

const filtered = computed(() => {
  const base = typeFiltered.value.filter((r) => matchesStatus(r) && matchesSource(r) && matchesRole(r))
  return sortMode.value === 'priority' ? [...base].sort(byPriority) : base
})
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

// Of what's on screen, not of everything configured — it sits next to the item count, and
// "1 items · 4 sources" while showing a single GitHub build was just wrong.
const sourceCount = computed(() => new Set(filtered.value.map((r) => r.source)).size)

// Where a row opens. The connector resolved this when it synced, so every source has one — this
// used to rebuild a GitHub URL from the id, guarded by `source === 'GitHub'`, which never matched
// because the source is named "Sarasnt GitHub". Jira, Notion and calendar items had no link at all.
function externalUrl(r: WorkRow): string | null {
  return r.url && r.url.startsWith('http') ? r.url : null
}
// Role visible on the row too (spec: not just the PRs/Reviews chip filter) — a quiet suffix on
// the same "by <author>" line, not a separate chip, since it's a fact about that author's PR.
function roleSuffix(r: WorkRow): string {
  return r.prRole === 'mine' ? ' · yours' : r.prRole === 'review' ? ' · for review' : ''
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

// Row hand actions: same store actions the Today cards use (they hit /today/* keyed by extId
// and hand back the refreshed Today state, though Work doesn't consume it), then Work re-fetches
// its own rows — WorkRow now carries planned/handled (WorkModelService), so the re-fetch is what
// flips the button label to the honest opposite state, not a guess made client-side.
const busy = ref<Set<string>>(new Set())
async function runRowAction(id: string, fn: (id: string) => Promise<void>) {
  if (busy.value.has(id)) return
  busy.value = toggleIn(busy.value, id)
  try {
    await fn(id)
    rows.value = await fetchWork()
  } finally {
    busy.value = toggleIn(busy.value, id)
  }
}
function openExternal(r: WorkRow) {
  const url = externalUrl(r)
  if (url) window.open(url, '_blank', 'noopener')
}
function plan(r: WorkRow) {
  runRowAction(r.id, store.planItem)
}
function snooze(r: WorkRow) {
  runRowAction(r.id, store.snoozeItem)
}
function handle(r: WorkRow) {
  runRowAction(r.id, store.handleItem)
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

      <div class="sorttoggle mono" role="group" aria-label="Sort work">
        <button class="sortbtn" :class="{ on: sortMode === 'updated' }" @click="sortMode = 'updated'">updated</button>
        <button class="sortbtn" :class="{ on: sortMode === 'priority' }" @click="sortMode = 'priority'">priority</button>
      </div>

      <select v-model="sourceFilter" class="statussel mono" aria-label="Filter by source">
        <option value="all">All sources</option>
        <option v-for="s in sources" :key="s" :value="s">{{ s }}</option>
      </select>

      <select v-model="statusFilter" class="statussel mono" aria-label="Filter by status">
        <option value="open">Open &amp; ongoing</option>
        <option value="all">All statuses</option>
        <optgroup label="Status">
          <option v-for="s in statuses" :key="s" :value="s">{{ s }}</option>
        </optgroup>
      </select>
    </div>

    <div v-if="active === 'PRs' || active === 'Reviews'" class="rolefilters">
      <button v-for="rf in roleFilters" :key="rf" class="chipbtn mono"
              :class="{ on: roleFilter === rf }" @click="roleFilter = rf">{{ rf }}</button>
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
            <span class="srcpill">{{ r.source }}</span>
            <span v-if="hasChildren(r)" class="subcount">{{ childrenOf(r.id).length }} subtasks</span>
            <span class="dot" :class="r.statusTone" aria-hidden="true"></span> {{ r.status }}
            <span v-if="(r.type === 'pr' || r.type === 'review') && r.author">by {{ r.author }}{{ roleSuffix(r) }}</span>
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
          <span class="rowacts" @click.stop>
            <button v-if="externalUrl(r)" class="actbtn" @click="openExternal(r)">Open</button>
            <button class="actbtn" :class="{ on: r.planned }" :disabled="busy.has(r.id)" @click="plan(r)">{{ r.planned ? 'Unplan' : 'Plan' }}</button>
            <button class="actbtn" :disabled="busy.has(r.id)" @click="snooze(r)">Snooze</button>
            <button class="actbtn" :class="{ on: r.handled }" :disabled="busy.has(r.id)" @click="handle(r)">{{ r.handled ? 'Unhandle' : 'Handled' }}</button>
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
                <span class="srcpill">{{ c.source }}</span>
                <span class="dot" :class="c.statusTone" aria-hidden="true"></span> {{ c.status }}
                <span v-if="(c.type === 'pr' || c.type === 'review') && c.author">by {{ c.author }}{{ roleSuffix(c) }}</span>
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
              <span class="rowacts" @click.stop>
                <button v-if="externalUrl(c)" class="actbtn" @click="openExternal(c)">Open</button>
                <button class="actbtn" :class="{ on: c.planned }" :disabled="busy.has(c.id)" @click="plan(c)">{{ c.planned ? 'Unplan' : 'Plan' }}</button>
                <button class="actbtn" :disabled="busy.has(c.id)" @click="snooze(c)">Snooze</button>
                <button class="actbtn" :class="{ on: c.handled }" :disabled="busy.has(c.id)" @click="handle(c)">{{ c.handled ? 'Unhandle' : 'Handled' }}</button>
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
.rolefilters { display: flex; gap: 8px; align-items: center; margin: -8px 0 16px; }
.chipbtn {
  font-size: 12.5px; padding: 5px 11px; border: 1px solid var(--line);
  border-radius: 20px; color: var(--dim); background: transparent; cursor: pointer;
}
.chipbtn:hover { border-color: var(--warp); }
.chipbtn.on { background: var(--warp-weft); border-color: var(--warp); color: var(--ink); }
.statussel {
  font-size: 12px; color: var(--ink); background: var(--chip-bg);
  border: 1px solid var(--line); border-radius: 6px; padding: 5px 9px; cursor: pointer;
}
.statussel:hover { border-color: var(--warp); }
.statussel:focus { outline: none; border-color: var(--warp); }
/* Sort toggle sits right before the dropdowns; margin-left:auto here (not on the selects) pushes
   this whole trailing group — sort + source + status — to the right as one cluster. */
.sorttoggle {
  margin-left: auto; display: flex; border: 1px solid var(--line); border-radius: 6px; overflow: hidden;
}
.sortbtn {
  font-size: 11.5px; padding: 5px 10px; border: 0; background: var(--chip-bg); color: var(--dim); cursor: pointer;
}
.sortbtn + .sortbtn { border-left: 1px solid var(--line); }
.sortbtn:hover { color: var(--ink); }
.sortbtn.on { background: var(--warp-weft); color: var(--warp-hi); }
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
/* Row hand actions: right-aligned (it trails .mt, which already carries the auto margin), dense
   and mono like the rest of the row furniture — this is a browsing row, not a card. */
.rowacts { display: flex; gap: 6px; flex-shrink: 0; }
.actbtn {
  font-family: var(--mono); font-size: 10.5px; color: var(--dim);
  background: transparent; border: 1px solid var(--line); border-radius: 5px;
  padding: 3px 8px; cursor: pointer; white-space: nowrap;
}
.actbtn:hover:not(:disabled) { border-color: var(--warp); color: var(--ink); }
.actbtn:disabled { opacity: 0.5; cursor: default; }
.actbtn.on { color: var(--warp-hi); border-color: var(--warp); }
.subcount { color: var(--warp-hi); }
.srcpill {
  color: var(--dim); border: 1px solid var(--line); border-radius: 5px;
  padding: 1px 6px; background: var(--chip-bg);
}
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
