<script setup lang="ts">
// Reports a checkout's upstream state and, when there is one obvious remedy, *is* the button for
// it. Every place that shows the state gets the action for free — including the worktree rows,
// where the branch and path sit right beside the chip and so name the target unambiguously.
//
// Diverged deliberately stays inert: pulling with commits on both sides needs a decision (merge,
// rebase, or look first) that a click cannot express.
import { computed } from 'vue'
import type { RepoView } from '../types'

defineOptions({ inheritAttrs: false })
const props = defineProps<{ repo: RepoView; disabled?: boolean }>()
const emit = defineEmits<{ pull: [] }>()

const state = computed(() => {
  const r = props.repo
  if (!r.hasUpstream) return { label: 'No upstream', tone: 'warn' }
  if (r.ahead && r.behind) return { label: `Diverged ↑${r.ahead} ↓${r.behind}`, tone: 'warn' }
  if (r.behind) return { label: `Needs pull ↓${r.behind}`, tone: 'warn' }
  if (r.ahead) return { label: `Needs push ↑${r.ahead}`, tone: 'info' }
  return { label: 'Up to date', tone: 'ok' }
})
const canPull = computed(() => !!props.repo.hasUpstream && !!props.repo.behind && !props.repo.ahead)
const pullTitle = computed(() =>
  `Pull ${props.repo.behind} commit${(props.repo.behind ?? 0) > 1 ? 's' : ''} from ${props.repo.upstream}`)
const restTitle = computed(() => (props.repo.upstream ? 'vs ' + props.repo.upstream : 'Upstream'))
</script>

<template>
  <button
    v-if="canPull"
    v-bind="$attrs"
    class="pullable"
    :class="state.tone"
    :disabled="disabled"
    :title="pullTitle"
    @click="emit('pull')"
  >{{ state.label }} ⤓</button>
  <span v-else v-bind="$attrs" :class="state.tone" :title="restTitle">{{ state.label }}</span>
</template>

<style scoped>
.pullable { cursor: pointer; background: transparent; font: inherit; color: inherit; }
.pullable:hover:not(:disabled) { border-color: var(--warp); color: var(--warp-hi); }
.pullable:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
