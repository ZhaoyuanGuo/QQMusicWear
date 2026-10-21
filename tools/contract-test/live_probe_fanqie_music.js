#!/usr/bin/env node
/**
 * 番茄音乐源「真实接口」验证（一次性本地脚本，非 CI 用例）。
 *
 * 与 mock_smoke.js 不同：这里用真实网络请求驱动 source/fanqie_music_source.js，
 * 校验解析链路对线上返回结构的适配是否正确（接口是否漂移）。
 *
 * 实现说明：源脚本的 qmu.http 是「同步阻塞」契约，而 Node 主线程无法同步等待
 * 异步 IO（Atomics.wait 在 main thread 抛错，在 worker 里又会阻塞消息回调形成死锁）。
 * 因此这里用 child_process.spawnSync 调 curl 做真正的同步请求，与宿主桥接语义一致。
 *
 * 用法：node tools/contract-test/live_probe_fanqie_music.js [源脚本路径]
 */
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const scriptPath = process.argv[2] || 'source/fanqie_music_source.js';
const src = fs.readFileSync(scriptPath, 'utf8');

let reqSeq = 0;

/** 同步 HTTP（curl 实现；与宿主桥接同语义：阻塞返回 {status, location, setCookie, body}） */
function syncHttp(method, url, headers, body, contentType) {
  const id = ++reqSeq;
  const bodyFile = path.join(os.tmpdir(), 'fq_probe_body_' + id + '.json');
  const hdrFile = path.join(os.tmpdir(), 'fq_probe_hdr_' + id + '.txt');
  const args = ['-s', '-X', method, '-D', hdrFile, '-o', bodyFile, '--max-time', '25'];
  const hdrs = Object.assign({}, headers || {});
  if (contentType) hdrs['Content-Type'] = contentType;
  for (const k of Object.keys(hdrs)) args.push('-H', k + ': ' + hdrs[k]);
  if (body) { fs.writeFileSync(bodyFile + '.in', body, 'utf8'); args.push('--data-binary', '@' + bodyFile + '.in'); }
  args.push(url);

  const r = spawnSync('curl.exe', args, { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 });
  let status = 0, location = '', setCookie = [];
  try {
    const hdrText = fs.readFileSync(hdrFile, 'utf8');
    const lines = hdrText.split(/\r?\n/);
    for (const line of lines) {
      const m = /^HTTP\/[\d.]+\s+(\d+)/.exec(line);
      if (m) status = parseInt(m[1], 10);
      const lc = /^location:\s*(.+)$/i.exec(line);
      if (lc) location = lc[1].trim();
      const sc = /^set-cookie:\s*(.+)$/i.exec(line);
      if (sc) setCookie.push(sc[1].trim());
    }
  } catch (e) { /* 头文件缺失，按失败处理 */ }
  let bodyText = '';
  try { bodyText = fs.readFileSync(bodyFile, 'utf8'); } catch (e) { /* 空体 */ }
  for (const f of [bodyFile, bodyFile + '.in', hdrFile]) { try { fs.unlinkSync(f); } catch (e) {} }

  console.log('  [net] ' + method + ' ' + url.slice(0, 88) + ' -> ' + status + ' bodyLen=' + bodyText.length);
  return JSON.stringify({
    status, location, setCookie, body: bodyText,
    bodyB64: Buffer.from(bodyText, 'utf8').toString('base64'),
  });
}

let registered = null;
global.qmu = {
  http: (method, url, headersJson, body, contentType) =>
    syncHttp(method, url, JSON.parse(headersJson || '{}'), body, contentType),
  credential: () => JSON.stringify({ musicid: 0, musickey: '', isLogged: false }),
  md5: (s) => require('crypto').createHash('md5').update(String(s), 'utf8').digest('hex'),
  aesCbcHex: () => '', aesCbcDecryptB64: () => '', inflateB64: () => '', rsaNoPadHex: () => '',
  b64decode: (s) => Buffer.from(String(s), 'base64').toString('utf8'),
  sleep: () => {}, log: (s) => console.log('  [js] ' + s), guid: () => 'x'.repeat(32),
  emit: () => {}, register: (o) => { registered = o; },
};

new Function('qmu', src)(global.qmu);
if (!registered) { console.error('未注册'); process.exit(1); }
const H = registered.handlers;
console.log('源: ' + registered.manifest.id + ' v' + registered.manifest.version);

let failed = false;
const check = (name, cond, extra) => {
  if (cond) console.log('  ok  ' + name);
  else { console.error('  FAIL ' + name + (extra !== undefined ? ' -> ' + JSON.stringify(extra).slice(0, 300) : '')); failed = true; }
};

// ---- 1. 榜单（官网 SSR） ----
console.log('\n== toplists（真实官网 SSR）==');
let tl = [];
try { tl = H.toplists({}); } catch (e) { console.error('  异常: ' + e.message); }
check('榜单非空', tl.length > 0, tl.length);
if (tl.length) {
  check('榜单有标题', !!tl[0].title, tl[0]);
  console.log('  首条: ' + JSON.stringify(tl[0]));
  console.log('\n== toplistSongs（真实榜单曲目）==');
  const ts = H.toplistSongs({ topId: tl[0].topId });
  check('榜单歌曲非空', ts.length > 0, ts.length);
  if (ts.length) {
    console.log('  首曲: ' + JSON.stringify(ts[0]).slice(0, 400));
    check('mid 前缀 fq_', ts[0].mid.indexOf('fq_') === 0, ts[0].mid);
    check('bookId 承载于 albumMid', /^\d{15,25}$/.test(ts[0].albumMid), ts[0].albumMid);
  }
}

// ---- 2. 搜索（真实 API） ----
console.log('\n== searchAll（真实搜索 API）==');
let search = { songs: [] };
try { search = H.searchAll({ query: '告白气球' }); } catch (e) { console.error('  异常: ' + e.message); }
check('搜索结果非空', search.songs.length > 0, search.songs.length);
if (search.songs.length) {
  console.log('  首曲: ' + JSON.stringify(search.songs[0]).slice(0, 400));
  check('mid 前缀 fq_', search.songs[0].mid.indexOf('fq_') === 0, search.songs[0].mid);
}

// ---- 3. 取链 + 歌词 ----
// 注意：版权曲目（如周杰伦）在平台上 play_num=0、can_download=false，
// 取链与歌词都会被平台拒绝（m 站 lyric_info 直接返回 403 页）。因此优先挑
// 「非版权」曲目做验证——优先用榜单曲目（榜单为平台热播内容，通常非版权）。
function pickProbe(list) {
  for (const s of list) {
    if (s && s.mid && s.intervalSec > 0) return s;
  }
  return (list && list[0]) || null;
}
const probe = pickProbe(tl.length ? H.toplistSongs({ topId: tl[0].topId }) : [])
  || pickProbe(search.songs);
if (probe) {
  console.log('\n== resolveUrls（真实取链）==');
  const ru = H.resolveUrls({ quality: 'STANDARD', songs: [probe] });
  console.log('  debug: ' + ru.debug);
  check('解析结果 1 条', ru.items.length === 1, ru.items.length);
  check('拿到直链', !!(ru.items[0] && ru.items[0].url && ru.items[0].url.indexOf('http') === 0), ru.items[0]);
  if (ru.items[0]) console.log('  直链: ' + ru.items[0].url.slice(0, 140) + ' ext=' + ru.items[0].ext + ' prefix=' + ru.items[0].prefix);

  console.log('\n== lyric（真实歌词）==');
  const ly = H.lyric({ mid: probe.mid, mediaMid: probe.mediaMid, songId: probe.songId });
  console.log('  歌词前 120 字: ' + JSON.stringify(ly.slice(0, 120)));
  check('歌词非空且非 403 页', ly.length > 0 && ly.indexOf('403 Forbidden') < 0, ly.slice(0, 80));
  check('歌词为 LRC 行', ly.indexOf('[') === 0, ly.slice(0, 40));
}

// ---- 4. 首页 ----
console.log('\n== homeFeed ==');
const feed = H.homeFeed({});
check('首页有卡', feed.cards.length >= 2, feed.cards.length);

if (failed) { console.error('\nLIVE PROBE FAILED'); process.exit(1); }
console.log('\nLIVE PROBE OK');
