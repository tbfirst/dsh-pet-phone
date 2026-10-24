#!/usr/bin/env node
/**
 * 从上游 dsh-pet 还原动画，转成 Android 能播的动画 WebP。
 *
 * 为什么不把素材直接放进仓库：
 *   上游明确「素材允许开源使用、禁止商用」，仓库本体只放代码，素材在构建时现取，
 *   这样 fork 的人能拿到干净仓库，也不会把几十上百 MB 的二进制塞进 git 历史。
 *
 * 用法：
 *   node tools/fetch-and-transcode.mjs                # 默认 480px / 15fps / q50
 *   WIDTH=640 FPS=24 QUALITY=60 node tools/fetch-and-transcode.mjs
 *   SKIP_DOWNLOAD=1 node tools/fetch-and-transcode.mjs # 只重转 .work/assets 里已解压的素材
 *
 * 依赖：node 18+、ffmpeg、ffprobe（PATH 里要有）
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const WORK = path.join(ROOT, '.work');
const ASSET_OUT = path.join(ROOT, 'app', 'src', 'main', 'assets', 'pet');
const CONFIG_OUT = path.join(ROOT, 'app', 'src', 'main', 'assets', 'pet-config.json');

const WIDTH = Number(process.env.WIDTH || 480);
const FPS = Number(process.env.FPS || 15);
const QUALITY = Number(process.env.QUALITY || 50);

const log = (...a) => console.log('[assets]', ...a);
const fail = (msg) => {
  console.error('[assets] ✗ ' + msg);
  process.exit(1);
};

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', ...opts });
  if (r.error) throw r.error;
  return r;
}

function ensureTool(name) {
  const r = run(name, ['-version']);
  if (r.status !== 0) fail(`找不到 ${name}，请先安装（Windows 可用 winget install Gyan.FFmpeg）`);
}

// ── 1. 取素材 ────────────────────────────────────────────────────
function fetchUpstream() {
  const pkgDir = path.join(WORK, 'pkg');
  fs.mkdirSync(pkgDir, { recursive: true });
  log('从 npm 拉取 dsh-pet 包…');
  const r = run('npm', ['pack', 'dsh-pet', '--pack-destination', pkgDir], { stdio: 'inherit' });
  if (r.status !== 0) fail('npm pack 失败：检查网络或 npm 源');

  const tgz = fs.readdirSync(pkgDir).find((f) => f.endsWith('.tgz'));
  if (!tgz) fail('没拿到 tgz 包');

  const extractDir = path.join(WORK, 'assets');
  fs.rmSync(extractDir, { recursive: true, force: true });
  fs.mkdirSync(extractDir, { recursive: true });
  log('解压 ' + tgz);
  const tar = run('tar', ['-xzf', path.join(pkgDir, tgz), '-C', extractDir]);
  if (tar.status !== 0) fail('解压失败');

  const webmDir = findWebmDir(extractDir);
  if (!webmDir) fail('包里没找到 *.webm 素材目录');
  log('素材目录：' + path.relative(ROOT, webmDir));
  return webmDir;
}

function findWebmDir(dir) {
  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    let entries = [];
    try {
      entries = fs.readdirSync(cur, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of entries) {
      if (e.isDirectory()) stack.push(path.join(cur, e.name));
    }
    const webms = entries.filter((e) => e.isFile() && e.name.endsWith('.webm'));
    if (webms.length >= 3) return cur;
  }
  return null;
}

// ── 2. 转码 ──────────────────────────────────────────────────────
function durationMs(file) {
  const r = run('ffprobe', [
    '-v', 'error',
    '-show_entries', 'format=duration',
    '-of', 'default=nw=1:nk=1',
    file,
  ]);
  const v = parseFloat((r.stdout || '').trim());
  return Number.isFinite(v) ? Math.round(v * 1000) : 2000;
}

function transcode(src, dst) {
  // 关键：必须显式用 libvpx-vp9 解码，默认解码器会把 alpha 丢成 rgb24
  const args = [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-c:v', 'libvpx-vp9', '-i', src,
    '-vf', `fps=${FPS},scale=${WIDTH}:-2:flags=lanczos`,
    '-c:v', 'libwebp_anim', '-pix_fmt', 'yuva420p',
    '-q:v', String(QUALITY), '-loop', '0',
    dst,
  ];
  const r = run('ffmpeg', args);
  if (r.status !== 0) {
    console.warn('[assets] 转码失败，跳过：' + path.basename(src));
    return false;
  }
  return true;
}

// ── 3. 状态归类（启发式，够用；不满意直接手改 pet-config.json） ──
const RULES = [
  ['idle', /待机|呼吸|发呆|闲着/],
  ['drag', /拖拽|被拖|悬空|抓手/],
  ['fling', /落地|摔|趴下|着地/],
  ['walk', /移动|走路|走两|跑/],
  ['tap', /点击|回应|打滚|点头|摇|戳/],
];

function classify(name) {
  for (const [state, re] of RULES) {
    if (re.test(name)) return state;
  }
  return null;
}

function main() {
  ensureTool('ffmpeg');
  ensureTool('ffprobe');
  fs.mkdirSync(WORK, { recursive: true });
  fs.mkdirSync(ASSET_OUT, { recursive: true });

  const srcDir = process.env.SKIP_DOWNLOAD
    ? path.join(WORK, 'assets')
    : fetchUpstream();

  if (!fs.existsSync(srcDir)) fail('素材目录不存在，先不带 SKIP_DOWNLOAD 跑一次');

  const files = [];
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.webm')) files.push(p);
    }
  })(srcDir);

  log(`共 ${files.length} 段动画，转码到 ${WIDTH}px / ${FPS}fps / q${QUALITY}`);
  const states = { idle: [], tap: [], drag: [], fling: [], walk: [], sleep: [] };
  const durations = {};
  const categories = {};
  let ok = 0;

  for (const src of files) {
    const name = path.basename(src, '.webm');
    const dst = path.join(ASSET_OUT, `${name}.webp`);
    if (!transcode(src, dst)) continue;
    ok++;
    durations[name] = durationMs(dst);
    const st = classify(name);
    if (st && states[st]) states[st].push(name);
    else {
      const group = name.includes('-') ? name.split('-')[0] : '其他';
      (categories[group] ||= []).push(name);
    }
    if (ok % 10 === 0) log(`已完成 ${ok}/${files.length}`);
  }

  // 没有 idle 会很难看：兜底把第一段塞进去
  if (states.idle.length === 0) {
    const first = Object.keys(durations)[0];
    if (first) states.idle.push(first);
    console.warn('[assets] ⚠ 没识别到「待机」类动画，已把第一段当作待机');
  }

  const config = {
    schema: 1,
    pack: 'default',
    generatedAt: new Date().toISOString(),
    fps: FPS,
    canvas: { w: WIDTH, h: Math.round((WIDTH * 9) / 16) },
    baseWidthDp: 150,
    states: {
      idle: { files: states.idle, loop: true },
      tap: { files: states.tap, loop: false },
      drag: { files: states.drag, loop: true },
      fling: { files: states.fling, loop: false },
      walk: { files: states.walk, loop: true },
      sleep: { files: states.sleep, loop: true },
    },
    durations,
    categories,
  };
  fs.writeFileSync(CONFIG_OUT, JSON.stringify(config, null, 2), 'utf8');

  const size = fs
    .readdirSync(ASSET_OUT)
    .reduce((s, f) => s + fs.statSync(path.join(ASSET_OUT, f)).size, 0);

  log(`✓ 转码 ${ok}/${files.length} 段，合计 ${(size / 1024 / 1024).toFixed(1)} MB`);
  log(`  待机 ${states.idle.length} / 点击 ${states.tap.length} / 拖拽 ${states.drag.length} / 落地 ${states.fling.length} / 移动 ${states.walk.length}`);
  log('  配置已写入 app/src/main/assets/pet-config.json');
  log('');
  log('⚠ 素材版权：来自 PC2005-cloud/dsh-pet，允许开源使用、禁止商用。');
  log('  分发任何包含这些素材的 APK 时，请保留 NOTICE.md 里的署名。');
}

main();
