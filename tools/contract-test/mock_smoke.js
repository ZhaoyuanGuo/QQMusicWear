#!/usr/bin/env node
/**
 * 音乐源本地模拟冒烟测试（无需真机/真账号）。
 * 用各源「真实响应样例」驱动 handler，断言解析出的 DTO 关键字段，
 * 覆盖：homeFeed / searchAll / playlistDetail / toplists / resolveUrls / qrLogin。
 *
 * 用法：node mock_smoke.js <源脚本>
 */
'use strict';
const fs = require('fs');

const scriptPath = process.argv[2];
if (!scriptPath) {
  console.error('用法: node mock_smoke.js <源脚本>');
  process.exit(2);
}
const src = fs.readFileSync(scriptPath, 'utf8');

// ---------------------------------------------------------------------------
// 各源的真实响应样例（按 URL 片段分派）
// ---------------------------------------------------------------------------

const NE_TRACK = {
  id: 1, name: 'A', ar: [{ name: 'Singer' }],
  al: { id: 2, name: 'Alb', picUrl: 'https://p1.music.126.net/x.jpg' }, dt: 180000,
};
const NE_PL = {
  id: 5, name: 'PL', coverImgUrl: 'https://p1.music.126.net/p.jpg', trackCount: 1,
  tracks: [NE_TRACK], trackIds: [{ id: 1 }], creator: { nickname: 'creator' },
};

const NET_EASE = [
  ['/api/recommend/songs', { code: 200, data: { dailySongs: [NE_TRACK] } }],
  ['/api/personalized/newsong', { code: 200, result: [{ id: 1, name: 'A', picUrl: 'https://p1.music.126.net/x.jpg', song: NE_TRACK }] }],
  ['/api/personalized/playlist', { code: 200, result: [{ id: 9, name: 'RPL', coverImgUrl: 'https://p1.music.126.net/r.jpg', trackCount: 3, creator: { nickname: 'x' } }] }],
  ['/api/v6/playlist/detail', { code: 200, playlist: NE_PL }],
  ['/api/v3/song/detail', { code: 200, songs: [NE_TRACK] }],
  ['/api/playlist/list', { code: 200, playlists: [{ id: 9, name: 'RPL', coverImgUrl: 'u', trackCount: 3, creator: { nickname: 'x' } }] }],
  ['/api/toplist', { code: 200, list: [{ id: 19723756, name: '飙升榜', coverImgUrl: 'https://p1.music.126.net/t.jpg', updateFrequency: '每天更新' }] }],
  ['/api/v1/artist/songs', { code: 200, songs: [NE_TRACK] }],
  ['/api/v1/album/', { code: 200, album: { name: 'Alb', picUrl: 'https://p1.music.126.net/x.jpg' }, songs: [NE_TRACK] }],
  ['/api/nuser/account/get', { code: 200, profile: { userId: 123, nickname: 'N', avatarUrl: 'https://p1.music.126.net/a.jpg' } }],
  ['/api/song/enhance/player/url/v1', { code: 200, data: [{ id: 1, url: 'http://m8.music.126.net/x.mp3', br: 320000, type: 'mp3', level: 'exhigh' }] }],
  ['/api/song/lyric', { code: 200, lrc: { lyric: '[00:00]hi' }, tlyric: { lyric: '[00:00]trans' }, romalrc: { lyric: '[00:00]roma' } }],
  ['/api/search/get/web', { code: 200, result: { songs: [NE_TRACK], artists: [{ id: 7, name: 'KS' }], playlists: [{ id: 9, name: 'RPL', coverImgUrl: 'u', trackCount: 3, creator: { nickname: 'x' } }] } }],
  ['/api/login/qrcode/unikey', { code: 200, unikey: 'UNIKEY123' }],
  ['/api/login/qrcode/client/login', { code: 803, message: 'ok', cookie: 'MUSIC_U=SESSION999; __csrf=abc' }],
];

const KG_INFO = {
  hash: 'ABCDEF0123456789', audio_id: 11, songname: 'K', singername: 'KS',
  album_name: 'KA', album_id: 22, duration: 200,
  imgurl: 'http://imge.kugou.com/stdmusic/{size}/20200101/x.jpg',
};
const KUGOU = [
  ['/api/v3/rank/song', { status: 1, data: { info: [KG_INFO] } }],
  ['/api/v3/rank/list', { status: 1, data: { info: [{ rankid: 8888, rankname: 'TOP500', imgurl: 'http://imge.kugou.com/stdmusic/{size}/r.jpg' }] } }],
  ['/api/v3/search/song', { status: 1, data: { info: [KG_INFO] } }],
  ['/api/v3/search/singer', { status: 1, data: { info: [{ singerid: 7, singername: 'KS' }] } }],
  ['/api/v3/search/special', { status: 1, data: { info: [{ specialid: 9, specialname: 'KPL', imgurl: 'http://imge.kugou.com/stdmusic/{size}/s.jpg', songcount: 3, nickname: 'n' }] } }],
  ['/api/v3/special/song', { status: 1, data: { info: [KG_INFO] } }],
  ['/api/v3/special/info', { status: 1, data: { specialname: 'KPL', imgurl: 'http://imge.kugou.com/stdmusic/{size}/s.jpg', songcount: 3 } }],
  ['/api/v3/special/list', { status: 1, data: { info: [{ specialid: 9, specialname: 'KPL', imgurl: 'http://imge.kugou.com/stdmusic/{size}/s.jpg', songcount: 3 }] } }],
  ['/api/v3/album/song', { status: 1, data: { info: [KG_INFO] } }],
  ['/api/v3/album/info', { status: 1, data: { albumname: 'KA', imgurl: 'http://imge.kugou.com/stdmusic/{size}/a.jpg' } }],
  ['/api/v3/singer/song', { status: 1, data: { info: [KG_INFO] } }],
  ['trackercdn.kugou.com/i/v2/', { status: 1, url: ['http://tracker.kugou.com/x.mp3'], extName: 'mp3' }],
  ['krcs.kugou.com/search', { candidates: [{ id: '1', accesskey: 'ak' }] }],
  ['lyrics.kugou.com/download', { content: Buffer.from('[00:00]hi', 'utf8').toString('base64') }],
  ['login-user.kugou.com/v2/qrcode', { status: 1, data: { qrcode: 'QRKEY1' } }],
  ['login-user.kugou.com/v2/get_userinfo_qrcode', { status: 1, data: { status: 4, token: 'TOKEN1', userid: 55 } }],
  ['loginservice.kugou.com/v1/login_by_token_get', { status: 0, data: { token: 'TOKEN1', userid: 55 } }],
];

const TABLE = src.indexOf("id: 'netease-web'") >= 0 ? NET_EASE
  : src.indexOf("id: 'kugou-web'") >= 0 ? KUGOU
    : null;
if (!TABLE) {
  // 未内置模拟样例的源（如 QQ音乐）：跳过而非失败，便于 CI 统一遍历
  console.log('SKIP mock smoke（无模拟样例）: ' + scriptPath);
  process.exit(0);
}

/** 记录每次请求 URL，供断言（如登录票轮询） */
let lastUrls = [];
let registered = null;

global.qmu = {
  http: (method, url) => {
    lastUrls.push(url);
    let payload = { code: 404 };
    for (const [frag, body] of TABLE) {
      if (url.indexOf(frag) >= 0) { payload = body; break; }
    }
    const bodyText = JSON.stringify(payload);
    return JSON.stringify({
      status: 200, location: '', setCookie: [], body: bodyText,
      bodyB64: Buffer.from(bodyText, 'utf8').toString('base64'),
    });
  },
  credential: () => JSON.stringify({ musicid: 0, musickey: '', strMusicid: '', encryptUin: '', nick: '', avatarUrl: '', isLogged: false }),
  md5: (s) => require('crypto').createHash('md5').update(String(s), 'utf8').digest('hex'),
  b64decode: (s) => Buffer.from(String(s), 'base64').toString('utf8'),
  sleep: () => {},
  log: () => {},
  guid: () => '0123456789abcdef0123456789abcdef',
  emit: (e) => { events.push(JSON.parse(e)); },
  register: (o) => { registered = o; },
};
const events = [];

new Function('qmu', src)(global.qmu);
if (!registered) { console.error('FAIL: 未注册'); process.exit(1); }
const H = registered.handlers;
const isNe = registered.manifest.id === 'netease-web';

let failed = false;
function check(name, cond, extra) {
  if (cond) { console.log('  ok  ' + name); }
  else { console.error('  FAIL ' + name + (extra !== undefined ? ' -> ' + JSON.stringify(extra) : '')); failed = true; }
}

// ---- homeFeed ----
console.log('== homeFeed ==');
const feed = H.homeFeed({});
check('cards 非空', Array.isArray(feed.cards) && feed.cards.length >= 3, feed.cards && feed.cards.length);
const daily = feed.cards.find((c) => c.action === 'daily');
check('daily 卡含歌曲', daily && daily.songs && daily.songs.length > 0);
check('mid 前缀正确', daily && daily.songs[0].mid.indexOf(isNe ? 'ne_' : 'kg_') === 0, daily && daily.songs[0].mid);
check('封面已裁尺寸', daily && /300|\/240\//.test(daily.songs[0].cover300), daily && daily.songs[0].cover300);

// ---- searchAll ----
console.log('== searchAll ==');
const search = H.searchAll({ query: 'test' });
check('歌曲非空', search.songs.length > 0, search.songs.length);
check('歌手非空', search.singers.length > 0, search.singers.length);
check('歌单非空', search.playlists.length > 0, search.playlists.length);

// ---- playlistDetail ----
console.log('== playlistDetail ==');
const pd = H.playlistDetail({ disstid: 5, page: 1, num: 100 });
check('歌单名', !!pd.playlist && pd.playlist.name.length > 0, pd.playlist);
check('歌单含歌曲', pd.songs.length > 0, pd.songs.length);

// ---- toplists ----
console.log('== toplists ==');
const tl = H.toplists({});
check('榜单非空', tl.length > 0 && tl[0].topId > 0, tl);

// ---- resolveUrls ----
console.log('== resolveUrls ==');
const ru = H.resolveUrls({
  quality: 'HIGH',
  songs: [{ mid: isNe ? 'ne_1' : 'kg_ABCDEF0123456789', songId: isNe ? 1 : 11, mediaMid: '', songType: 0 }],
});
check('解析结果 1 条', ru.items.length === 1, ru.items.length);
check('直链存在', ru.items[0] && ru.items[0].url.indexOf('http') === 0, ru.items[0]);
check('未加密', ru.items[0] && ru.items[0].encrypted === false);
check('音质前缀存在', ru.items[0] && !!ru.items[0].prefix, ru.items[0] && ru.items[0].prefix);

// ---- lyric ----
console.log('== lyric ==');
const ly = H.lyric({ mid: isNe ? 'ne_1' : 'kg_ABCDEF0123456789', songId: isNe ? 1 : 11 });
check('歌词非空', typeof ly === 'string' && ly.length > 0, ly);

// ---- qrLogin（mock 立即成功） ----
console.log('== qrLogin ==');
const cred = H.qrLogin({});
check('返回凭据', !!cred && !!cred.musickey, cred);
check('发出 Success 事件', events.some((e) => e.type === 'Success'));
const qrEvt = events.find((e) => e.type === 'QrReady');
check('QrReady 含 text 或 b64', !!qrEvt && (!!qrEvt.text || !!qrEvt.b64), qrEvt);

if (failed) { console.error('\nMOCK SMOKE FAILED'); process.exit(1); }
console.log('\nMOCK SMOKE OK: ' + registered.manifest.id + ' v' + registered.manifest.version);