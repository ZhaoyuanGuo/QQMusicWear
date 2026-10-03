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
const zlib = require('zlib');

const scriptPath = process.argv[2];
if (!scriptPath) {
  console.error('用法: node run.js <源脚本>');
  process.exit(2);
}
const src = fs.readFileSync(scriptPath, 'utf8');

let registered = null;

/**
 * 通用 musicu 成功包体：结构完整（含 code:0 和嵌套 req_1.data）
 * 使得各 handler 解析后得到空列表/空对象而不抛异常。
 */
const genericBody = JSON.stringify({
  code: 0,
  req_1: { code: 0, data: {
    tracks: [], track_info: [], songList: [], song_info: [],
    list: [], data: [], items: [], topList: [],
    vec_songid: [], lyric: '', trans: '', roma: '',
    dirinfo: {}, userinfo: {}, shelves: [],
  }},
});

/** 必须存在的 handler（与 APK 侧调用一一对应） */
const REQUIRED_HANDLERS = [
  'ping', 'homeFeed', 'recommendSongs', 'recommendNewSongs', 'playlistDetail',
  'toplists', 'toplistSongs', 'artistSongs', 'albumSongs',
  'musicHallShelves', 'myPlaylists',
  'favPlaylists', 'userProfile', 'lyric', 'lyricTrans', 'lyricRoma',
  'searchAll', 'resolveUrls',
  'setLike', 'qrLogin',
];

/** 允许试调的 handler（qrLogin 会轮询阻塞，排除） */
const SAFE_HANDLERS = REQUIRED_HANDLERS.filter((n) => n !== 'qrLogin' && n !== 'ping');

/**
 * 可选 handler（新增能力）：源"实现了"才试调，不实现不报错。
 * 这样 QQ 源无需回填这些能力即可通过 CI。
 */
const OPTIONAL_HANDLERS = [
  'artistAlbums', 'radioSongs', 'heartMode', 'djRadios', 'djPrograms',
  'songComments', 'cloudSongs', 'userEvents', 'userFollows',
];

/**
 * qmu.http 契约：宿主桥接返回 JSON 字符串，脚本内 http() 对其 JSON.parse。
 * mock 必须返回字符串以匹配实际运行时行为。
 */
const mockHttpResponse = JSON.stringify({
  status: 200,
  location: '',
  setCookie: [],
  body: genericBody,
  bodyB64: Buffer.from(genericBody, 'utf8').toString('base64'),
});

global.qmu = {
  http: () => mockHttpResponse,
  credential: () => JSON.stringify({
    musicid: 0, musickey: '', strMusicid: '', encryptUin: '', nick: '', avatarUrl: '',
  }),
  md5: (s) => crypto.createHash('md5').update(String(s), 'utf8').digest('hex'),
  // 宿主新增的 AES/RSA 桥接原语（与 APK 侧 SourceEngine 对应）
  aesCbcHex: () => '0'.repeat(64),
  // 真实 AES-CBC 解密：mock 端与源脚本协议可回环验证（key/iv 按 Latin1 取字节）
  aesCbcDecryptB64: (b64, key, iv) => {
    try {
      const k = Buffer.from(String(key), 'latin1');
      const d = crypto.createDecipheriv(`aes-${k.length * 8}-cbc`, k, Buffer.from(String(iv), 'latin1'));
      return Buffer.concat([d.update(Buffer.from(String(b64), 'base64')), d.final()]).toString('utf8');
    } catch (e) { return ''; }
  },
  inflateB64: (b64) => {
    try { return zlib.inflateSync(Buffer.from(String(b64), 'base64')).toString('utf8'); }
    catch (e) { return ''; }
  },
  rsaNoPadHex: () => '0'.repeat(256),
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
  // 多源：id 不限定具体值，但必须是非空字符串（宿主按 id 隔离缓存与凭据）
  if (typeof m.id !== 'string' || !m.id) fail('manifest.id 缺失或非法: ' + m.id);
  if (typeof m.name !== 'string' || !m.name) fail('manifest.name 缺失（品牌展示名）');
  if (typeof m.themeColor !== 'string' || !m.themeColor) fail('manifest.themeColor 缺失（品牌色）');
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

// 4) 安全 handler 试调（mock 数据结构完整；handler 抛异常或返回 undefined 视为逻辑缺陷，阻断门禁）
if (registered) {
  for (const name of SAFE_HANDLERS) {
    const fn = registered.handlers[name];
    if (typeof fn !== 'function') continue;
    try {
      const out = fn({});
      if (out === undefined) {
        fail(name + '() 返回 undefined，handler 必须有明确返回值');
        continue;
      }
      JSON.stringify(out);
    } catch (e) {
      fail(name + '() 抛出异常: ' + e.message);
    }
  }
}

// 5) 可选 handler 试调（源实现才试调；未实现不算失败）
if (registered) {
  for (const name of OPTIONAL_HANDLERS) {
    const fn = registered.handlers[name];
    if (typeof fn !== 'function') continue;
    try {
      const out = fn({});
      if (out === undefined) {
        fail(name + '() 返回 undefined，handler 必须有明确返回值');
        continue;
      }
      JSON.stringify(out);
    } catch (e) {
      fail(name + '() 抛出异常: ' + e.message);
    }
  }
}

if (failed) process.exit(1);
console.log('CONTRACT OK: manifest v' + registered.manifest.version +
  ', handlers=' + Object.keys(registered.handlers).length);
