<script setup lang="ts">
// The brainstorm menu is absolutely positioned against its own trigger (.splitwrap is
// position: relative), so it cannot be hoisted to one copy per card — it has to render beside
// each button. That is the whole reason this is a component: the primary card and every worktree
// row underneath it each need their own.
import type { RepoView } from '../types'

defineProps<{
  repo: RepoView
  models: string[]
  open: boolean
  disabled: boolean
}>()
const emit = defineEmits<{ toggle: []; pick: [model: string] }>()

function label(m: string): string {
  return m === 'claude-cli' ? 'Claude CLI · interactive terminal' : m
}
</script>

<template>
  <div class="splitwrap">
    <button
      class="btn brainstorm"
      :disabled="disabled"
      title="Choose a model to brainstorm this repo"
      @click="emit('toggle')"
    >
      ✎ Brainstorm here ▾
    </button>
    <div v-if="open" class="bmenu" @click.self="emit('toggle')">
      <div class="bmlab mono">{{ repo.localOnly ? 'local models only' : 'choose a model' }}</div>
      <button v-for="m in models" :key="m" class="bmi" @click="emit('pick', m)">
        {{ label(m) }}
      </button>
      <div v-if="!models.length" class="bmi empty mono">no local models pulled</div>
    </div>
  </div>
</template>

<style scoped>
/* Copied verbatim from ReposView so the button stays pixel-identical after the extraction. */
.splitwrap { position: relative; display: inline-flex; }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; }
.btn:hover { border-color: var(--warp); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.brainstorm { border-color: var(--warp); color: var(--warp-hi); }
.bmenu { position: absolute; top: calc(100% + 4px); right: 0; z-index: 20; min-width: 240px; background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 6px; box-shadow: 0 8px 24px rgba(0,0,0,0.35); }
.bmi { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; width: 100%; text-align: left; background: transparent; border: 0; border-radius: 7px; padding: 8px 10px; color: var(--ink); font-size: 13px; cursor: pointer; }
.bmi:hover { background: var(--nav-hover); }
.bmi .mono { font-size: 11px; color: var(--faint-text); }
.bmi.empty { color: var(--faint-text); cursor: default; }
.bmi.empty:hover { background: transparent; }
.bmlab { font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint-text); padding: 4px 10px 6px; }
</style>
