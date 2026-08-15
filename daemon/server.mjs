#!/usr/bin/env node
// ============================================================
// DSH 控制守护进程 (Android/Termux)
// 提供 http://127.0.0.1:8023 的 JSON API，供手机控制 App 调用：
//   GET  /api/ping    -> {ok:true}
//   GET  /api/status  -> {running,pid,port,version,lastLog[]}
//   POST /api/start   -> 启动 dsh web
//   POST /api/stop    -> 停止 dsh web
//   POST /api/restart -> 重启 dsh web
//   GET  /api/logs?lines=N -> 最近 N 行日志
// ============================================================
import http from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';

const PORT = 8023;
const DSH_PORT = 3080;
const HOME = '/data/data/com.termux/files/home';
const PREFIX = '/data/data/com.termux/files/usr';
const DSH_BIN = PREFIX + '/bin/dsh';
const LOG_FILE = HOME + '/dsh/storage/dsh.log';
const PKILL_MARK = 'deepseek-ai/dsh/lib/bin.js';
const BOOT_AT = Date.now();

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

function portOpen(port, timeout = 700) {
  return new Promise((resolve) => {
    const s = net.connect({ port, host: '127.0.0.1' });
    const done = (v) => { s.destroy(); resolve(v); };
    s.on('connect', () => done(true));
    s.on('error', () => done(false));
    s.setTimeout(timeout, () => done(false));
  });
}

function findDshPids() {
  const pids = [];
  let names = [];
  try { names = fs.readdirSync('/proc'); } catch { return pids; }
  for (const name of names) {
    if (!/^\d+$/.test(name)) continue;
    try {
      const cmd = fs.readFileSync(`/proc/${name}/cmdline`, 'utf8').replace(/\0/g, ' ');
      if (cmd.includes(PKILL_MARK)) pids.push(Number(name));
    } catch { /* ignore */ }
  }
  return pids;
}

async function dshStatus() {
  const running = await portOpen(DSH_PORT);
  const pids = findDshPids();
  let lastLog = [];
  try {
    lastLog = fs.readFileSync(LOG_FILE, 'utf8').split('\n').filter(Boolean).slice(-50);
  } catch { /* ignore */ }
  return { running, pid: pids[0] || null, pids, port: DSH_PORT, lastLog };
}

function startDsh() {
  return new Promise((resolve) => {
    try { fs.mkdirSync(HOME + '/dsh/storage', { recursive: true }); } catch { /* ignore */ }
    const out = fs.openSync(LOG_FILE, 'a');
    const child = spawn(DSH_BIN, ['web'], {
      detached: true,
      stdio: ['ignore', out, out],
      env: {
        PATH: PREFIX + '/bin:/system/bin:/system/xbin',
        HOME, PREFIX, LANG: 'en_US.UTF-8',
        DSH_PERMISSION_MODE: 'danger-full-access',
      },
    });
    child.on('error', (e) => { try { fs.closeSync(out); } catch {} resolve(false); });
    child.on('spawn', () => { try { fs.closeSync(out); } catch {} resolve(true); });
    child.unref();
  });
}

async function stopDsh() {
  // 先 SIGTERM，随后轮询端口释放；超时则逐轮升级到 SIGKILL（最多 5 轮），
  // 确保端口真正释放后再返回，避免重启时 EADDRINUSE。
  for (let round = 0; round < 5; round++) {
    for (const pid of findDshPids()) {
      try { process.kill(pid, round === 0 ? 'SIGTERM' : 'SIGKILL'); } catch { /* ignore */ }
    }
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      if (!(await portOpen(DSH_PORT))) return true;
      await new Promise((r) => setTimeout(r, 300));
    }
  }
  return !(await portOpen(DSH_PORT));
}

async function waitReady(timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await portOpen(DSH_PORT)) return true;
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

// dsh 版本懒加载：空值或超过 10 分钟时重新读取（部署后守护进程可自动感知版本）
let dshVersion = '';
let dshVersionAt = 0;
function getDshVersion() {
  if (!dshVersion || Date.now() - dshVersionAt > 600000) {
    try {
      dshVersion = String(execFileSync(DSH_BIN, ['--version'], { timeout: 15000 })).trim();
      dshVersionAt = Date.now();
    } catch { /* 保留旧值 */ }
  }
  return dshVersion;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const p = url.pathname;
  try {
    if (p === '/api/ping') return send(res, 200, { ok: true, name: 'dsh-control', version: '1.0.0', uptime: Math.round((Date.now() - BOOT_AT) / 1000) });
    if (p === '/api/status') {
      const st = await dshStatus();
      return send(res, 200, { ok: true, dshVersion: getDshVersion(), ...st });
    }
    if (p === '/api/start') {
      const already = await portOpen(DSH_PORT);
      let started = already;
      if (!already) { started = await startDsh(); if (started) started = await waitReady(30000); }
      const st = await dshStatus();
      return send(res, 200, { ok: true, action: 'start', started, dshVersion: getDshVersion(), ...st });
    }
    if (p === '/api/stop') {
      const stopped = await stopDsh();
      const st = await dshStatus();
      return send(res, 200, { ok: true, action: 'stop', stopped, dshVersion: getDshVersion(), ...st });
    }
    if (p === '/api/restart') {
      await stopDsh();
      let started = await startDsh();
      if (started) started = await waitReady(30000);
      const st = await dshStatus();
      return send(res, 200, { ok: true, action: 'restart', started, dshVersion: getDshVersion(), ...st });
    }
    if (p === '/api/logs') {
      const n = Math.min(Math.max(parseInt(url.searchParams.get('lines') || '80', 10) || 80, 1), 500);
      let text = '';
      try { text = fs.readFileSync(LOG_FILE, 'utf8').split('\n').slice(-n).join('\n'); } catch { /* ignore */ }
      return send(res, 200, { ok: true, lines: n, log: text });
    }
    if (p === '/api/env') {
      let node = '';
      try { node = String(execFileSync(PREFIX + '/bin/node', ['-v'], { timeout: 10000 })).trim(); } catch { /* ignore */ }
      let diskAvail = '';
      try {
        const out = String(execFileSync(PREFIX + '/bin/df', ['-h', HOME], { timeout: 10000 }));
        const line = out.split('\n')[1];
        if (line) {
          const parts = line.trim().split(/\s+/);
          if (parts.length >= 4) diskAvail = parts[3];
        }
      } catch { /* ignore */ }
      return send(res, 200, {
        ok: true, node, dshVersion: getDshVersion(), diskAvail,
        uptime: Math.round((Date.now() - BOOT_AT) / 1000),
      });
    }
    if (p === '/api/deploy') {
      const f = HOME + '/dsh/storage/deploy.log';
      const exists = fs.existsSync(f);
      let log = '';
      if (exists) {
        try { log = fs.readFileSync(f, 'utf8').split('\n').slice(-60).join('\n'); } catch { /* ignore */ }
      }
      return send(res, 200, { ok: true, exists, log });
    }

    // ============================================================ 存储管理
    // 路径白名单：仅允许访问 .dsh / dsh / deepseek-harness-android 三个区域
    const safePath = (p) => {
      if (!p || typeof p !== 'string') return null;
      const abs = p.startsWith('/') ? p : HOME + '/' + p;
      const ok = abs === HOME + '/.dsh' || abs.startsWith(HOME + '/.dsh/')
        || abs === HOME + '/dsh' || abs.startsWith(HOME + '/dsh/')
        || abs === HOME + '/deepseek-harness-android' || abs.startsWith(HOME + '/deepseek-harness-android/');
      return ok ? abs : null;
    };
    const dirSizeKb = (p) => {
      try {
        const out = String(execFileSync(PREFIX + '/bin/du', ['-sk', p], { timeout: 15000 }));
        const m = out.trim().split(/\s+/);
        return m.length ? Number(m[0]) : 0;
      } catch { return 0; }
    };

    if (p === '/api/storage') {
      // 标准存储配置表：无论目录是否存在，始终可查/可设
      const stdTypes = [
        ['sessions', '会话存储'],
        ['attachments', '附件存储'],
        ['skills', '技能目录'],
        ['workspace', '工作区'],
      ];
      const standard = stdTypes.map(([type, label]) => {
        const def = HOME + '/.dsh/' + type;
        const exists = fs.existsSync(def);
        let target = null;
        try { if (fs.lstatSync(def).isSymbolicLink()) target = fs.readlinkSync(def); } catch { /* ignore */ }
        let sizeKb = 0;
        if (exists) { try { sizeKb = dirSizeKb(def); } catch { /* ignore */ } }
        return { type, label, path: def, exists, symlink: !!target, target, sizeKb };
      });
      // MCP 配置（发现哪个算哪个）
      let mcp = null;
      for (const f of ['mcp.json', 'mcp.yaml', 'mcp.yml']) {
        const fp = HOME + '/.dsh/' + f;
        if (fs.existsSync(fp)) { mcp = fp; break; }
      }
      const items = [];
      const roots = [
        ['配置目录', HOME + '/.dsh'],
        ['部署与日志', HOME + '/dsh'],
        ['部署源码', HOME + '/deepseek-harness-android'],
      ];
      for (const [label, path] of roots) {
        const exists = fs.existsSync(path);
        items.push({ label, name: '', path, dir: true, exists, sizeKb: exists ? dirSizeKb(path) : 0 });
      }
      let sub = [];
      try { sub = fs.readdirSync(HOME + '/.dsh', { withFileTypes: true }); } catch { /* ignore */ }
      const stdNames = new Set(stdTypes.map((x) => x[0]));
      for (const e of sub) {
        if (stdNames.has(e.name)) continue; // 标准项已单独列出
        const fp = HOME + '/.dsh/' + e.name;
        let sizeKb = 0;
        try { sizeKb = e.isDirectory() ? dirSizeKb(fp) : Math.round(fs.statSync(fp).size / 1024); } catch { /* ignore */ }
        items.push({ label: null, name: e.name, path: fp, dir: e.isDirectory(), exists: true, sizeKb });
      }
      let sdcardWritable = false;
      try { fs.accessSync('/sdcard', fs.constants.W_OK); sdcardWritable = true; } catch { /* ignore */ }
      return send(res, 200, { ok: true, home: HOME, sdcardWritable, standard, mcp, items });
    }
    if (p === '/api/set-storage' && req.method === 'POST') {
      const type = String(url.searchParams.get('type') || '');
      const to = String(url.searchParams.get('to') || '');
      if (!['sessions', 'attachments', 'skills', 'workspace'].includes(type)) {
        return send(res, 400, { ok: false, error: '不支持该存储类型' });
      }
      const def = HOME + '/.dsh/' + type;
      const isLink = () => {
        try { return fs.lstatSync(def).isSymbolicLink(); } catch { return false; }
      };
      try {
        if (to === 'default') {
          // 还原默认位置：解除软链并把数据搬回
          if (isLink()) {
            const tgt = fs.readlinkSync(def);
            fs.unlinkSync(def);
            if (fs.existsSync(tgt) && !fs.existsSync(def)) {
              execFileSync(PREFIX + '/bin/cp', ['-a', tgt + '/.', def], { timeout: 180000 });
            }
          }
          return send(res, 200, { ok: true, restored: true, path: def });
        }
        if (!to.startsWith('/') || (!to.startsWith('/sdcard/') && !to.startsWith(HOME + '/'))) {
          return send(res, 400, { ok: false, error: '目标路径仅支持 /sdcard/ 或 Termux 家目录下' });
        }
        fs.mkdirSync(to, { recursive: true });
        if (fs.existsSync(def) && !isLink()) {
          execFileSync(PREFIX + '/bin/cp', ['-a', def + '/.', to + '/'], { timeout: 180000 });
          execFileSync(PREFIX + '/bin/rm', ['-rf', def], { timeout: 60000 });
        }
        if (!fs.existsSync(def)) fs.symlinkSync(to, def);
        return send(res, 200, { ok: true, path: def, target: to });
      } catch (e) {
        return send(res, 500, { ok: false, error: String(e && e.message || e) });
      }
    }
    if (p === '/api/browse') {
      const dir = safePath(url.searchParams.get('path') || '');
      if (!dir) return send(res, 400, { ok: false, error: '路径不在允许范围内' });
      let entries = [];
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true })
          .map((e) => {
            const fp = dir + '/' + e.name;
            let sizeKb = 0;
            try { sizeKb = e.isDirectory() ? dirSizeKb(fp) : Math.round(fs.statSync(fp).size / 1024); } catch { /* ignore */ }
            return { name: e.name, dir: e.isDirectory(), sizeKb };
          })
          .sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name) : (a.dir ? -1 : 1)));
      } catch (e) {
        return send(res, 404, { ok: false, error: String(e && e.message || e) });
      }
      return send(res, 200, { ok: true, path: dir, entries });
    }
    if (p === '/api/read') {
      const f = safePath(url.searchParams.get('path') || '');
      if (!f) return send(res, 400, { ok: false, error: '路径不在允许范围内' });
      try {
        const st = fs.statSync(f);
        if (st.size > 262144) return send(res, 400, { ok: false, error: '文件过大（>256KB），请用终端查看' });
        return send(res, 200, { ok: true, path: f, content: fs.readFileSync(f, 'utf8') });
      } catch (e) {
        return send(res, 404, { ok: false, error: String(e && e.message || e) });
      }
    }
    if (p === '/api/write' && req.method === 'POST') {
      const f = safePath(url.searchParams.get('path') || '');
      if (!f) return send(res, 400, { ok: false, error: '路径不在允许范围内' });
      let body = '';
      for await (const chunk of req) body += chunk;
      if (body.length > 262144) return send(res, 400, { ok: false, error: '内容过大' });
      try {
        fs.writeFileSync(f, body, 'utf8');
        return send(res, 200, { ok: true, path: f });
      } catch (e) {
        return send(res, 500, { ok: false, error: String(e && e.message || e) });
      }
    }
    if (p === '/api/move-sdcard' && req.method === 'POST') {
      const target = String(url.searchParams.get('dir') || '');
      if (!['sessions', 'attachments', 'skills', 'workspace'].includes(target)) {
        return send(res, 400, { ok: false, error: '不支持迁移该目录' });
      }
      try { fs.accessSync('/sdcard', fs.constants.W_OK); } catch {
        return send(res, 400, { ok: false, error: '共享存储不可写：请先在 Termux 执行 termux-setup-storage 并授权' });
      }
      const src = HOME + '/.dsh/' + target;
      const dst = '/sdcard/dsh-data/' + target;
      try {
        if (!fs.existsSync(dst)) {
          fs.mkdirSync('/sdcard/dsh-data', { recursive: true });
          if (fs.existsSync(src)) {
            execFileSync(PREFIX + '/bin/cp', ['-a', src, dst], { timeout: 120000 });
            execFileSync(PREFIX + '/bin/rm', ['-rf', src], { timeout: 60000 });
          } else {
            fs.mkdirSync(dst, { recursive: true });
          }
        }
        if (!fs.existsSync(src)) fs.symlinkSync(dst, src);
        return send(res, 200, { ok: true, movedTo: dst });
      } catch (e) {
        return send(res, 500, { ok: false, error: String(e && e.message || e) });
      }
    }
    if (p === '/api/set-storage-all' && req.method === 'POST') {
      // 统一数据根：to=default 全部还原；否则把 4 类数据全部迁到 <to>/<type> 并软链
      const to = String(url.searchParams.get('to') || '');
      const types = ['sessions', 'attachments', 'skills', 'workspace'];
      const results = [];
      for (const type of types) {
        const def = HOME + '/.dsh/' + type;
        const isLink = () => { try { return fs.lstatSync(def).isSymbolicLink(); } catch { return false; } };
        try {
          if (to === 'default') {
            if (isLink()) {
              const tgt = fs.readlinkSync(def);
              fs.unlinkSync(def);
              if (fs.existsSync(tgt) && !fs.existsSync(def)) {
                execFileSync(PREFIX + '/bin/cp', ['-a', tgt + '/.', def], { timeout: 180000 });
              }
            }
            results.push({ type, ok: true, restored: true });
          } else {
            if (!to.startsWith('/') || (!to.startsWith('/sdcard/') && !to.startsWith(HOME + '/'))) {
              results.push({ type, ok: false, error: '根目录仅支持 /sdcard/ 或 Termux 家目录下' });
              continue;
            }
            const dst = to + '/' + type;
            fs.mkdirSync(dst, { recursive: true });
            if (fs.existsSync(def) && !isLink()) {
              execFileSync(PREFIX + '/bin/cp', ['-a', def + '/.', dst + '/'], { timeout: 180000 });
              execFileSync(PREFIX + '/bin/rm', ['-rf', def], { timeout: 60000 });
            }
            if (!fs.existsSync(def)) fs.symlinkSync(dst, def);
            results.push({ type, ok: true, target: dst });
          }
        } catch (e) {
          results.push({ type, ok: false, error: String(e && e.message || e) });
        }
      }
      return send(res, 200, { ok: true, root: to, results });
    }
    send(res, 404, { ok: false, error: 'not found' });
  } catch (e) {
    send(res, 500, { ok: false, error: String(e && e.message || e) });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[dsh-control] listening on 127.0.0.1:${PORT}, dsh ${getDshVersion() || '(unknown)'}`);
});
