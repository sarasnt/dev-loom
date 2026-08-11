// One-off: rasterize the DevLoom loom mark (frontend/public/favicon.svg) to a 256x256 PNG for
// the desktop-notification app logo. Dependency-free (Node zlib). Run: node scripts/make-logo.mjs
import zlib from 'node:zlib'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const S = 256, CH = 4
const buf = Buffer.alloc(S * S * CH, 0) // transparent RGBA

function px(x, y, c) {
  x |= 0; y |= 0
  if (x < 0 || y < 0 || x >= S || y >= S) return
  const i = (y * S + x) * CH
  buf[i] = c[0]; buf[i + 1] = c[1]; buf[i + 2] = c[2]; buf[i + 3] = 255
}
function fillRoundRect(x0, y0, x1, y1, rad, col) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
    const cx = x < x0 + rad ? x0 + rad : x > x1 - 1 - rad ? x1 - 1 - rad : x
    const cy = y < y0 + rad ? y0 + rad : y > y1 - 1 - rad ? y1 - 1 - rad : y
    const dx = x - cx, dy = y - cy
    if (dx * dx + dy * dy <= rad * rad) px(x, y, col)
  }
}
// Thick, round-capped segment (works for the axis-aligned warp/weft threads).
function thick(x0, y0, x1, y1, r, col) {
  const minx = Math.min(x0, x1) - r, maxx = Math.max(x0, x1) + r
  const miny = Math.min(y0, y1) - r, maxy = Math.max(y0, y1) + r
  const vx = x1 - x0, vy = y1 - y0, len2 = vx * vx + vy * vy || 1
  for (let y = miny; y <= maxy; y++) for (let x = minx; x <= maxx; x++) {
    let t = ((x - x0) * vx + (y - y0) * vy) / len2
    t = t < 0 ? 0 : t > 1 ? 1 : t
    const px_ = x0 + vx * t, py_ = y0 + vy * t
    const dx = x - px_, dy = y - py_
    if (dx * dx + dy * dy <= r * r) px(x, y, col)
  }
}

const BG = [20, 22, 28], WARP = [198, 144, 47], WEFT = [224, 169, 74]
// rounded frame + inner fill (SVG rect 2,2,28,28 rx7 stroke2 → ×8)
fillRoundRect(8, 8, 248, 248, 60, WARP)
fillRoundRect(24, 24, 232, 232, 48, BG)
// warp: 3 vertical threads (x 10,16,22 · y 7..25) ×8
for (const cx of [80, 128, 176]) thick(cx, 56, cx, 200, 8, WARP)
// weft: 2 brighter horizontal threads (y 13,20 · x 7..25) ×8
for (const cy of [104, 160]) thick(56, cy, 200, cy, 8, WEFT)

// ---- PNG encode ----
function crc32(b) {
  let c = ~0
  for (let i = 0; i < b.length; i++) {
    c ^= b[i]
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xEDB88320 & -(c & 1))
  }
  return (~c) >>> 0
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0)
  const t = Buffer.from(type, 'ascii')
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0)
  return Buffer.concat([len, t, data, crc])
}
const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(S, 0); ihdr.writeUInt32BE(S, 4)
ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
const raw = Buffer.alloc(S * (S * CH + 1))
for (let y = 0; y < S; y++) {
  raw[y * (S * CH + 1)] = 0 // filter: none
  buf.copy(raw, y * (S * CH + 1) + 1, y * S * CH, (y + 1) * S * CH)
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
])
const outDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'assets')
fs.mkdirSync(outDir, { recursive: true })
const out = path.join(outDir, 'devloom-logo.png')
fs.writeFileSync(out, png)
console.log('wrote', out, png.length, 'bytes')
