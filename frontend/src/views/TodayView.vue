<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useDashboardStore } from '../stores/dashboard'
import WarpList from '../components/WarpList.vue'
import type { Recommendation } from '../types'

const store = useDashboardStore()
const router = useRouter()
const { today, loading, error } = storeToRefs(store)

const snoozedOpen = ref(false)

// The connector writes calendar status once per sync and it doubles as the ongoing signal: an
// all-day event running today is exactly "today", a timed event still running is "now · until
// HH:mm" (see CalendarIcsConnector, TodayService.isTodayOrNow). It's always the first chip.
function statusOf(r: Recommendation): string {
  return (r.chips?.[0]?.label ?? '').toLowerCase()
}
// All-day: the connector says "today", or — belt and braces — startsAt lands on local midnight
// (how CalendarIcsConnector stores an all-day event's start).
function isAllDay(r: Recommendation): boolean {
  if (statusOf(r) === 'today') return true
  if (!r.startsAt) return false
  const d = new Date(r.startsAt)
  return d.getHours() === 0 && d.getMinutes() === 0
}
function isOngoing(r: Recommendation): boolean {
  return statusOf(r).startsWith('now')
}
function isPastToday(r: Recommendation): boolean {
  return statusOf(r).startsWith('ended')
}
function hhmm(startsAt?: string | null): string {
  if (!startsAt) return ''
  const d = new Date(startsAt)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// Backend already returns `schedule` sorted chronologically by startsAt (nulls last); splitting
// into chip row / timed rows here preserves that order in both halves.
const allDaySchedule = computed(() => (today.value?.schedule ?? []).filter(isAllDay))
const timedSchedule = computed(() => (today.value?.schedule ?? []).filter((r) => !isAllDay(r)))

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

      <!-- SCHEDULE STRIP: today's calendar entries, chronological. Compact timeline rows, not
           cards — a meeting is a fact about the day, not a task competing for attention. -->
      <template v-if="allDaySchedule.length || timedSchedule.length">
        <div class="sectlab"><span class="eyebrow">Schedule</span></div>
        <ul v-if="allDaySchedule.length" class="chiprow" aria-label="All-day">
          <li v-for="r in allDaySchedule" :key="r.id" class="daychip mono" :title="r.title">
            <a v-if="r.url" :href="r.url" target="_blank" rel="noopener noreferrer">{{ r.title }}</a>
            <template v-else>{{ r.title }}</template>
          </li>
        </ul>
        <ol v-if="timedSchedule.length" class="timeline">
          <li
            v-for="r in timedSchedule"
            :key="r.id"
            class="timerow"
            :class="{ ongoing: isOngoing(r), past: isPastToday(r) }"
          >
            <span class="time mono">{{ hhmm(r.startsAt) }}</span>
            <span class="dot" aria-hidden="true">·</span>
            <a v-if="r.url" class="ttitle" :href="r.url" target="_blank" rel="noopener noreferrer">{{ r.title }}</a>
            <span v-else class="ttitle">{{ r.title }}</span>
          </li>
        </ol>
      </template>

      <!-- NEEDS YOU NOW: the urgency set (failed builds, review requests). Only when non-empty —
           a header over nothing is noise. -->
      <template v-if="today.needsYou.length">
        <div class="sectlab"><span class="eyebrow">Needs you now</span></div>
        <WarpList :items="today.needsYou" />
      </template>

      <!-- PLANNED: the "+ Plan" set. Always renders — its empty state is the call to action that
           sends you to Work to build today's plan, so it can't just disappear when empty. -->
      <div class="sectlab"><span class="eyebrow">Planned</span></div>
      <WarpList v-if="today.planned.length" :items="today.planned" />
      <div v-else class="subhead mono quiet">nothing planned yet — pick items in Work</div>

      <!-- ASSIGNED TO YOU: PRs where you're author/reviewer, plus tasks and reviews assigned to
           you. Only when non-empty. -->
      <template v-if="today.assigned.length">
        <div class="sectlab"><span class="eyebrow">Assigned to you</span></div>
        <WarpList :items="today.assigned" />
      </template>

      <div class="more">
        <button class="morelink" @click="router.push('/work')">▸ Everything ({{ today.everythingCount }})</button>
        <button class="morelink" :disabled="!today.snoozedCount" :aria-expanded="snoozedOpen" @click="snoozedOpen = !snoozedOpen">
          {{ snoozedOpen ? '▾' : '▸' }} Snoozed ({{ today.snoozedCount }})
        </button>
      </div>
      <ul v-if="snoozedOpen && today.snoozed.length" class="donelist snoozelist">
        <li v-for="r in today.snoozed" :key="r.id" class="doneitem">
          <span class="donetitle">{{ r.title }}</span>
          <span class="donesrc mono">{{ r.source }}</span>
          <button class="unsnooze mono" @click="store.unsnoozeItem(r.id)">unsnooze</button>
        </li>
      </ul>
    </template>
  </main>
</template>

<style scoped>
.donelist { list-style: none; margin: 4px 0 18px; padding: 0 0 0 26px; display: flex; flex-direction: column; gap: 2px; }
.doneitem { display: flex; align-items: baseline; gap: 9px; font-size: 12.5px; padding: 3px 0; }
.donetitle { color: var(--dim); text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
a.donetitle:hover { color: var(--ink); text-decoration: underline; }
.donesrc { color: var(--faint-text); font-size: 10.5px; margin-left: auto; }
.main { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
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

.subhead { font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint-text); margin: 6px 0 8px; }
.subhead.quiet { color: var(--faint-text); text-transform: none; letter-spacing: 0; font-size: 12px; margin: 0 0 18px; }

/* Schedule strip: compact mono density, same spirit as .doneitem but its own row shape — a
   timeline, not a list of finished work. */
.chiprow { list-style: none; display: flex; flex-wrap: wrap; gap: 7px; margin: 0 0 12px; padding: 0; }
.daychip {
  font-size: 11px; padding: 4px 10px; border: 1px solid var(--line); border-radius: 999px;
  background: var(--raised); color: var(--dim); max-width: 220px; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.daychip a { color: inherit; text-decoration: none; }
.daychip a:hover { color: var(--ink); text-decoration: underline; }
.timeline { list-style: none; margin: 0 0 22px; padding: 0; display: flex; flex-direction: column; }
.timerow { display: flex; align-items: baseline; gap: 9px; font-size: 12.5px; padding: 4px 2px; border-radius: 5px; }
.timerow .time { color: var(--warp-hi); min-width: 42px; }
.timerow .dot { color: var(--faint-text); }
.timerow .ttitle { color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-decoration: none; }
a.ttitle:hover { color: var(--warp-hi); text-decoration: underline; }
.timerow.ongoing { background: var(--warp-weft); }
.timerow.ongoing .time { color: var(--warp-hi); font-weight: 600; }
.timerow.past { opacity: 0.5; }
.timerow.past .time { color: var(--faint-text); }

.more { display: flex; gap: 20px; margin-top: 16px; }
.morelink { background: none; border: 0; padding: 0; color: var(--faint-text); font-size: 13px; cursor: pointer; }
.morelink:hover:not(:disabled) { color: var(--ink); }
.morelink:disabled { cursor: default; opacity: 0.6; }
.snoozelist { margin-top: 10px; }
.unsnooze { background: none; border: 1px solid var(--line); border-radius: 5px; padding: 1px 8px; font-size: 10.5px; color: var(--dim); cursor: pointer; }
.unsnooze:hover { border-color: var(--warp); color: var(--ink); }

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
