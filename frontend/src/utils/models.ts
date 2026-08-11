// Whether a model leaves the machine (paid/remote). Local Ollama's "gpt-oss" must NOT be
// treated as OpenAI's gpt-*. claude-code / claude-cli use the Anthropic subscription, so they
// leave the machine too.
export function isRemoteModel(m?: string): boolean {
  const s = (m || '').toLowerCase()
  if (s.startsWith('claude')) return true
  if (s.startsWith('gpt-oss')) return false
  return s.startsWith('gpt-') || s.startsWith('o1') || s.startsWith('o3') || s.startsWith('o4')
}
