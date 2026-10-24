#!/usr/bin/env node
/**
 * 校验转码后的动画 WebP：尺寸、帧数、是否真的带 alpha。
 * 便携版移植踩过的坑就是「转出来是 rgb24，透明通道丢了」，这里直接卡住。
 *
 * 用法：node tools/verify-assets.mjs
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const DIR = path.join(ROOT, 'app', 'src', 'main', 'assets', 'pet');
const CFG = path.join(ROOT, 'app', 'src', 'main', 'assets', 'pet-config.json');

if (!fs.existsSync(DIR)) {
  console.error('没有素材目录，先跑 node tools/fetch-and-transcode.mjs');
  process.exit(1);
}

function probe(file) {
  const r = spawnSync('ffprobe', [
    '-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,pix_fmt,nb_frames',
    '-of', 'json', file,
  ], { encoding: 'utf8' });
  try {
    return JSON.parse(r.stdout).streams[0] || {};
  } catch {
    return {};
  }
}

const files = fs.readdirSync(DIR).filter((f) => f.endsWith('.webp'));
let bad = 0;
let total = 0;
const rows = [];

for (const f of files) {
  const full = path.join(DIR, f);
  const info = probe(full);
  const size = fs.statSync(full).size;
  total += size;
  const hasAlpha = String(info.pix_fmt || '').includes('a');
  if (!hasAlpha || !info.width) bad++;
  rows.push({
    name: f.replace(/\.webp$/, ''),
    w: info.width || 0,
    h: info.height || 0,
    frames: Number(info.nb_frames || 0),
    pix: info.pix_fmt || '?',
    kb: Math.round(size / 1024),
    ok: hasAlpha && info.width > 0,
  });
}

// 只要前 12 条明细，避免刷屏
console.table(rows.slice(0, 12));
console.log(`共 ${rows.length} 段，${(total / 1024 / 1024).toFixed(1)} MB，异常 ${bad} 段`);

if (fs.existsSync(CFG)) {
  const cfg = JSON.parse(fs.readFileSync(CFG, 'utf8'));
  const listed = new Set();
  for (const [state, spec] of Object.entries(cfg.states || {})) {
    for (const name of spec.files || []) listed.add(name);
  }
  const missing = [...listed].filter((n) => !fs.existsSync(path.join(DIR, n + '.webp')));
  const unlisted = rows.map((r) => r.name).filter((n) => !listed.has(n));
  if (missing.length) console.error('✗ 配置引用了不存在的文件：', missing.slice(0, 8));
  if (unlisted.length) console.warn('· 有文件没被任何状态引用（只会出现在「换个动作」里）：', unlisted.slice(0, 8));
  if (!missing.length) console.log('✓ pet-config.json 里的引用全部命中');
}

process.exit(bad > 0 ? 1 : 0);
