//qmu-sig:v1:03DyvKjaW8MKvfEWg7oalXu3QGHEO1LgBlW1qZ0YBBRjBLd8/Ik4dNbAAX+v59jNESwYOB+zHpo+fJWgdYMdCQ==
/*
 * QQMusicWear 音乐源插件（网易云音乐 · Web 协议）
 * ---------------------------------------------------------------------------
 * 与 qmusic_source.js 同一宿主契约：qmu.register({ manifest, handlers })。
 * 一次只加载一个源；本文件只实现网易云，不与其它源聚合。
 *
 * 关键约定：
 *   - Song.mid 加前缀 ne_<songId>，避免与其它源主键碰撞
 *   - 播放地址解析走 /api/song/enhance/player/url(/v1)（明文 /api/，无需 AES/RSA）
 *   - 扫码登录：/api/login/qrcode/unikey 取 unikey，二维码文本由宿主渲染，
 *     轮询 /api/login/qrcode/client/login；成功后 MUSIC_U 作为会话密钥
 *   - 兼容目标：Rhino 1.7.15（ES5 风格）
 * ---------------------------------------------------------------------------
 */

var SOURCE_VERSION = 1;
var MIN_APP_VERSION = 38;

// ---------------------------------------------------------------------------
// 基础工具
// ---------------------------------------------------------------------------

function S(o, k) {
  if (!o) return '';
  var v = o[k];
  if (v === null || v === undefined) return '';
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return '' + v;
  return '';
}

function N(o, k) {
  if (!o) return 0;
  var v = o[k];
  if (v === null || v === undefined) return 0;
  if (typeof v === 'number') return v;
  var n = parseFloat(v);
  return isNaN(n) ? 0 : n;
}

function Ob(o, k) {
  var v = o ? o[k] : null;
  return (v && typeof v === 'object' && v.length === undefined) ? v : null;
}

function Ar(o, k) {
  var v = o ? o[k] : null;
  return (v && typeof v === 'object' && typeof v.length === 'number') ? v : null;
}

/** 宿主桥接返回 JSON 字符串，需解析为对象 */
function cred() {
  return JSON.parse(qmu.credential());
}

function http(method, url, headers, body, contentType, followRedirects) {
  var respText = qmu.http(
    method, url,
    JSON.stringify(headers || {}),
    body || null,
    contentType || null,
    followRedirects !== false
  );
  return JSON.parse(respText);
}

// ---------------------------------------------------------------------------
// 协议常量
// ---------------------------------------------------------------------------

var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36';
var BASE = 'https://music.163.com';
var REFERER = 'https://music.163.com/';

/** Cookie 罐：登录密钥从 credential().musickey（MUSIC_U）恢复 */
var cookieJar = {};

function cookieHeader() {
  var pairs = [];
  for (var k in cookieJar) {
    if (cookieJar.hasOwnProperty(k) && cookieJar[k]) pairs.push(k + '=' + cookieJar[k]);
  }
  return pairs.join('; ');
}

function storeCookies(resp) {
  var list = resp && resp.setCookie ? resp.setCookie : [];
  for (var i = 0; i < list.length; i++) {
    var c = list[i];
    var seg = c.split(';')[0];
    var eq = seg.indexOf('=');
    if (eq <= 0) continue;
    var name = seg.substring(0, eq).trim();
    var val = seg.substring(eq + 1).trim();
    if (name) cookieJar[name] = val;
  }
}

/** 从凭据恢复 Cookie 罐 */
function seedCookies() {
  var c = cred();
  if (c && c.musickey) cookieJar['MUSIC_U'] = c.musickey;
  if (!cookieJar['os']) cookieJar['os'] = 'pc';
  if (!cookieJar['appver']) cookieJar['appver'] = '8.9.70';
}

function headers(extra) {
  var h = {
    'User-Agent': UA,
    'Referer': REFERER,
    'Origin': BASE,
    'Cookie': cookieHeader()
  };
  if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) h[k] = extra[k];
  return h;
}

/** 明文 POST /api/...（application/x-www-form-urlencoded），返回解析后的 body */
function apiPost(path, form) {
  seedCookies();
  var resp = http('POST', BASE + path, headers({ 'Content-Type': 'application/x-www-form-urlencoded' }),
    form || '', 'application/x-www-form-urlencoded', true);
  storeCookies(resp);
  try { return JSON.parse(resp.body); } catch (e) { return {}; }
}

function apiGet(path, query) {
  seedCookies();
  var url = BASE + path + (query ? ('?' + query) : '');
  var resp = http('GET', url, headers(), null, null, true);
  storeCookies(resp);
  try { return JSON.parse(resp.body); } catch (e) { return {}; }
}

/** 登录态失效检测（301=需要登录）；仅在本地处于登录态时提示重登 */
function checkAuth(code) {
  if ((code === 301 || code === -462) && cred().isLogged) {
    qmu.emit(JSON.stringify({ type: 'CredentialExpired' }));
  }
}

// ---------------------------------------------------------------------------
// 模型映射
// ---------------------------------------------------------------------------

function coverOf(picUrl, size) {
  if (!picUrl) return '';
  var base = picUrl.split('?')[0];
  return base + '?param=' + size + 'y' + size;
}

/** "/api/v3/song/detail" / playlist track 元素 -> Song 字段 */
function trackArtistNames(t) {
  var out = [];
  var ar = Ar(t, 'ar') || Ar(t, 'artists');
  if (ar) {
    for (var i = 0; i < ar.length; i++) out.push(S(ar[i], 'name'));
    return out.join('/');
  }
  // 部分接口用 ar 字符串或 artist 字符串
  var a = S(t, 'artist') || S(t, 'singername');
  return a;
}

function parseSong(t) {
  if (!t) return null;
  var id = N(t, 'id');
  if (!id) return null;
  var al = Ob(t, 'al') || Ob(t, 'album') || {};
  var pic = S(al, 'picUrl') || S(t, 'picUrl');
  var dt = N(t, 'dt') || N(t, 'duration');
  return {
    songId: id,
    mid: 'ne_' + id,
    name: S(t, 'name'),
    singers: trackArtistNames(t),
    albumName: S(al, 'name'),
    albumMid: S(al, 'id') ? ('' + S(al, 'id')) : '',
    mediaMid: '',
    intervalSec: dt > 0 ? Math.floor(dt / 1000) : 0,
    songType: 0,
    vip: false,
    cover300: coverOf(pic, 300),
    cover500: coverOf(pic, 500)
  };
}

function parseSongs(arr) {
  var out = [];
  if (!arr) return out;
  for (var i = 0; i < arr.length; i++) {
    var s = parseSong(arr[i]);
    if (s) out.push(s);
  }
  return out;
}

function parsePlaylist(p) {
  if (!p) return null;
  return {
    disstid: N(p, 'id'),
    name: S(p, 'name'),
    picUrl: S(p, 'coverImgUrl') || S(p, 'picUrl'),
    songCount: N(p, 'trackCount'),
    creatorNick: Ob(p, 'creator') ? S(Ob(p, 'creator'), 'nickname') : ''
  };
}

function parsePlaylists(arr) {
  var out = [];
  if (!arr) return out;
  for (var i = 0; i < arr.length; i++) {
    var p = parsePlaylist(arr[i]);
    if (p && p.disstid) out.push(p);
  }
  return out;
}

/** 从 mid 或 songId 取数字 id */
function idOf(args) {
  if (args && args.songId) return N(args, 'songId');
  var mid = args && args.mid ? ('' + args.mid) : '';
  if (mid.indexOf('ne_') === 0) return parseInt(mid.substring(3), 10) || 0;
  return parseInt(mid, 10) || 0;
}

// ---------------------------------------------------------------------------
// 业务处理器
// ---------------------------------------------------------------------------

var handlers = {

  ping: function () { return 'ok'; },

  /** 首页推送大卡（网易云：每日推荐 / 私人推荐 / 排行榜 / 歌单广场） */
  homeFeed: function (args) {
    var cards = [];
    var daily = [];
    try { daily = handlers.recommendSongs({}); } catch (e) { qmu.log('ne homeFeed recommend err: ' + e); }
    if (!daily || !daily.length) {
      try { daily = handlers.recommendNewSongs({}); } catch (e2) { }
    }
    if (!daily) daily = [];
    var rep = daily.length ? daily[0] : null;
    cards.push({
      id: 'daily', title: '每日推荐',
      subtitle: daily.length ? ('为你推荐 · ' + daily.length + ' 首') : '为你推荐',
      action: 'daily',
      coverUrl: rep ? rep.cover300 : '', songName: rep ? rep.name : '', singers: rep ? rep.singers : '',
      songs: daily
    });
    if (daily.length > 1) {
      var lucky = daily[Math.floor(Math.random() * daily.length)];
      cards.push({
        id: 'guess', title: '私人推荐', subtitle: '根据口味生成', action: 'daily',
        coverUrl: lucky.cover300, songName: lucky.name, singers: lucky.singers, songs: daily
      });
    }
    cards.push({ id: 'rank', title: '排行榜', subtitle: '云音乐飙升榜 · 热歌榜', action: 'rank' });
    cards.push({ id: 'square', title: '歌单广场', subtitle: '官方精选歌单', action: 'square' });
    return { cards: cards };
  },

  /** 每日推荐（需登录）；未登录/失败回退推荐新歌 */
  recommendSongs: function (args) {
    try {
      var r = apiPost('/api/recommend/songs', '');
      checkAuth(r.code);
      var data = Ob(r, 'data') || r;
      var daily = Ar(data, 'dailySongs') || Ar(data, 'recommend');
      if (daily && daily.length) return parseSongs(daily);
    } catch (e) { qmu.log('ne recommendSongs err: ' + e); }
    return handlers.recommendNewSongs({});
  },

  /** 推荐新歌（无需登录） */
  recommendNewSongs: function (args) {
    var r = apiPost('/api/personalized/newsong', 'limit=20');
    var result = Ar(r, 'result');
    var songs = [];
    if (result) {
      for (var i = 0; i < result.length; i++) {
        var s = parseSong(Ob(result[i], 'song') || result[i]);
        if (s) songs.push(s);
      }
    }
    return songs;
  },

  /**
   * 歌单详情（含收藏歌单 / 我喜欢的音乐）。
   * /api/v6/playlist/detail 的 tracks 只有约 10 条，但 trackIds 完整；
   * 因此按其偏移切片，再用 /api/v3/song/detail 批量取详情实现真正的分页。
   */
  playlistDetail: function (args) {
    var id = N(args, 'disstid');
    var num = N(args, 'num') || 100;
    var page = N(args, 'page') || 1;
    var r = apiPost('/api/v6/playlist/detail', 'id=' + id + '&n=1000&s=8');
    checkAuth(r.code);
    var pl = Ob(r, 'playlist');
    if (!pl) return { playlist: null, songs: [] };
    var songs;
    var ids = Ar(pl, 'trackIds');
    if (ids && ids.length) {
      var start = (page - 1) * num;
      var slice = [];
      for (var i = start; i < ids.length && slice.length < num; i++) {
        var sid = N(ids[i], 'id');
        if (sid) slice.push(sid);
      }
      songs = fetchSongDetails(slice);
    } else {
      songs = page > 1 ? [] : parseSongs(Ar(pl, 'tracks'));
    }
    return { playlist: parsePlaylist(pl), songs: songs };
  },

  /** 排行榜列表 */
  toplists: function (args) {
    var r = apiPost('/api/toplist', '');
    var list = Ar(r, 'list') || [];
    var out = [];
    for (var i = 0; i < list.length; i++) {
      out.push({
        topId: N(list[i], 'id'),
        title: S(list[i], 'name'),
        picUrl: S(list[i], 'coverImgUrl'),
        updateInfo: S(list[i], 'updateFrequency')
      });
    }
    return out;
  },

  /** 排行榜歌曲（榜单 id 即歌单 id）；复用 playlistDetail 的 trackIds 批量取详情 */
  toplistSongs: function (args) {
    var topId = N(args, 'topId');
    var res = handlers.playlistDetail({ disstid: topId, page: 1, num: 200 });
    return res.songs || [];
  },

  /** 歌手歌曲（分页）；singerMid 为歌手数字 id */
  artistSongs: function (args) {
    var sid = parseInt('' + (args.singerMid || '0'), 10) || 0;
    var page = N(args, 'page') || 1;
    var size = N(args, 'size') || 30;
    var offset = (page - 1) * size;
    var r = apiPost('/api/v1/artist/songs', 'id=' + sid + '&offset=' + offset + '&limit=' + size + '&order=time');
    checkAuth(r.code);
    var songs = parseSongs(Ar(r, 'songs'));
    return { songs: songs, hasMore: songs.length >= size };
  },

  /** 专辑详情 */
  albumSongs: function (args) {
    var aid = parseInt('' + (args.albumMid || '0'), 10) || 0;
    var r = apiPost('/api/v1/album/' + aid, '');
    var album = Ob(r, 'album') || {};
    var songs = parseSongs(Ar(r, 'songs'));
    return {
      name: S(album, 'name'),
      coverUrl: S(album, 'picUrl') || S(album, 'blurPicUrl') ||
        (songs.length ? songs[0].cover500 : ''),
      songs: songs
    };
  },

  /** 歌单广场栏目（推荐歌单 / 分类热榜） */
  musicHallShelves: function (args) {
    var shelves = [];
    var rec = Ar(apiPost('/api/personalized/playlist', 'limit=6&total=true&n=1000'), 'result');
    if (rec) shelves.push({ title: '推荐歌单', playlists: parsePlaylists(rec) });
    var hot = Ar(apiPost('/api/playlist/list', 'cat=%E5%85%A8%E9%83%A8&order=hot&limit=12&offset=0'), 'playlists');
    if (hot) shelves.push({ title: '热门歌单', playlists: parsePlaylists(hot) });
    return shelves;
  },

  /** 我的歌单（创建，不含收藏） */
  myPlaylists: function (args) {
    return filterMyPlaylists(false);
  },

  /** 收藏歌单 */
  favPlaylists: function (args) {
    return filterMyPlaylists(true);
  },

  /** 当前登录用户资料 */
  userProfile: function (args) {
    var r = apiPost('/api/nuser/account/get', '');
    checkAuth(r.code);
    var profile = Ob(r, 'profile');
    if (!profile) return null;
    return {
      musicid: N(profile, 'userId'),
      nick: S(profile, 'nickname'),
      avatarUrl: S(profile, 'avatarUrl'),
      encryptUin: ''
    };
  },

  /** 歌词（原文 LRC）；源侧已含译文/罗马音另取 */
  lyric: function (args) {
    var r = apiPost('/api/song/lyric', 'id=' + idOf(args) + '&lv=-1&kv=-1&tv=-1');
    return Ob(r, 'lrc') ? S(Ob(r, 'lrc'), 'lyric') : '';
  },

  lyricTrans: function (args) {
    var r = apiPost('/api/song/lyric', 'id=' + idOf(args) + '&lv=-1&kv=-1&tv=-1');
    return Ob(r, 'tlyric') ? S(Ob(r, 'tlyric'), 'lyric') : '';
  },

  lyricRoma: function (args) {
    var r = apiPost('/api/song/lyric', 'id=' + idOf(args) + '&lv=-1&kv=-1&tv=-1');
    return Ob(r, 'romalrc') ? S(Ob(r, 'romalrc'), 'lyric') : '';
  },

  /** 聚合搜索：歌曲 / 歌手 / 歌单 */
  searchAll: function (args) {
    var q = args.query ? ('' + args.query) : '';
    var enc = encodeURIComponent(q);
    var songs = parseSongs(Ar(Ob(apiPost('/api/search/get/web', 's=' + enc + '&type=1&offset=0&limit=30&total=true'), 'result'), 'songs'));
    var singerRes = Ob(apiPost('/api/search/get/web', 's=' + enc + '&type=100&offset=0&limit=10&total=true'), 'result');
    var singers = [];
    var ar = singerRes ? Ar(singerRes, 'artists') : null;
    if (!ar && singerRes) ar = Ar(singerRes, 'artists');
    if (ar) {
      for (var i = 0; i < ar.length; i++) {
        var a = ar[i];
        singers.push({ mid: '' + N(a, 'id'), id: N(a, 'id'), name: S(a, 'name') });
      }
    }
    var plRes = Ob(apiPost('/api/search/get/web', 's=' + enc + '&type=1000&offset=0&limit=10&total=true'), 'result');
    var playlists = parsePlaylists(plRes ? Ar(plRes, 'playlists') : null);
    return { songs: songs, singers: singers, playlists: playlists };
  },

  /** 批量解析播放地址（明文 /api/song/enhance/player/url）；加密格式（.flac 加密）不可本地播放 */
  resolveUrls: function (args) {
    var quality = args.quality || 'STANDARD';
    var songs = Ar(args, 'songs') || [];
    var levels = levelChain(quality);
    var items = [];
    var debug = '';
    for (var i = 0; i < songs.length; i++) {
      var id = N(songs[i], 'songId') || parseInt(('' + S(songs[i], 'mid')).substring(3), 10) || 0;
      items.push(resolveOne(id, levels, i === 0 ? function (d) { debug = d; } : null));
    }
    return { items: items, debug: debug };
  },

  /** 加入/移出「我喜欢」 */
  setLike: function (args) {
    var id = N(args, 'songId') || idOf(args);
    var like = args.like === true;
    var r = apiPost('/api/song/like', 'trackId=' + id + '&like=' + (like ? 'true' : 'false'));
    checkAuth(r.code);
    return r.code === 200;
  },

  /**
   * 扫码登录：
   *   1) POST /api/login/qrcode/unikey 取 unikey
   *   2) emit QrReady{text=https://music.163.com/login?codekey=<unikey>}（宿主渲染二维码）
   *   3) 轮询 POST /api/login/qrcode/client/login（800 过期 / 801 待扫 / 802 已扫 / 803 成功）
   *   4) 成功后 MUSIC_U 为会话密钥，再取账号资料补全 uid/昵称/头像
   */
  qrLogin: function (args) {
    try {
      cookieJar = {};
      var u = apiPost('/api/login/qrcode/unikey', 'type=1');
      var unikey = S(u, 'unikey');
      if (!unikey) return fail('获取二维码失败（' + S(u, 'message') + '）');
      emit('QrReady', { text: 'https://music.163.com/login?codekey=' + unikey });

      var polls = 0;
      while (true) {
        if (++polls > 120) return fail('登录轮询超时');
        qmu.sleep(2000);
        var r = apiPost('/api/login/qrcode/client/login', 'key=' + encodeURIComponent(unikey) + '&type=1');
        var code = N(r, 'code');
        if (code === 800) { emit('Expired'); return null; }
        if (code === 801) { emit('WaitingScan'); continue; }
        if (code === 802) { emit('ScannedConfirm'); continue; }
        if (code === 803) {
          seedLoginCookie(r);
          var musickey = cookieJar['MUSIC_U'] || '';
          if (!musickey) return fail('登录成功但未取得会话密钥');
          var prof = null;
          try { prof = handlers.userProfile({}); } catch (e1) { }
          var cred = {
            musicid: prof ? N(prof, 'musicid') : 0,
            musickey: musickey,
            strMusicid: prof && prof.musicid ? ('' + prof.musicid) : '',
            encryptUin: '',
            nick: prof ? S(prof, 'nick') : '',
            avatarUrl: prof ? S(prof, 'avatarUrl') : ''
          };
          emit('Success', { credential: cred });
          return cred;
        }
        // 其它状态继续等待
        emit('WaitingScan');
      }
    } catch (e) {
      return fail(e && e.message ? e.message : ('' + e));
    }
  }
};

// ---------------------------------------------------------------------------
// 辅助实现
// ---------------------------------------------------------------------------

var QUALITY_LEVELS = {
  STANDARD: ['standard', 'higher'],
  HIGH: ['exhigh', 'higher', 'standard'],
  LOSSLESS: ['lossless', 'exhigh', 'higher'],
  HI_RES: ['hires', 'lossless', 'exhigh']
};

function levelChain(quality) {
  return QUALITY_LEVELS[quality] || QUALITY_LEVELS.STANDARD;
}

/** 批量取歌曲详情（/api/v3/song/detail，每批 100） */
function fetchSongDetails(ids) {
  var out = [];
  if (!ids || !ids.length) return out;
  for (var i = 0; i < ids.length; i += 100) {
    var chunk = ids.slice(i, i + 100);
    var c = [];
    for (var j = 0; j < chunk.length; j++) c.push({ id: chunk[j] });
    var r = apiPost('/api/v3/song/detail', 'c=' + encodeURIComponent(JSON.stringify(c)));
    var songs = Ar(r, 'songs');
    if (songs) {
      var parsed = parseSongs(songs);
      for (var k = 0; k < parsed.length; k++) out.push(parsed[k]);
    }
  }
  return out;
}

/**
 * 单曲按音质链解析；返回 {url, ekey, encrypted, prefix, ext} 或 null。
 * /api/song/enhance/player/url/v1 明文可用；付费/会员曲返回 url=null + code=-110，
 * 此时按链降档继续尝试，全部失败返回 null（宿主自动跳过）。
 */
function resolveOne(id, levels, onDebug) {
  if (!id) { if (onDebug) onDebug('no id'); return null; }
  for (var i = 0; i < levels.length; i++) {
    var level = levels[i];
    var enc = (level === 'lossless' || level === 'hires') ? 'flac' : 'mp3';
    var r = apiPost('/api/song/enhance/player/url/v1',
      'ids=%5B' + id + '%5D&level=' + level + '&encodeType=' + enc);
    var data = Ar(r, 'data');
    var d = data && data.length ? data[0] : null;
    if (d && S(d, 'url')) {
      var url = S(d, 'url');
      if (url.indexOf('http') !== 0) url = 'https://' + url.replace(/^\/\//, '');
      var type = S(d, 'type') || S(d, 'encodeType') || 'mp3';
      return { url: url, ekey: '', encrypted: false, prefix: level, ext: type };
    }
    if (onDebug) onDebug('L' + i + ' code=' + N(r, 'code') + ' dcode=' + (d ? N(d, 'code') : '-'));
  }
  return null;
}

function filterMyPlaylists(subscribed) {
  var c = cred();
  var uid = c && c.musicid ? c.musicid : 0;
  if (!uid) return [];
  var r = apiPost('/api/user/playlist', 'uid=' + uid + '&limit=1000&offset=0');
  checkAuth(r.code);
  var list = Ar(r, 'playlist') || [];
  var out = [];
  for (var i = 0; i < list.length; i++) {
    var isSub = list[i].subscribed === true;
    if (isSub === subscribed) {
      var p = parsePlaylist(list[i]);
      if (p && p.disstid) out.push(p);
    }
  }
  return out;
}

/** 把 Set-Cookie 头或响应 cookie 字段并入 Cookie 罐（登录成功用） */
function seedLoginCookie(resp) {
  cookieJar = {};
  storeCookies(resp);
  var ck = S(resp, 'cookie');
  if (ck) {
    var parts = ck.split(';');
    for (var i = 0; i < parts.length; i++) {
      var seg = parts[i].trim();
      var eq = seg.indexOf('=');
      if (eq <= 0) continue;
      cookieJar[seg.substring(0, eq)] = seg.substring(eq + 1);
    }
  }
}

function emit(type, extra) {
  var ev = { type: type };
  if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) ev[k] = extra[k];
  qmu.emit(JSON.stringify(ev));
}

function fail(message) {
  emit('Error', { message: message });
  return null;
}

// ---------------------------------------------------------------------------
// 注册
// ---------------------------------------------------------------------------

qmu.register({
  manifest: {
    id: 'netease-web',
    name: '网易云音乐',
    themeColor: '#E23B2E',
    version: SOURCE_VERSION,
    minAppVersion: MIN_APP_VERSION,
    playbackHeaders: { 'User-Agent': UA, 'Referer': REFERER },
    imageHostSuffix: '126.net',
    imageHeaders: { 'Referer': REFERER },
    qualityPrefixes: {
      STANDARD: ['standard'],
      HIGH: ['higher', 'exhigh'],
      LOSSLESS: ['lossless'],
      HI_RES: ['hires']
    }
  },
  handlers: handlers
});