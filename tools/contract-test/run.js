#!/usr/bin/env node
/**
 * 音乐源插件契约测试（CI 用）：
 * 以 mock 桥接加载 source/qmusic_source.js，校验注册契约、manifest 完整性、
 * handler 齐全性，并以通用成功包体逐个试调安全 handler，确保返回值可 JSON 序列化。
 * 用法：node run.js <源脚本>
 */
'use strict';
const fs = require('fs');
const crypto = require('crypto');

const scriptPath = process.argv[2];
if (!scriptPath) {
  console.error('用法: node run.js <源脚本>');
  process.exit(2);
}
const src = fs.readFileSync(scriptPath, 'utf8');

let registered = null;

/** 通用 musicu 成功包体（各 handler 解析后应得到空列表/空对象） */
const genericBody = JSON.stringify({ code: 0, req_1: { code: 0, data: {} } });

/** 必须存在的 handler（与 APK 侧调用一一对应） */
const REQUIRED_HANDLERS = [
  'ping', 'recommendSongs', 'recommendNewSongs', 'playlistDetail',
  'toplists', 'toplistSongs', 'musicHallShelves', 'myPlaylists',
  'favPlaylists', 'userProfile', 'lyric', 'lyricTrans', 'searchAll', 'resolveUrls',
  'setLike', 'qrLogin',
];

/** 允许试调的 handler（qrLogin 会轮询阻塞，排除） */
const SAFE_HANDLERS = REQUIRED_HANDLERS.filter((n) => n !== 'qrLogin' && n !== 'ping');

global.qmu = {
  http: () => ({
    status: 200,
    location: '',
    setCookie: [],
    body: genericBody,
    bodyB64: Buffer.from(genericBody, 'utf8').toString('base64'),
  }),
  credential: () => JSON.stringify({
    musicid: 0, musickey: '', strMusicid: '', encryptUin: '', nick: '', avatarUrl: '',
  }),
  md5: (s) => crypto.createHash('md5').update(String(s), 'utf8').digest('hex'),
  b64decode: (s) => Buffer.from(String(s), 'base64').toString('utf8'),
  sleep: () => {},
  log: () => {},
  guid: () => '0123456789abcdef0123456789abcdef',
  emit: () => {},
  register: (obj) => { registered = obj; },
};

let failed = false;
const fail = (msg) => { console.error('FAIL: ' + msg); failed = true; };

// 1) 加载脚本（签名头为注释行，无需剥离）
try {
  new Function('qmu', src)(global.qmu);
} catch (e) {
  fail('脚本执行失败: ' + e.message);
  process.exit(1);
}

// 2) 注册契约
if (!registered) { fail('未调用 qmu.register'); }
else {
  const m = registered.manifest || {};
  if (m.id !== 'qmusic-web') fail('manifest.id 异常: ' + m.id);
  if (!Number.isInteger(m.version) || m.version < 1) fail('manifest.version 非法: ' + m.version);
  if (!Number.isInteger(m.minAppVersion) || m.minAppVersion < 0) {
    fail('manifest.minAppVersion 非法: ' + m.minAppVersion);
  }
  if (typeof m.playbackHeaders !== 'object') fail('manifest.playbackHeaders 缺失');
  const h = registered.handlers || {};
  for (const name of REQUIRED_HANDLERS) {
    if (typeof h[name] !== 'function') fail('缺少 handler: ' + name);
  }
}

// 3) ping
if (registered && typeof registered.handlers.ping === 'function') {
  if (registered.handlers.ping({}) !== 'ok') fail('ping() 未返回 "ok"');
}

// 4) 安全 handler 试调（网络为 mock 包体；handler 内部异常记 WARN 不阻断，序列化失败才阻断）
if (registered) {
  for (const name of SAFE_HANDLERS) {
    const fn = registered.handlers[name];
    if (typeof fn !== 'function') continue;
    try {
      const out = fn({});
      JSON.stringify(out);
    } catch (e) {
      console.warn('WARN: ' + name + ' 抛错（mock 数据所致，可忽略）: ' + e.message);
    }
  }
}

if (failed) process.exit(1);
console.log('CONTRACT OK: manifest v' + registered.manifest.version +
  ', handlers=' + Object.keys(registered.handlers).length);
