// Minimal, dependency-free Markdown → HTML for model replies. A small whitelist of literal HTML
// (from e.g. Jira descriptions the model echoes) is normalised to Markdown/newlines FIRST; then
// everything is HTML-escaped, so model output can never inject tags; then we introduce only a
// known-safe set (headings, bold, italic, inline code, fenced code, lists, links, tables).

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// Convert a small whitelist of literal HTML tags to Markdown/newlines before escaping. Anything
// not in the whitelist is still escaped afterwards (so <script> etc. remain inert).
function normalizeHtml(s: string): string {
  return s
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/?(ul|ol)>/gi, '\n')
    .replace(/<li>\s*/gi, '\n- ')
    .replace(/<\/li>/gi, '')
    .replace(/<\/?(b|strong)>/gi, '**')
    .replace(/<\/?(i|em)>/gi, '*')
    .replace(/<\/?(code|tt)>/gi, '`')
}

function inline(t: string): string {
  return t
    .replace(/`([^`]+)`/g, '<code class="md-code">$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_]+)__/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
}

// A GFM table row → its cells (drops the outer empty cells from surrounding pipes).
function cells(row: string): string[] {
  const parts = row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|')
  return parts.map((c) => c.trim())
}
const isRow = (x: string) => x.includes('|') && x.trim() !== ''
const isSep = (x: string) => /^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$/.test(x) && x.includes('-') && x.includes('|')

export function renderMarkdown(src: string): string {
  if (!src) return ''
  let s = escapeHtml(normalizeHtml(src))

  // Pull fenced code blocks out first so their contents aren't further transformed.
  const blocks: string[] = []
  s = s.replace(/```[^\n]*\n?([\s\S]*?)```/g, (_m, code: string) => {
    const i = blocks.length
    blocks.push(`<pre class="md-pre"><code>${code.replace(/\n$/, '')}</code></pre>`)
    // NUL-delimited so the placeholder can't be confused with real text, and so trimming the
    // line can't strip its delimiters — the old ` CB0 ` form was matched against an
    // already-trimmed line, so its spaces were gone and every code block rendered as "CB0".
    return `\u0000CB${i}\u0000`
  })

  const out: string[] = []
  let list: 'ul' | 'ol' | null = null
  let para: string[] = []
  const flushPara = () => {
    if (para.length) { out.push(`<p class="md-p">${inline(para.join(' '))}</p>`); para = [] }
  }
  const closeList = () => { if (list) { out.push(`</${list}>`); list = null } }

  const lines = s.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const t = line.trim()

    // GFM table: a row followed by a |---|---| separator.
    if (isRow(t) && i + 1 < lines.length && isSep(lines[i + 1])) {
      flushPara(); closeList()
      const head = cells(t)
      i += 2
      const body: string[][] = []
      while (i < lines.length && isRow(lines[i]) && !isSep(lines[i])) { body.push(cells(lines[i])); i++ }
      i-- // the for-loop will advance past the last consumed row
      const th = head.map((c) => `<th>${inline(c)}</th>`).join('')
      const rows = body.map((r) => `<tr>${head.map((_, k) => `<td>${inline(r[k] ?? '')}</td>`).join('')}</tr>`).join('')
      out.push(`<div class="md-tablewrap"><table class="md-table"><thead><tr>${th}</tr></thead><tbody>${rows}</tbody></table></div>`)
      continue
    }

    const cb = t.match(/^\u0000CB(\d+)\u0000$/)
    if (cb) { flushPara(); closeList(); out.push(blocks[Number(cb[1])]); continue }

    const h = t.match(/^(#{1,6})\s+(.*)$/)
    if (h) { flushPara(); closeList(); const lvl = Math.min(6, h[1].length); out.push(`<div class="md-h md-h${lvl}">${inline(h[2])}</div>`); continue }

    // Thematic break. Models use `---` liberally between sections; unhandled it reads as stray text.
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(t)) { flushPara(); closeList(); out.push('<hr>'); continue }

    const ul = t.match(/^[-*]\s+(.*)$/)
    const ol = t.match(/^\d+\.\s+(.*)$/)
    if (ul) { flushPara(); if (list !== 'ul') { closeList(); out.push('<ul class="md-ul">'); list = 'ul' } out.push(`<li>${inline(ul[1])}</li>`); continue }
    if (ol) { flushPara(); if (list !== 'ol') { closeList(); out.push('<ol class="md-ol">'); list = 'ol' } out.push(`<li>${inline(ol[1])}</li>`); continue }

    if (t === '') { flushPara(); closeList(); continue }
    para.push(t)
  }
  flushPara()
  closeList()
  return out.join('\n')
}
