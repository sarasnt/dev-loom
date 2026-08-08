<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { OnboardStep } from '../types'
import { fetchOnboarding } from '../api/stub'

const steps = ref<OnboardStep[]>([])
const router = useRouter()
onMounted(async () => {
  steps.value = await fetchOnboarding()
})
</script>

<template>
  <div class="wrap">
    <header class="hero">
      <div class="brand"><span class="mark" aria-hidden="true"></span><b>Welcome to DevLoom</b></div>
      <p class="sub">Connect your work; keep it on your machine. About three minutes.</p>
    </header>

    <ol class="steps">
      <li v-for="s in steps" :key="s.title" class="stp" :class="s.state">
        <span class="num mono" aria-hidden="true">{{ s.n }}</span>
        <div class="body">
          <h3>{{ s.title }}</h3>
          <p>{{ s.detail }}</p>
        </div>
        <div class="side">
          <span v-if="s.state === 'done'" class="tag ok mono">done</span>
          <button v-else-if="s.action" class="btn pri" @click="router.push('/settings/integrations')">
            {{ s.action }} ▸
          </button>
          <span v-else class="mono muted">pending</span>
        </div>
      </li>
    </ol>

    <div class="skip"><RouterLink to="/today">Skip to dashboard →</RouterLink></div>
  </div>
</template>

<style scoped>
.wrap { max-width: 620px; margin: 0 auto; padding: 48px 24px; }
.hero { text-align: center; margin-bottom: 28px; }
.brand { display: inline-flex; align-items: center; gap: 10px; }
.brand b { font-size: 22px; letter-spacing: -0.02em; }
.mark { width: 24px; height: 24px; border-radius: 6px; border: 1px solid var(--warp); background: repeating-linear-gradient(90deg, var(--warp) 0 2px, transparent 2px 5px); }
.sub { color: var(--dim); margin-top: 10px; }
.steps { list-style: none; margin: 0; padding: 0; }
.stp { display: flex; gap: 14px; align-items: center; padding: 15px 0; border-bottom: 1px solid var(--line); }
.stp:last-child { border: 0; }
.num { width: 26px; height: 26px; border-radius: 50%; border: 1.5px solid var(--line); color: var(--faint-text); font-size: 12px; display: flex; align-items: center; justify-content: center; flex: none; }
.stp.done .num { border-color: var(--healthy); color: var(--healthy); }
.stp.now .num { border-color: var(--warp); color: var(--warp-hi); background: var(--warp-weft); }
.body { flex: 1; }
.body h3 { font-size: 15px; }
.body p { margin: 3px 0 0; color: var(--dim); font-size: 13px; }
.tag { font-size: 10px; border: 1px solid var(--healthy); color: var(--healthy); border-radius: 5px; padding: 2px 6px; }
.muted { color: var(--faint-text); font-size: 12px; }
.btn.pri { font-size: 13px; font-weight: 600; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--warp); background: var(--warp); color: var(--on-warp); }
.skip { text-align: center; margin-top: 28px; }
.skip a { color: var(--faint-text); font-size: 13px; text-decoration: none; }
.skip a:hover { color: var(--ink); }
</style>
