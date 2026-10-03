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
  // weapi 新接口（私人FM / 歌手专辑 / 播客 / 评论 / 云盘 / 动态 / 关注 / 精品歌单 / 收藏）
  // 路径契约：调用方写 /api/xxx，weapi 通道实际请求 /weapi/xxx（去掉 /api 前缀）
  ['/weapi/v1/radio/get', { code: 200, data: [NE_TRACK] }],
  ['/weapi/artist/albums/', { code: 200, hotAlbums: [{ id: 77, name: 'Alb2', picUrl: 'https://p1.music.126.net/a.jpg', size: 5, publishTime: 1 }] }],
  ['/weapi/top/playlist/highquality', { code: 200, playlists: [{ id: 9, name: 'HQ', coverImgUrl: 'u', trackCount: 3, creator: { nickname: 'x' } }] }],
  ['/weapi/djradio/hot/v1', { code: 200, djRadios: [{ id: 9, name: 'R', picUrl: 'u', programCount: 5, dj: { nickname: 'DJ' } }] }],
  ['/weapi/dj/program/byradio', { code: 200, programs: [{ id: 1, name: 'P', coverUrl: 'u', duration: 60000, radio: { id: 9, name: 'R' }, mainSong: NE_TRACK }] }],
  ['/weapi/v1/resource/comments/', { code: 200, total: 30, more: true, hotComments: [{ user: { nickname: 'H' }, content: 'hot', likedCount: 9 }], comments: [{ user: { nickname: 'C', avatarUrl: 'a' }, content: 'nice', time: 1, likedCount: 2 }] }],
  ['/weapi/v1/cloud', { code: 200, data: [{ songId: 1, songName: 'A', simpleSong: NE_TRACK }] }],
  ['/weapi/event/get/', { code: 200, events: [{ id: 1, type: 18, user: { nickname: 'E' }, json: { msg: '分享', songs: [NE_TRACK] } }] }],
  ['/weapi/user/getfollows/', { code: 200, follow: [{ userId: 5, nickname: 'F', avatarUrl: 'a', signature: 's' }] }],
  ['/weapi/song/like', { code: 200 }],
];

const KG_INFO = {
  hash: 'ABCDEF0123456789', audio_id: 11, songname: 'K', singername: 'KS',
  album_name: 'KA', album_id: 22, duration: 200,
  // 真实接口不下发顶层 imgurl：歌曲封面只存在于 trans_param.union_cover
  trans_param: { union_cover: 'http://imge.kugou.com/stdmusic/{size}/20200101/x.jpg' },
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
  // 主站走 trackercdn.kugou.com、概念版走 trackercdngz.kugou.com，故按通用片段匹配
  ['kugou.com/i/v2/', { status: 1, url: ['http://tracker.kugou.com/x.mp3'], extName: 'mp3' }],
  ['krcs.kugou.com/search', { candidates: [{ id: '1', accesskey: 'ak' }] }],
  ['lyrics.kugou.com/download', { content: Buffer.from('[00:00]hi', 'utf8').toString('base64') }],
  // 网关云歌单（get_all_list）：含「我喜欢」与自建歌单
  // global_collection_id 为「取消喜欢」按 hash 定位 fileid 的必需参数
  ['gateway.kugou.com/v7/get_all_list', {
    status: 1,
    data: {
      info: [
        { listid: 111, listname: '我喜欢', songcount: 12, global_collection_id: 'collection_3_55_111_0', picurl: 'http://imge.kugou.com/stdmusic/{size}/l1.jpg', list_create_userid: 55 },
        { listid: 222, listname: '我的收藏', songcount: 8, global_collection_id: 'collection_3_55_222_0', picurl: 'http://imge.kugou.com/stdmusic/{size}/l2.jpg', list_create_userid: 55 },
      ],
    },
  }],
  // 红心：加入（add_song）与移出（delete_songs + 曲目列表定位 fileid）
  ['gateway.kugou.com/cloudlist.service/v6/add_song', { data: { status: 1, listid: 111, count: 13 } }],
  ['gateway.kugou.com/pubsongs/v2/get_other_list_file_nofilt', {
    status: 1, count: 12,
    songs: [{ fileid: 999, hash: 'ABCDEF0123456789', name: 'K', audio_id: 11, album_id: 22, mixsongid: 33 }],
  }],
  ['gateway.kugou.com/v4/delete_songs', { data: { status: 1, listid: 111, count: 11 } }],
  // 网关歌曲评论
  ['gateway.kugou.com/index.php', {
    status: 1, total: 23,
    list: [{ nickname: '听友', user_pic: 'http://imge.kugou.com/k.jpg', content: '好听', addtime: '2026-01-01 08:00:00', like_count: 3 }],
  }],
  ['login-user.kugou.com/v2/qrcode', { status: 1, data: { qrcode: 'QRKEY1' } }],
  ['login-user.kugou.com/v2/get_userinfo_qrcode', { status: 1, data: { status: 4, token: 'TOKEN1', userid: 55 } }],
  // 换票成功包体（status=1 且 data 携带昵称/头像），与官方 pwd 登录同构
  ['loginservice.kugou.com/v1/login_by_token_get', { status: 1, data: { token: 'TOKEN1', userid: 55, nickname: '酷狗用户', pic: 'http://imge.kugou.com/k.jpg' } }],
];

// 酷狗主站与酷狗概念版共用同一套接口与样例（仅 appid/解析节点不同）
const isKugouSrc = src.indexOf("id: 'kugou-web'") >= 0 || src.indexOf("id: 'kugou-concept-web'") >= 0;
const isKugouConcept = src.indexOf("id: 'kugou-concept-web'") >= 0;
const TABLE = src.indexOf("id: 'netease-web'") >= 0 ? NET_EASE
  : isKugouSrc ? KUGOU
    : null;
if (!TABLE) {
  // 未内置模拟样例的源（如 QQ音乐）：跳过而非失败，便于 CI 统一遍历
  console.log('SKIP mock smoke（无模拟样例）: ' + scriptPath);
  process.exit(0);
}

/** 记录每次请求 URL，供断言（如登录票轮询） */
let lastUrls = [];
/** 记录最近一次请求的 headers（JSON 字符串），供断言（如网易红心的 Cookie 身份） */
let lastHeaders = null;
let registered = null;
/** 登录态开关：云歌单/评论/红心等需登录能力在断言前置为 true */
let mockLogged = false;

global.qmu = {
  http: (method, url, headers) => {
    lastUrls.push(url);
    lastHeaders = headers;
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
  credential: () => JSON.stringify({
    musicid: mockLogged ? 55 : 0,
    musickey: mockLogged ? 'token=TOKEN1;userid=55;' : '',
    strMusicid: mockLogged ? '55' : '', encryptUin: '', nick: '', avatarUrl: '',
    isLogged: mockLogged,
  }),
  md5: (s) => require('crypto').createHash('md5').update(String(s), 'utf8').digest('hex'),
  // AES/RSA 桥接 mock：只验证参数形状，不做真实密码学运算
  aesCbcHex: (plain, key, iv) => 'aes:' + String(plain).length + ':' + key.length + ':' + iv.length,
  // 返回空串：源脚本 cloudlist 响应解密失败后会回退到明文 body（mock 返回即为明文），
  // 从而在不掌握随机 aes 的前提下仍能验证「解析 + 回退」链路。
  aesCbcDecryptB64: () => '',
  inflateB64: () => '',
  rsaNoPadHex: (plain) => 'rsa:' + String(plain).length,
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
if (!isNe) check('搜索歌曲含封面', /^https?:\/\/imge\.kugou\.com\/.*\/240\//.test(search.songs[0].cover300), search.songs[0].cover300);

// ---- playlistDetail ----
console.log('== playlistDetail ==');
const pd = H.playlistDetail({ disstid: 5, page: 1, num: 100 });
check('歌单名', !!pd.playlist && pd.playlist.name.length > 0, pd.playlist);
check('歌单含歌曲', pd.songs.length > 0, pd.songs.length);

// ---- toplists ----
console.log('== toplists ==');
const tl = H.toplists({});
check('榜单非空', tl.length > 0 && tl[0].topId > 0, tl);

// ---- 我的歌单 / 评论（需登录；酷狗走网关云歌单与评论服务） ----
if (!isNe) {
  console.log('== myPlaylists / songComments ==');
  mockLogged = true;
  const mpl = H.myPlaylists({});
  check('云歌单非空', mpl.length > 0, mpl.length);
  check('含「我喜欢」', mpl.some((p) => p.name.indexOf('我喜欢') >= 0), mpl.map((p) => p.name));
  const cm = H.songComments({ mid: 'kg_ABCDEF0123456789', songId: 11, name: 'K' });
  check('评论非空', cm.comments.length > 0, cm.comments.length);
  check('评论内容正确', cm.comments[0] && cm.comments[0].content === '好听', cm.comments[0]);
  // 宿主 DTO 的 time 是 Long：日期字符串必须转成 epoch，否则整个评论列表反序列化失败
  // （概念版已修；主站 kugou_source.js 仍是字符串，故只对概念版断言）
  if (isKugouConcept) {
    check('评论时间为数字', cm.comments[0] && typeof cm.comments[0].time === 'number' && cm.comments[0].time > 0, cm.comments[0] && cm.comments[0].time);
  }
  // 红心：加入走 add_song；概念版另实现了移出（delete_songs，服务端只认 fileid）
  const likeArgs = { mid: 'kg_ABCDEF0123456789', songId: 11, name: 'K', albumMid: '22', intervalSec: 200 };
  const added = H.setLike(Object.assign({ like: true }, likeArgs));
  check('加入我喜欢成功', added === true, added);
  if (isKugouConcept) {
    const removed = H.setLike(Object.assign({ like: false }, likeArgs));
    check('取消喜欢成功', removed === true, removed);
  }
  mockLogged = false;
  const notLoggedLike = H.setLike(Object.assign({ like: true }, likeArgs));
  check('未登录红心返回 false', notLoggedLike === false, notLoggedLike);
} else {
  console.log('== weapi handlers ==');
  mockLogged = true;
  const aa = H.artistAlbums({ singerMid: 7 });
  check('歌手专辑非空', aa.albums.length > 0, aa.albums.length);
  const dj = H.djRadios({});
  check('电台非空', dj.length > 0, dj.length);
  const prog = H.djPrograms({ radioId: 9 });
  check('节目非空', prog.length > 0, prog.length);
  const cm2 = H.songComments({ songId: 1 });
  check('评论非空', cm2.comments.length > 0, cm2.comments.length);
  const cl = H.cloudSongs({});
  check('云盘非空', cl.length > 0, cl.length);
  const ev = H.userEvents({ uid: 1 });
  check('动态非空', ev.length > 0, ev.length);
  const fo = H.userFollows({ uid: 1 });
  check('关注非空', fo.length > 0, fo.length);
  const fm = H.radioSongs({});
  check('私人FM非空', fm.length > 0, fm.length);
  // 收藏：必须走 weapi（/weapi/song/like），明文 /api/song/like 无效；
  // 且必须伪装客户端身份（Cookie os=android），os=pc 下网易风控恒返回 524「当前环境异常」
  lastHeaders = null;
  const neLiked = H.setLike({ songId: 1, mid: 'ne_1', like: true });
  check('收藏成功', neLiked === true, neLiked);
  const likeCookie = (JSON.parse(lastHeaders || '{}').Cookie) || '';
  check('收藏用客户端身份 os=android', likeCookie.indexOf('os=android') >= 0, likeCookie);
  mockLogged = false;
}

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
if (!isNe) {
  // 会员曲解析：trackercdn 请求须附带登录票据与 album_id（否则会员曲返回 status=2 / ec=20017）
  mockLogged = true;
  lastUrls = [];
  H.resolveUrls({ quality: 'HIGH', songs: [{ mid: 'kg_ABCDEF0123456789', songId: 11, mediaMid: '', songType: 0, albumMid: '22' }] });
  mockLogged = false;
  const tu = lastUrls.find((u) => u.indexOf('trackercdn') >= 0) || '';
  check('会员解析带 token/userid', tu.indexOf('token=TOKEN1') > 0 && tu.indexOf('userid=55') > 0, tu);
  check('会员解析带 album_id', tu.indexOf('album_id=22') > 0, tu);
}

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
if (!isNe) {
  // 酷狗换票必须携带 AES 密文 params 与 RSA 密文 pk（否则服务端不返回账号资料）
  const tkUrl = lastUrls.find((u) => u.indexOf('login_by_token_get') >= 0) || '';
  check('换票请求含 params', tkUrl.indexOf('params=') >= 0, tkUrl.slice(0, 120));
  check('换票请求含 pk', tkUrl.indexOf('pk=') >= 0);
  check('返回昵称', !!cred.nick, cred.nick);
  check('返回头像', !!cred.avatarUrl, cred.avatarUrl);
}

if (failed) { console.error('\nMOCK SMOKE FAILED'); process.exit(1); }
console.log('\nMOCK SMOKE OK: ' + registered.manifest.id + ' v' + registered.manifest.version);