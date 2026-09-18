//qmu-sig:v1:zOnSsbhIaB0stOXcZHwIS9Yoz9T2nZ+Dcy0XDzyhpn/uFpKONodzAydnU9HquuE2enuLvJdlgo3iLTwXZ7gdCw==
/*
 * QQMusicWear 音乐源插件（Web 协议）
 * ---------------------------------------------------------------------------
 * 本文件是 QQMusicWear 的可下载音乐源：包含全部协议实现（端点、模块、
 * 扫码登录链、vkey 解析、响应解析）。APK 只含通用运行框架（Rhino 桥），
 * 不写入任何协议明文。
 *
 * 桥接 API（由宿主注入的全局对象 qmu）：
 *   qmu.http(method, url, headersJson, body, contentType, followRedirects)
 *       -> {status, location, setCookie:[], body, bodyB64}
 *   qmu.credential() -> {musicid, musickey, strMusicid, encryptUin, nick, avatarUrl}
 *   qmu.md5(s) / qmu.b64decode(s) / qmu.sleep(ms) / qmu.log(s) / qmu.guid()
 *   qmu.emit(eventJson) -> 向宿主发事件（扫码登录流程）
 *
 * 注册契约：qmu.register({ manifest, handlers })
 * 兼容目标：Rhino 1.7.15（ES6 子集；本文件保持 ES5 风格以最大化兼容）
 * ---------------------------------------------------------------------------
 */

var SOURCE_VERSION = 9;

/** APK 兼容性闸门：宿主 versionCode 低于该值将拒绝加载本源 */
var MIN_APP_VERSION = 27;

// ---------------------------------------------------------------------------
// 基础工具
// ---------------------------------------------------------------------------

/** 取字符串字段（宽松：非字符串原样转字符串，空安全） */
function S(o, k) {
  if (!o) return '';
  var v = o[k];
  if (v === null || v === undefined) return '';
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return '' + v;
  return '';
}

/** 取数值字段（宽松解析，失败返回 0） */
function N(o, k) {
  if (!o) return 0;
  var v = o[k];
  if (v === null || v === undefined) return 0;
  if (typeof v === 'number') return v;
  var n = parseFloat(v);
  return isNaN(n) ? 0 : n;
}

/** 取嵌套对象字段 */
function Ob(o, k) {
  var v = o ? o[k] : null;
  return (v && typeof v === 'object' && v.length === undefined) ? v : null;
}

/** 取数组字段 */
function Ar(o, k) {
  var v = o ? o[k] : null;
  return (v && typeof v === 'object' && typeof v.length === 'number') ? v : null;
}

/** 取布尔字段 */
function Bo(o, k) {
  var v = o ? o[k] : null;
  return v === true || v === 1 || v === '1';
}

/** 发起一次桥接 HTTP，返回解析后的响应对象 */
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

function getText(url, headers) {
  return http('GET', url, headers || {}).body;
}

/** hash33（g_tk / ptqrtoken 同源算法） */
function hash33(s, init) {
  var h = init;
  for (var i = 0; i < s.length; i++) {
    h += (h << 5) + s.charCodeAt(i);
  }
  return h & 0x7fffffff;
}

/** musickey -> g_tk（未登录返回标准初值 5381） */
function gtk(musickey) {
  return musickey ? hash33(musickey, 5381) : 5381;
}

/** qrsig -> ptqrtoken */
function ptqrtoken(qrsig) {
  return hash33(qrsig, 0);
}

// ---------------------------------------------------------------------------
// 协议常量（全部收敛在本源插件，不进 APK）
// ---------------------------------------------------------------------------

var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36';
var REFERER = 'https://y.qq.com/';
var FCG = 'https://u.y.qq.com/cgi-bin/musicu.fcg';
var STREAM_HOST = 'https://isure.stream.qqmusic.qq.com/';

// 扫码登录（QQ 互联 Web 链路，2026-09 PC 实测）：
// 主 appid 是 QQ 互联 716027609；100497308 只是 pt_3rd_aid。
var APPID = '716027609';
var DAID = '383';
var PT_3RD_AID = '100497308';
var S_URL = 'https://graph.qq.com/oauth2.0/login_jump';
var AUTHORIZE_URL = 'https://graph.qq.com/oauth2.0/authorize';
var REDIRECT_URI = 'https://y.qq.com/portal/wx_redirect.html?login_type=1' +
  '&surl=https%3A%2F%2Fy.qq.com%2F%23&use_customer_cb=0';
// ptlogin 前端版本（来自官方登录页 ptui.ptui_version，需保持一致）
var JS_VER = '26090116';
var XLOGIN_URL = 'https://xui.ptlogin2.qq.com/cgi-bin/xlogin' +
  '?appid=' + APPID + '&daid=' + DAID + '&style=33&theme=2' +
  '&login_text=%E6%8E%88%E6%9D%83%E5%B9%B6%E7%99%BB%E5%BD%95' +
  '&hide_title_bar=1&hide_border=1&target=self' +
  '&s_url=' + encodeURIComponent(S_URL) +
  '&pt_3rd_aid=' + PT_3RD_AID;

/** 音质 -> 文件名前缀链（依次尝试，第一个解析出 purl 的生效） */
var QUALITY_CHAINS = {
  STANDARD: ['M500', 'C200'],
  HIGH: ['M800', 'M500', 'C400'],
  LOSSLESS: ['AI00', 'F0M0', 'M800'],
  HI_RES: ['AIM0', 'O800', 'O600', 'F0M0', 'AI00', 'M800']
};

/** 文件名前缀 -> 扩展名（mflac/mgg 为加密格式，无法本地播放） */
function extOf(prefix) {
  if (prefix === 'F0M0' || prefix === 'AIM0') return 'mflac';
  if (prefix === 'AI00') return 'flac';
  if (['O400', 'O600', 'O800', 'O801', 'O4M0', 'O6M0', 'O8M0', 'O8M1'].indexOf(prefix) >= 0) return 'mgg';
  if (['C200', 'C400', 'C600'].indexOf(prefix) >= 0) return 'm4a';
  return 'mp3';
}

// ---------------------------------------------------------------------------
// 登录态与公共请求
// ---------------------------------------------------------------------------

function cred() {
  return JSON.parse(qmu.credential());
}

/** 向宿主发全局事件（凭据过期等，独立于扫码登录事件流） */
function emitGlobal(type, extra) {
  var ev = { type: type };
  if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) ev[k] = extra[k];
  qmu.emit(JSON.stringify(ev));
}

/** 登录 Cookie（uin / qm_keyst 等），未登录返回空 */
function authCookies(c) {
  if (!c.musicid || !c.musickey) return {};
  var uin = c.strMusicid || ('' + c.musicid);
  return {
    uin: uin,
    qqmusic_uin: uin,
    qm_keyst: c.musickey,
    qqmusic_key: c.musickey
  };
}

function cookieHeader(c) {
  var pairs = [];
  var cookies = authCookies(c);
  for (var k in cookies) {
    if (cookies.hasOwnProperty(k)) pairs.push(k + '=' + cookies[k]);
  }
  return pairs.length ? pairs.join('; ') : null;
}

/** Web 平台 comm（musicu.fcg 公共参数） */
function webComm(c) {
  var comm = {
    ct: 24, cv: 4747474, platform: 'yqq.json', chid: 0,
    g_tk: gtk(c.musickey),
    g_tk_new_20200303: gtk(c.musickey),
    format: 'json', inCharset: 'utf-8', outCharset: 'utf-8',
    notice: 0, needNewCode: 1
  };
  if (c.musicid) comm.uin = c.musicid;
  return comm;
}

/** 移动端 comm（音乐厅首页等移动专属模块要求 platform=android） */
function mobileComm(c) {
  var comm = {
    ct: 19, cv: 190319, platform: 'android',
    g_tk: gtk(c.musickey),
    format: 'json', inCharset: 'utf-8', outCharset: 'utf-8',
    notice: 0, needNewCode: 0
  };
  if (c.musicid) comm.uin = c.musicid;
  return comm;
}

/**
 * musicu.fcg RPC。
 * 2026-09 实测：POST 大面积 500001，GET + data= 为稳定通道；
 * 「GET → GET 重试 → POST 兜底」策略与旧实现一致。
 */
function musicuCall(moduleName, methodName, param, commObj) {
  var c = cred();
  var root = {
    comm: commObj || webComm(c),
    req_1: { module: moduleName, method: methodName, param: param || {} }
  };
  var payload = JSON.stringify(root);
  var ck = cookieHeader(c);
  var last = null;

  var tries = 0;
  while (tries < 2) {
    if (last && last.code === 0) return last;
    try {
      var resp = http('GET', FCG + '?data=' + encodeURIComponent(payload),
        { 'User-Agent': UA, 'Referer': REFERER, 'Cookie': ck || '' });
      if (resp.body) last = JSON.parse(resp.body);
    } catch (e) { qmu.log('musicu GET err: ' + e); }
    tries++;
  }
  if (last && last.code === 0) return last;

  try {
    var resp2 = http('POST', FCG,
      { 'User-Agent': UA, 'Referer': REFERER, 'Cookie': ck || '' },
      payload, 'application/json; charset=utf-8', true);
    if (resp2.body) last = JSON.parse(resp2.body);
  } catch (e2) { qmu.log('musicu POST err: ' + e2); }
  if (!last) throw new Error('musicu.fcg 请求失败');

  // 登录态失效探测（2001/2002 = 需要登录/凭据过期）：登录态下通知宿主重新登录
  var code = (typeof last.code === 'number') ? last.code
    : (last.req_1 && typeof last.req_1.code === 'number') ? last.req_1.code : 0;
  if (c.musicid && c.musickey && (code === 2001 || code === 2002)) {
    qmu.log('credential expired (code=' + code + ')');
    emitGlobal('CredentialExpired');
  }
  return last;
}

// ---------------------------------------------------------------------------
// 响应解析（宽松；第三方接口字段命名不稳定）
// ---------------------------------------------------------------------------

/** 封面地址（专辑封面 CDN 模板） */
function coverUrl(albumMid, size) {
  if (!albumMid) return '';
  return 'https://y.gtimg.cn/music/photo_new/T002R' + size + 'x' + size +
    'M000' + albumMid + '.jpg?max_age=2592000';
}

/** 单个歌曲对象 -> DTO（兼容 songmid/mid、songname/name 等多种命名） */
function parseSong(obj) {
  if (!obj) return null;
  var mid = S(obj, 'songmid') || S(obj, 'mid');
  if (!mid) return null;
  var name = S(obj, 'songname') || S(obj, 'name');
  var singerArr = Ar(obj, 'singer') || Ar(obj, 'singers');
  var singers = '';
  if (singerArr) {
    var names = [];
    for (var i = 0; i < singerArr.length; i++) {
      var n = S(singerArr[i], 'name') || S(singerArr[i], 'title');
      if (n) names.push(n);
    }
    singers = names.join(' / ');
  }
  var album = Ob(obj, 'album');
  var albumMid = S(obj, 'albummid') || (album ? S(album, 'mid') : '');
  var albumName = S(obj, 'albumname') || (album ? S(album, 'name') : '');
  var songId = N(obj, 'songid') || N(obj, 'id');
  var pay = Ob(obj, 'pay');
  var vip = pay ? ((N(pay, 'pay_play') || N(pay, 'payplay')) !== 0) : false;
  return {
    songId: songId,
    mid: mid,
    name: name,
    singers: singers,
    albumName: albumName,
    albumMid: albumMid,
    mediaMid: S(Ob(obj, 'file'), 'mediaMid'),
    intervalSec: N(obj, 'interval'),
    songType: N(obj, 'songtype') || N(obj, 'song_type'),
    vip: vip,
    cover300: coverUrl(albumMid, 300),
    cover500: coverUrl(albumMid, 500)
  };
}

/** 歌曲条目 -> DTO：兼容直接歌曲对象 / {songInfo:{...}} / {songInfo:"json"} 包装 */
function parseSongEntry(item) {
  if (!item || typeof item !== 'object' || item.length !== undefined) return null;
  var inner = Ob(item, 'songInfo') || Ob(item, 'song_info');
  if (!inner) {
    var raw = item['songInfo'] !== undefined ? item['songInfo']
      : (item['song_info'] !== undefined ? item['song_info'] : null);
    if (typeof raw === 'string') {
      try { inner = JSON.parse(raw); } catch (e) { inner = null; }
    }
  }
  return parseSong(inner || item);
}

/** 按点分路径定位节点 */
function locate(root, path) {
  var cur = root;
  var segs = path.split('.');
  for (var i = 0; i < segs.length; i++) {
    if (!cur || typeof cur !== 'object') return null;
    cur = cur[segs[i]];
  }
  return cur === undefined ? null : cur;
}

/** 递归查找第一个「元素都是对象、且对象包含任一候选键组全部键」的数组 */
function findArrayWithKeys(root, keySets) {
  if (!root || typeof root !== 'object') return null;
  var i, j, k, arr, obj;
  if (root.length !== undefined && typeof root.length === 'number') {
    var objs = [];
    for (i = 0; i < root.length; i++) {
      if (root[i] && typeof root[i] === 'object' && root[i].length === undefined) objs.push(root[i]);
    }
    if (objs.length) {
      var all = true;
      for (i = 0; i < objs.length && all; i++) {
        var hit = false;
        for (j = 0; j < keySets.length && !hit; j++) {
          var keys = keySets[j], ok = true;
          for (k = 0; k < keys.length; k++) {
            if (objs[i][keys[k]] === undefined) { ok = false; break; }
          }
          if (ok) hit = true;
        }
        if (!hit) all = false;
      }
      if (all) return root;
    }
    for (i = 0; i < root.length; i++) {
      var r = findArrayWithKeys(root[i], keySets);
      if (r) return r;
    }
    return null;
  }
  for (var key in root) {
    if (root.hasOwnProperty(key)) {
      var r2 = findArrayWithKeys(root[key], keySets);
      if (r2) return r2;
    }
  }
  return null;
}

var SONG_KEY_SETS = [
  ['songmid', 'songname'],
  ['mid', 'name', 'singer'],
  ['songmid', 'name'],
  ['mid', 'songname']
];

function parseSongsLoose(root, preferredKeys) {
  var i, node, arr, songs;
  for (i = 0; i < preferredKeys.length; i++) {
    node = locate(root, preferredKeys[i]);
    if (!node) continue;
    arr = null;
    if (node.length !== undefined && typeof node.length === 'number') arr = node;
    else {
      arr = Ar(node, 'list') || Ar(node, 'track_info') || Ar(node, 'songlist') || Ar(node, 'songs');
    }
    if (!arr) continue;
    songs = mapSongs(arr);
    if (songs.length) return songs;
  }
  var fallback = findArrayWithKeys(root, SONG_KEY_SETS);
  return fallback ? mapSongs(fallback) : [];
}

function mapSongs(arr) {
  var out = [];
  for (var i = 0; i < arr.length; i++) {
    if (arr[i] && typeof arr[i] === 'object' && arr[i].length === undefined) {
      var s = parseSong(arr[i]);
      if (s) out.push(s);
    }
  }
  return out;
}

var PLAYLIST_KEY_SETS = [
  ['disstid', 'dissname'],
  ['disstid', 'dirname'],
  ['dissid', 'dissname'],
  ['tid', 'dirName'],
  ['id', 'title', 'picurl'],
  ['id', 'title', 'song_count'],
  ['tid', 'title']
];

function parsePlaylistsLoose(root) {
  var arr = findArrayWithKeys(root, PLAYLIST_KEY_SETS);
  if (!arr) return [];
  var out = [];
  for (var i = 0; i < arr.length; i++) {
    var obj = arr[i];
    if (!obj || typeof obj !== 'object' || obj.length !== undefined) continue;
    var disstid = N(obj, 'disstid') || N(obj, 'dissid') || N(obj, 'id') || N(obj, 'tid');
    if (!disstid) continue;
    var creator = Ob(obj, 'creator');
    out.push({
      disstid: disstid,
      name: S(obj, 'dissname') || S(obj, 'dirName') || S(obj, 'diss_name') ||
        S(obj, 'dirname') || S(obj, 'title') || S(obj, 'name'),
      picUrl: S(obj, 'picUrl') || S(obj, 'picurl') || S(obj, 'diss_cover') ||
        S(obj, 'logo') || S(obj, 'imgurl'),
      songCount: N(obj, 'songNum') || N(obj, 'songnum') || N(obj, 'song_count'),
      creatorNick: S(obj, 'nick') || S(obj, 'nickname') ||
        (creator ? (S(creator, 'nick') || S(creator, 'name')) : '')
    });
  }
  return out;
}

var SINGER_KEY_SETS = [
  ['singerID', 'singerName'],
  ['singermid', 'singername'],
  ['singerid', 'singername'],
  ['singermid', 'name']
];

function parseSingersLoose(root) {
  var arr = findArrayWithKeys(root, SINGER_KEY_SETS);
  if (!arr) return [];
  var out = [];
  for (var i = 0; i < arr.length; i++) {
    var obj = arr[i];
    if (!obj || typeof obj !== 'object' || obj.length !== undefined) continue;
    var name = S(obj, 'singername') || S(obj, 'singerName');
    if (!name) continue;
    out.push({
      mid: S(obj, 'singermid') || S(obj, 'singerMID'),
      id: N(obj, 'singerid') || N(obj, 'singerID'),
      name: name
    });
  }
  return out;
}

/** 歌词字段：兼容 {req_1:{data:{lyric}}} / {data:{lyric}} / {lyric}；base64 解码（解码结果不像 LRC 则返回空串走兜底） */
function extractLyric(resp) {
  return extractLrcField(resp, 'lyric');
}

/** 通用 LRC 字段提取：key 可为 lyric（原文）/ trans（翻译） */
function extractLrcField(resp, key) {
  var req1 = Ob(resp, 'req_1');
  var data = (req1 && (Ob(req1, 'data') || req1)) || Ob(resp, 'data') || resp;
  var raw = S(data, key);
  if (!raw) return '';
  // 仅当形似 base64 时才解码：宿主 b64decode 对非法输入会抛 Java 异常，
  // 该异常在部分 Rhino 配置下无法被 JS catch 捕获，会导致整个 handler 失败（表现为「暂无歌词」）。
  if (/^[A-Za-z0-9+/=\r\n]+$/.test(raw)) {
    var decoded = '';
    try { decoded = qmu.b64decode(raw); } catch (e) { decoded = ''; }
    if (decoded.indexOf('[') === 0 || decoded.indexOf('[0') >= 0) return decoded;
    // 解码结果不像 LRC（如拿到加密串/乱码）：返回空串让兜底通道接管
    return '';
  }
  // 明文 LRC（经典接口 nobase64=1 通道）直接返回
  return raw;
}

/** 递归查找第一个包含全部指定键的对象 */
function findObjectWithKeys(root, keys) {
  if (!root || typeof root !== 'object') return null;
  var i, ok, key;
  if (root.length === undefined) {
    ok = true;
    for (i = 0; i < keys.length; i++) {
      if (root[keys[i]] === undefined) { ok = false; break; }
    }
    if (ok) return root;
  }
  for (key in root) {
    if (root.hasOwnProperty(key) && root[key] && typeof root[key] === 'object') {
      var r = findObjectWithKeys(root[key], keys);
      if (r) return r;
    }
  }
  return null;
}

// ---------------------------------------------------------------------------
// 业务处理器（handlers）
// ---------------------------------------------------------------------------

var handlers = {

  ping: function () { return 'ok'; },

  /** 猜你喜欢（私人电台，需登录）；不足 15 首并入推荐新歌 */
  recommendSongs: function (args) {
    var c = cred();
    var songs = [];
    try {
      var resp = musicuCall('music.radioProxy.MbTrackRadioSvr', 'get_radio_track', {
        id: 99, num: 15, from: 0, scene: 0, song_ids: []
      });
      var data = Ob(Ob(resp, 'req_1'), 'data');
      var tracks = data ? (Ar(data, 'tracks') || Ar(data, 'track_info')) : null;
      if (tracks) {
        for (var i = 0; i < tracks.length; i++) {
          var t = tracks[i];
          var inner = Ob(t, 'songInfo') || Ob(t, 'song_info') || t;
          var s = parseSong(inner);
          if (s) songs.push(s);
        }
      }
    } catch (e) { qmu.log('guessRecommend err: ' + e); }
    if (songs.length >= 15) return songs;
    var extra = handlers.recommendNewSongs({});
    var seen = {}, merged = [];
    var all = songs.concat(extra);
    for (var j = 0; j < all.length; j++) {
      if (seen[all[j].mid]) continue;
      seen[all[j].mid] = true;
      merged.push(all[j]);
      if (merged.length >= 30) break;
    }
    return merged;
  },

  /** 推荐新歌（无需登录） */
  recommendNewSongs: function (args) {
    var resp = musicuCall('newsong.NewSongServer', 'get_new_song_info', { type: 5 });
    return parseSongsLoose(resp, ['new_song', 'song_list', 'list', 'data']);
  },

  /** 歌单详情（我喜欢 / 收藏歌单共用；songlist 元素可能包一层 JSON 字符串） */
  playlistDetail: function (args) {
    var disstid = args.disstid, page = args.page || 1, num = args.num || 100;
    var resp = musicuCall('music.srfDissInfo.DissInfo', 'CgiGetDiss', {
      disstid: disstid,
      dirid: 0,
      tag: true,
      song_begin: num * (page - 1),
      song_num: num,
      userinfo: true,
      orderlist: true,
      onlysonglist: false
    });
    var root = Ob(resp, 'req_1') || resp;
    var data = Ob(root, 'data') || root;
    var dirinfo = Ob(data, 'dirinfo') || Ob(data, 'dissinfo') || {};
    var playlist = {
      disstid: N(data, 'disstid') || N(dirinfo, 'disstid'),
      name: S(dirinfo, 'title') || S(dirinfo, 'dissname') || S(data, 'dissname'),
      picUrl: S(dirinfo, 'picurl') || S(dirinfo, 'logo') || S(data, 'logo'),
      songCount: N(data, 'songnum') || N(data, 'total'),
      creatorNick: S(dirinfo, 'creator') || S(Ob(dirinfo, 'creator'), 'nick')
    };
    var songs = [];
    var songlist = Ar(data, 'songlist');
    if (songlist) {
      for (var i = 0; i < songlist.length; i++) {
        var obj = songlist[i];
        var inner = obj ? obj['json'] : null;
        if (typeof inner === 'string') {
          try { inner = JSON.parse(inner); } catch (e) { inner = null; }
        }
        var s = parseSong(inner || obj);
        if (s) songs.push(s);
      }
    }
    return { playlist: playlist, songs: songs };
  },

  /** 排行榜列表（官方 v8 接口） */
  toplists: function (args) {
    var body = getText(
      'https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?format=json&outCharset=utf-8',
      { 'User-Agent': UA, 'Referer': REFERER }
    );
    if (!body) return [];
    var root = JSON.parse(body);
    var arr = Ar(Ob(root, 'data'), 'topList');
    var out = [];
    if (arr) {
      for (var i = 0; i < arr.length; i++) {
        var o = arr[i];
        var id = N(o, 'id');
        if (!id) continue;
        out.push({
          topId: id,
          title: S(o, 'topTitle') || S(o, 'name'),
          picUrl: S(o, 'picUrl') || S(o, 'picurl'),
          updateInfo: S(o, 'updateInfo')
        });
      }
    }
    return out;
  },

  /** 排行榜歌曲（musicu 网页版模块，与每日推荐同通道） */
  toplistSongs: function (args) {
    var resp = musicuCall('musicToplist.ToplistInfoServer', 'GetDetail', {
      topId: args.topId, offset: 0, num: 100, period: ''
    });
    return parseSongsLoose(resp, ['songInfoList', 'songList', 'list']);
  },

  /** 歌手歌曲列表（musicu 歌手单曲通道，order=1 按热度；分页） */
  artistSongs: function (args) {
    var page = args.page || 1, size = args.size || 30;
    var resp = musicuCall('music.musichallSinger.SingerSongList', 'GetSingerSongList', {
      singerMid: args.singerMid,
      order: 1,
      page: { index: page, size: size }
    });
    var root = Ob(resp, 'req_1') || resp;
    var data = Ob(root, 'data') || root;
    var ssl = Ob(data, 'singerSongList') || data;
    var songList = Ar(ssl, 'songList') || Ar(ssl, 'songlist');
    var songs = [];
    if (songList) {
      for (var i = 0; i < songList.length; i++) {
        var s = parseSongEntry(songList[i]);
        if (s) songs.push(s);
      }
    }
    if (!songs.length) {
      songs = parseSongsLoose(resp, [
        'req_1.data.singerSongList.songList', 'data.singerSongList.songList', 'singerSongList'
      ]);
    }
    if (!songs.length) {
      // 主通道失败（如 code 500003）：备用通道 music.singer.SingerSong / GetSingerSong
      // 响应假定 {code:0, data:{total:..., songList:[{songInfo:{...}}]}}，与歌单详情条目同构
      try {
        var alt = musicuCall('music.singer.SingerSong', 'GetSingerSong', {
          singerMid: args.singerMid,
          order: 1,
          begin: (page - 1) * size,
          num: size
        });
        var altRoot = Ob(alt, 'req_1') || alt;
        var altData = Ob(altRoot, 'data') || altRoot;
        var altList = Ar(altData, 'songList') || Ar(altData, 'songlist');
        if (altList) {
          for (var j = 0; j < altList.length; j++) {
            var s2 = parseSongEntry(altList[j]);
            if (s2) songs.push(s2);
          }
        }
        if (!songs.length) {
          songs = parseSongsLoose(alt, ['req_1.data.songList', 'data.songList', 'songList']);
        }
        if (songs.length) {
          var altTotal = N(altData, 'total');
          return {
            songs: songs,
            hasMore: altTotal > 0 ? (page * size < altTotal) : (songs.length >= size)
          };
        }
      } catch (eAlt) { /* 备用通道无效，按主通道空结果返回 */ }
    }
    var total = N(ssl, 'total');
    return { songs: songs, hasMore: total > 0 ? (page * size < total) : (songs.length >= size) };
  },

  /** 专辑详情 + 全部歌曲（musicu 专辑通道；不同响应嵌套名可能是 data.GetAlbumDetail / data 直接字段） */
  albumSongs: function (args) {
    var resp = musicuCall('music.susicalbum.SusAlbumDetail', 'GetAlbumDetail', {
      albumMid: args.albumMid
    });
    var root = Ob(resp, 'req_1') || resp;
    var data = Ob(root, 'data') || root;
    var detail = Ob(data, 'GetAlbumDetail') || data;
    var album = Ob(detail, 'album') || detail;
    var albumMid = S(album, 'mid') || S(detail, 'mid') || args.albumMid;
    var name = S(album, 'albumName') || S(album, 'name') ||
      S(detail, 'albumName') || S(detail, 'name');
    var cover = S(album, 'cover') || S(detail, 'cover') || '';
    if (cover.indexOf('http') !== 0) cover = coverUrl(albumMid, 500);
    var songList = Ar(detail, 'songList') || Ar(detail, 'songlist') || Ar(detail, 'list');
    var songs = [];
    if (songList) {
      for (var i = 0; i < songList.length; i++) {
        var s = parseSongEntry(songList[i]);
        if (s) songs.push(s);
      }
    }
    if (!songs.length) {
      songs = parseSongsLoose(resp, ['req_1.data.songList', 'data.songList', 'songList', 'list']);
    }
    return { name: name, coverUrl: cover, songs: songs };
  },

  /** 歌单广场：移动端音乐厅首页 feed，提取歌单卡（type=500）按栏目分组 */
  musicHallShelves: function (args) {
    var resp = musicuCall('music.musicHall.MusicHallHomePage', 'GetHomePage', {}, mobileComm(cred()));
    var shelves = Ar(Ob(Ob(resp, 'req_1'), 'data'), 'v_shelf');
    if (!shelves) return [];
    var out = [];
    var seen = {};
    for (var i = 0; i < shelves.length; i++) {
      var sh = shelves[i];
      var niches = Ar(sh, 'v_niche');
      if (!niches) continue;
      var title = '';
      var pls = [];
      for (var j = 0; j < niches.length; j++) {
        var niche = niches[j];
        if (!title) title = S(niche, 'title_content') || S(niche, 'title_template');
        var cards = Ar(niche, 'v_card');
        if (!cards) continue;
        for (var k = 0; k < cards.length; k++) {
          var cd = cards[k];
          if (N(cd, 'type') !== 500) continue;
          var id = parseInt(S(cd, 'id'), 10);
          if (!id || id <= 0 || seen[id]) continue;
          var name = (S(cd, 'title')).replace(/^\s+|\s+$/g, '');
          if (!name) continue;
          seen[id] = true;
          pls.push({
            disstid: id,
            name: name,
            picUrl: S(cd, 'cover'),
            songCount: N(cd, 'cnt'),
            creatorNick: ''
          });
        }
      }
      if (pls.length) out.push({ title: title || '精选推荐', playlists: pls });
    }
    return out;
  },

  /** 当前账号的创建歌单列表 */
  myPlaylists: function (args) {
    var c = cred();
    var resp = musicuCall('music.musicasset.PlaylistBaseRead', 'GetPlaylistByUin', {
      uin: c.strMusicid || ('' + c.musicid)
    });
    return parsePlaylistsLoose(resp);
  },

  /** 收藏歌单（uin 必须传 encryptUin，带 offset/size） */
  favPlaylists: function (args) {
    var c = cred();
    var euin = c.encryptUin || c.strMusicid || ('' + c.musicid);
    var resp = musicuCall('music.musicasset.PlaylistFavRead', 'CgiGetPlaylistFavInfo', {
      uin: euin, offset: 0, size: 100
    });
    return parsePlaylistsLoose(resp);
  },

  /** 当前登录用户资料（头像、昵称、encrypt_uin） */
  userProfile: function (args) {
    var c = cred();
    if (!c.musicid || !c.musickey) return null;
    var body = getText(
      'https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg' +
      '?g_tk=' + gtk(c.musickey) +
      '&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&cid=205360838' +
      '&needNewCode=0&loginUin=' + c.musicid + '&hostUin=0' +
      '&userid=' + c.musicid + '&reqfrom=1',
      { 'User-Agent': UA, 'Cookie': cookieHeader(c) || '' }
    );
    var root = null;
    try { root = body ? JSON.parse(body) : null; } catch (e) { root = null; }
    if (!root) return { musicid: c.musicid, nick: c.nick, avatarUrl: c.avatarUrl, encryptUin: '' };
    var creator = findObjectWithKeys(root, ['nick']) || findObjectWithKeys(root, ['nickname']);
    var nick = (creator ? (S(creator, 'nick') || S(creator, 'nickname') ||
      S(Ob(creator, 'friend'), 'nick')) : '') || c.nick;
    var avatar = (creator ? (S(creator, 'avatar') || S(creator, 'headpic') ||
      S(Ob(creator, 'friend'), 'avatar')) : '') || c.avatarUrl;
    var encryptUin = creator ? (S(creator, 'encrypt_uin') || S(creator, 'encryptUin') ||
      S(creator, 'encuin')) : '';
    return { musicid: c.musicid, nick: nick, avatarUrl: avatar, encryptUin: encryptUin };
  },

  /** 歌词（主通道 + 经典接口兜底，游客可用） */
  lyric: function (args) {
    var primary = '';
    try {
      var param = {
        // crypt=0：返回 base64 明文歌词（extractLyric 用 b64decode 解开）。
        // crypt=1 返回加密 hex 串，宿主无私钥会解析失败且非空串会跳过兜底。
        crypt: 0, lrc_t: 0, qrc: 0, qrc_t: 0, roma: 0, roma_t: 0,
        trans: 0, trans_t: 0, needSingingAnnotations: false, type: 1
      };
      if (args.songId > 0) param.songId = args.songId; else param.songMid = args.mid;
      var resp = musicuCall('music.musichallSong.PlayLyricInfo', 'GetPlayLyricInfo', param);
      primary = extractLyric(resp);
    } catch (e) { primary = ''; }
    if (primary) return primary;
    try {
      var text = getText(
        'https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg' +
        '?songmid=' + args.mid + '&g_tk=5381&format=json&nobase64=1&outCharset=utf-8',
        { 'User-Agent': UA, 'Referer': REFERER }
      );
      if (!text) return '';
      return extractLyric(JSON.parse(text));
    } catch (e2) { return ''; }
  },

  /** 歌词翻译（trans=1 请求译文 LRC；无译文返回空串。老版本 APK 不会调用此 handler，向后兼容） */
  lyricTrans: function (args) {
    try {
      var param = {
        crypt: 0, lrc_t: 0, qrc: 0, qrc_t: 0, roma: 0, roma_t: 0,
        trans: 1, trans_t: 1, needSingingAnnotations: false, type: 1
      };
      if (args.songId > 0) param.songId = args.songId; else param.songMid = args.mid;
      var resp = musicuCall('music.musichallSong.PlayLyricInfo', 'GetPlayLyricInfo', param);
      return extractLrcField(resp, 'trans');
    } catch (e) { return ''; }
  },

  /** 歌词罗马音（roma=1 请求音译 LRC；无罗马音返回空串。老版本 APK 不会调用此 handler，向后兼容） */
  lyricRoma: function (args) {
    try {
      var param = {
        crypt: 0, lrc_t: 0, qrc: 0, qrc_t: 0,
        roma: 1, roma_t: 1,
        trans: 0, trans_t: 0, needSingingAnnotations: false, type: 1
      };
      if (args.songId > 0) param.songId = args.songId; else param.songMid = args.mid;
      var resp = musicuCall('music.musichallSong.PlayLyricInfo', 'GetPlayLyricInfo', param);
      return extractLrcField(resp, 'roma');
    } catch (e) { return ''; }
  },

  /** 聚合搜索：歌曲 / 歌手 / 歌单（musicu Desktop 主通道 + client_search_cp 兜底） */
  searchAll: function (args) {
    function legacy(type) {
      return musicuCall('music.search.SearchCgiService', 'DoSearchForQQMusicDesktop', {
        search_type: type, query: args.query, page_num: 1, num_per_page: 15
      });
    }
    function cp(type) {
      var text = getText(
        'https://c.y.qq.com/soso/fcgi-bin/client_search_cp' +
        '?ct=24&qqmusic_ver=1298&new_json=1&remoteplace=txt.yqq.all&t=' + type +
        '&aggr=1&cr=1&catZhida=1&p=1&n=15&w=' + encodeURIComponent(args.query) +
        '&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8' +
        '&notice=0&platform=yqq.json&needNewCode=0',
        { 'User-Agent': UA }
      );
      return text ? JSON.parse(text) : {};
    }
    function songsOf(a, b) {
      try {
        var r = parseSongsLoose(a, ['req_1.data.body.song.list', 'body.song.list', 'body']);
        if (r.length) return r;
        return parseSongsLoose(b, ['data.song.list', 'data', 'body']);
      } catch (e) {
        try { return parseSongsLoose(b, ['data.song.list', 'data', 'body']); } catch (e2) { return []; }
      }
    }
    function singersOf(a, b) {
      try {
        var r = parseSingersLoose(a);
        if (r.length) return r;
        return parseSingersLoose(b);
      } catch (e) {
        try { return parseSingersLoose(b); } catch (e2) { return []; }
      }
    }
    function playlistsOf(a, b) {
      try {
        var r = parsePlaylistsLoose(a);
        if (r.length) return r;
        return parsePlaylistsLoose(b);
      } catch (e) {
        try { return parsePlaylistsLoose(b); } catch (e2) { return []; }
      }
    }
    var legacy0 = legacy(0), cp0 = cp(0);
    var legacy1 = legacy(1), cp1 = cp(1);
    var legacy3 = legacy(3), cp3 = cp(3);
    return {
      songs: songsOf(legacy0, cp0),
      singers: singersOf(legacy1, cp1),
      playlists: playlistsOf(legacy3, cp3)
    };
  },

  /**
   * 批量解析播放地址。
   * 1) 缺 media_mid 先批量补全详情；2) 按音质链构造文件名分批 vkey（每批 100）；
   * 3) 仍失败的走不传 filename 的自动下发批。返回 {items:[dto|null], debug}。
   */
  resolveUrls: function (args) {
    var songs = args.songs || [];
    var chain = QUALITY_CHAINS[args.quality] || QUALITY_CHAINS.STANDARD;
    var debug = '';
    if (!songs.length) return { items: [], debug: '' };

    // ---- 补全缺 media_mid 的歌曲 ----
    var missing = [];
    var i, j;
    for (i = 0; i < songs.length; i++) {
      if (!songs[i].mediaMid) missing.push(songs[i].mid);
    }
    var infoMap = {};
    if (missing.length) {
      try {
        var resp = musicuCall('music.musichallSong.SongInfoInter', 'GetSongInfo', {
          song_mids: missing.slice(0, 100)
        });
        var trackInfo = Ar(Ob(Ob(resp, 'req_1'), 'data'), 'track_info');
        if (trackInfo) {
          for (i = 0; i < trackInfo.length; i++) {
            var s = parseSong(trackInfo[i]);
            if (s) infoMap[s.mid] = s;
          }
        }
      } catch (e) { infoMap = {}; }
      for (i = 0; i < songs.length; i++) {
        if (!songs[i].mediaMid && infoMap[songs[i].mid]) {
          var f = infoMap[songs[i].mid];
          songs[i].mediaMid = f.mediaMid;
          if (!songs[i].songType) songs[i].songType = f.songType;
          if (!songs[i].songId) songs[i].songId = f.songId;
        }
      }
    }

    var items = [];
    for (i = 0; i < songs.length; i++) items.push(null);

    // ---- 批1：有 media_mid -> 音质链文件名 ----
    var entries = [];
    for (i = 0; i < songs.length; i++) {
      if (!songs[i].mediaMid) continue;
      for (j = 0; j < chain.length; j++) {
        entries.push({ idx: i, prefix: chain[j], filename: chain[j] + songs[i].mediaMid + '.' + extOf(chain[j]) });
      }
    }
    function vkeyReq(entryList, songmidList) {
      return musicuCall('vkey.GetVkeyServer', 'CgiGetVkey', {
        guid: qmu.guid(),
        filename: entryList,
        songmid: songmidList,
        // songtype 统一 0：非 0 会被服务器按特殊曲库处理导致拒发（PC 实测）
        songtype: songmidList.map(function () { return 0; }),
        // param.uin 匿名：登录态经 cookie 鉴权，与 cookie 不一致会 104009
        uin: '',
        loginflag: 1,
        platform: '20'
      });
    }
    function purlFull(purl) {
      if (purl.indexOf('http') === 0) return purl;
      return STREAM_HOST + purl;
    }
    for (var start = 0; start < entries.length; start += 100) {
      var chunk = entries.slice(start, start + 100);
      var mids = [];
      for (i = 0; i < chunk.length; i++) mids.push(songs[chunk[i].idx].mid);
      var resp1 = null;
      try { resp1 = vkeyReq(chunk.map(function (e2) { return e2.filename; }), mids); }
      catch (e3) { continue; }
      var data1 = Ob(Ob(resp1, 'req_1'), 'data');
      var midurlinfo = data1 ? Ar(data1, 'midurlinfo') : null;
      var fnMap = {};
      var okCount = 0;
      if (midurlinfo) {
        for (i = 0; i < midurlinfo.length; i++) {
          var info = midurlinfo[i];
          if (!info) continue;
          var fn = S(info, 'filename');
          var purl = S(info, 'purl');
          if (fn) fnMap[fn] = { purl: purl, ekey: S(info, 'ekey') };
          if (purl) okCount++;
        }
      }
      debug = 'B1 c=' + N(resp1, 'code') + '/' + N(data1, 'code') + ' ok=' + okCount + '/' + chunk.length;
      for (i = 0; i < chunk.length; i++) {
        var en = chunk[i];
        if (items[en.idx]) continue;
        var hit = fnMap[en.filename];
        if (!hit || !hit.purl) continue;
        items[en.idx] = {
          url: purlFull(hit.purl),
          ekey: hit.ekey,
          encrypted: en.filename.indexOf('.mflac') >= 0 || en.filename.indexOf('.mgg') >= 0,
          prefix: en.prefix,
          ext: extOf(en.prefix)
        };
      }
    }

    // ---- 批2：缺 media_mid -> 不传 filename 自动下发 ----
    var pending = [];
    for (i = 0; i < songs.length; i++) {
      if (!items[i] && songs[i].mid) pending.push(songs[i]);
    }
    for (var st2 = 0; st2 < pending.length; st2 += 100) {
      var group = pending.slice(st2, st2 + 100);
      var resp2 = null;
      try { resp2 = vkeyReq([], group.map(function (x) { return x.mid; })); }
      catch (e4) { continue; }
      var data2 = Ob(Ob(resp2, 'req_1'), 'data');
      var midurlinfo2 = data2 ? Ar(data2, 'midurlinfo') : null;
      var ok2 = 0;
      if (midurlinfo2) {
        for (i = 0; i < midurlinfo2.length; i++) {
          var inf2 = midurlinfo2[i];
          if (!inf2) continue;
          var smid = S(inf2, 'songmid');
          var purl2 = S(inf2, 'purl');
          if (!smid || !purl2) continue;
          ok2++;
          for (j = 0; j < songs.length; j++) {
            if (songs[j].mid === smid && !items[j]) {
              items[j] = { url: purlFull(purl2), ekey: '', encrypted: false, prefix: 'C400', ext: 'm4a' };
              break;
            }
          }
        }
      }
      debug += ' | B2 c=' + N(resp2, 'code') + '/' + N(data2, 'code') + ' ok=' + ok2 + '/' + group.length;
    }
    return { items: items, debug: debug };
  },

  /** 加入/移出「我喜欢」，返回服务端是否确认成功 */
  setLike: function (args) {
    var c = cred();
    if (!c.musicid || !c.musickey || !args.songId) return false;
    var url = 'https://c.y.qq.com/like/fcgi-bin/like' +
      '?g_tk=' + gtk(c.musickey) +
      '&uin=' + c.musicid +
      '&format=json&inCharset=utf-8&outCharset=utf-8&notice=0' +
      '&platform=yqq.json&needNewCode=0' +
      '&songid=' + args.songId + '&dirid=1' +
      (args.like ? '' : '&del=1');
    try {
      var text = getText(url, { 'User-Agent': UA, 'Referer': REFERER, 'Cookie': cookieHeader(c) || '' });
      if (!text) return false;
      return N(JSON.parse(text), 'code') === 0;
    } catch (e) { return false; }
  },

  // -------------------------------------------------------------------------
  // 扫码登录（QQ 互联 Web 链路完整移植）
  // -------------------------------------------------------------------------

  qrLogin: function (args) {
    var ptCookies = {};
    var siteCookies = {};

    /** 服务端用 "deleted" 值清除 Cookie */
    function storeCookies(map, resp) {
      var list = resp.setCookie || [];
      for (var i = 0; i < list.length; i++) {
        var first = (list[i].split(';')[0] || '').replace(/^\s+|\s+$/g, '');
        var idx = first.indexOf('=');
        if (idx > 0) {
          var name = first.substring(0, idx).replace(/^\s+|\s+$/g, '');
          var value = first.substring(idx + 1).replace(/^\s+|\s+$/g, '');
          if (name && value) {
            if (value.toLowerCase() === 'deleted') delete map[name];
            else map[name] = value;
          }
        }
      }
    }

    function step(url, referer, cookieMap) {
      var pairs = [];
      for (var k in cookieMap) {
        if (cookieMap.hasOwnProperty(k)) pairs.push(k + '=' + cookieMap[k]);
      }
      return http('GET', url, {
        'Referer': referer,
        'Cookie': pairs.join('; '),
        'User-Agent': UA
      }, null, null, false);
    }

    /** 相对地址 -> 绝对地址 */
    function resolveUrl(base, target) {
      target = target.replace(/\\\//g, '/').replace(/&amp;/g, '&').replace(/ /g, '%20');
      if (target.indexOf('http') === 0) return target;
      if (target.indexOf('/') === 0) {
        var m = base.match(/^(https?:\/\/[^\/]+)/);
        return m ? (m[1] + target) : target;
      }
      var cut = base.lastIndexOf('/');
      return cut > 0 ? base.substring(0, cut + 1) + target : target;
    }

    /** 从 HTML 提取 JS / meta refresh 跳转地址 */
    function extractHtmlRedirect(html) {
      if (!html) return null;
      var pats = [
        /location\.replace\(\s*['"]([^'"]+)['"]/,
        /(?:top\.|parent\.|window\.|document\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]/,
        /http-equiv=["']refresh["'][^>]*content=["'][^"']*?url=([^'"]+)["']/
      ];
      for (var i = 0; i < pats.length; i++) {
        var m = html.match(pats[i]);
        if (m && m[1] && (m[1].indexOf('http') === 0 || m[1].indexOf('/') === 0)) {
          return m[1].replace(/\\\//g, '/').replace(/&amp;/g, '&');
        }
      }
      return null;
    }

    /** 从 QQLogin 响应体提取 encryptUin（收藏歌单接口必需） */
    function extractEncryptUin(body) {
      if (!body) return null;
      var pats = [
        /"encrypt_uin"\s*:\s*"([A-Za-z0-9*+=_-]+)"/,
        /"encryptUin"\s*:\s*"([A-Za-z0-9*+=_-]+)"/,
        /"euin"\s*:\s*"([A-Za-z0-9*+=_-]+)"/,
        /"encrypted_user"\s*:\s*"([A-Za-z0-9*+=_-]+)"/
      ];
      for (var i = 0; i < pats.length; i++) {
        var m = body.match(pats[i]);
        if (m && m[1]) return m[1];
      }
      return null;
    }

    function emit(type, extra) {
      var ev = { type: type };
      if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) ev[k] = extra[k];
      qmu.emit(JSON.stringify(ev));
    }

    function fail(msg) { emit('Error', { message: msg }); return null; }

    try {
      // ---- 0. xlogin 预热：建立会话并取得 pt_login_sig ----
      var resp0 = step(XLOGIN_URL, XLOGIN_URL, ptCookies);
      if (resp0.status < 200 || resp0.status >= 300) {
        return fail('登录初始化失败 HTTP ' + resp0.status);
      }
      storeCookies(ptCookies, resp0);
      var loginSig = ptCookies['pt_login_sig'] || '';

      // ---- 1. 拉取二维码 ----
      var t = '0.' + Math.floor(Math.random() * 1e15);
      var qrUrl = 'https://xui.ptlogin2.qq.com/ssl/ptqrshow' +
        '?appid=' + APPID + '&e=2&l=M&s=3&d=72&v=4&t=' + t +
        '&daid=' + DAID + '&pt_3rd_aid=' + PT_3RD_AID +
        '&u1=' + encodeURIComponent(S_URL);
      var resp1 = step(qrUrl, XLOGIN_URL, ptCookies);
      if (resp1.status < 200 || resp1.status >= 300) {
        return fail('二维码获取失败 HTTP ' + resp1.status);
      }
      storeCookies(ptCookies, resp1);
      if (!resp1.bodyB64) return fail('二维码数据为空');
      emit('QrReady', { b64: resp1.bodyB64 });
      var qrsig = ptCookies['qrsig'] || '';

      // ---- 2. 轮询扫码状态（66 未扫 / 67,68 已扫 / 65 过期 / 0 成功） ----
      var token = ptqrtoken(qrsig);
      var pollUrl = 'https://xui.ptlogin2.qq.com/ssl/ptqrlogin' +
        '?u1=' + encodeURIComponent(S_URL) +
        '&ptqrtoken=' + token +
        '&ptredirect=0&h=1&t=1&g=1&from_ui=1' +
        '&ptlang=2052&action=0-0-' + Date.now() +
        '&js_ver=' + JS_VER + '&js_type=1&login_sig=' + encodeURIComponent(loginSig) +
        '&pt_uistyle=33&appid=' + APPID + '&daid=' + DAID +
        '&pt_3rd_aid=' + PT_3RD_AID;

      var checkUrl = '';
      var nick = '';
      var lastBody = '';
      var polls = 0;
      while (true) {
        if (++polls > 120) return fail('登录轮询超时');
        qmu.sleep(2000);
        var respP = step(pollUrl, XLOGIN_URL, ptCookies);
        if (respP.status < 200 || respP.status >= 300) {
          return fail('登录轮询失败 HTTP ' + respP.status);
        }
        storeCookies(ptCookies, respP);
        var body = respP.body;
        lastBody = body;
        // ptuiCB('0','0','https://...','0','二维码登录成功。','昵称'...)
        // 参数个数不固定：分立提取（整体正则容易失配）
        var codeM = body.match(/ptuiCB\(\s*'(\d+)/);
        var code = codeM ? codeM[1] : '';
        if (code === '0') {
          var urlM = body.match(/ptuiCB\(\s*'0'\s*,\s*'[^']*'\s*,\s*'([^']+)'/);
          checkUrl = urlM ? urlM[1].replace(/\\\//g, '/') : '';
          var nickM = body.match(/ptuiCB\(.*,\s*'([^']*)'\s*\)\s*;?\s*$/);
          nick = nickM ? nickM[1] : '';
          break;
        }
        if (code === '66') { emit('WaitingScan'); continue; }
        if (code === '67' || code === '68') { emit('ScannedConfirm'); continue; }
        if (code === '65') { emit('Expired'); return null; }
        if (code === '71' || code === '72' || code === '73') { emit('Refused'); return null; }
        return fail('登录状态异常 (code=' + code + ')');
      }

      // ---- 3. OAuth 跳转链收集登录 Cookie ----
      if (!checkUrl) {
        return fail('未取得授权跳转地址（' + lastBody.substring(0, 120) + '）');
      }
      var current = checkUrl;
      var referer = XLOGIN_URL;
      var lastUrl = current;
      var lastStatus = 0;
      var hops = 0;
      while (hops < 12 && current) {
        // login_jump：先真实请求一次种 graph 会话，再复刻浏览器 JS 的 authorize POST
        if (current.indexOf('/oauth2.0/login_jump') >= 0) {
          var merged = {};
          var k1;
          for (k1 in ptCookies) if (ptCookies.hasOwnProperty(k1)) merged[k1] = ptCookies[k1];
          for (k1 in siteCookies) if (siteCookies.hasOwnProperty(k1)) merged[k1] = siteCookies[k1];
          step(current, referer, merged);
          var merged2 = {};
          for (k1 in ptCookies) if (ptCookies.hasOwnProperty(k1)) merged2[k1] = ptCookies[k1];
          for (k1 in siteCookies) if (siteCookies.hasOwnProperty(k1)) merged2[k1] = siteCookies[k1];
          var ui = qmu.guid();
          siteCookies['ui'] = ui;
          var pSkey = merged2['p_skey'] || '';
          var pairs2 = [];
          for (k1 in merged2) if (merged2.hasOwnProperty(k1)) pairs2.push(k1 + '=' + merged2[k1]);
          pairs2.push('ui=' + ui);
          var form = [
            'response_type=code',
            'client_id=' + PT_3RD_AID,
            'redirect_uri=' + encodeURIComponent(REDIRECT_URI),
            'scope=',
            'state=state',
            'switch=',
            'from_ptlogin=1',
            'src=1',
            'update_auth=1',
            'openapi=80901010',
            'g_tk=' + gtk(pSkey),
            'auth_time=' + Math.floor(Date.now() / 1000),
            'ui=' + ui
          ].join('&');
          var respA = http('POST', AUTHORIZE_URL, {
            'User-Agent': UA,
            'Referer': S_URL,
            'Origin': 'https://graph.qq.com',
            'Cookie': pairs2.join('; ')
          }, form, 'application/x-www-form-urlencoded', false);
          storeCookies(siteCookies, respA);
          lastUrl = AUTHORIZE_URL;
          lastStatus = respA.status;
          var loc = respA.location || '';
          referer = AUTHORIZE_URL;
          if (respA.status >= 300 && respA.status < 400 && loc) {
            current = resolveUrl(AUTHORIZE_URL, loc);
          } else {
            var r = extractHtmlRedirect(respA.body);
            current = r ? resolveUrl(AUTHORIZE_URL, r) : '';
          }
          continue;
        }
        // wx_redirect.html：拿 code 后复刻前端 JS 的 musicu.fcg QQLogin 换登录态
        if (current.indexOf('wx_redirect.html') >= 0 && current.indexOf('code=') >= 0) {
          var codeM2 = current.match(/[?&]code=([^&]+)/);
          var oauthCode = codeM2 ? codeM2[1] : '';
          var mk1;
          var merged3 = {};
          for (mk1 in ptCookies) if (ptCookies.hasOwnProperty(mk1)) merged3[mk1] = ptCookies[mk1];
          for (mk1 in siteCookies) if (siteCookies.hasOwnProperty(mk1)) merged3[mk1] = siteCookies[mk1];
          var pSkey2 = merged3['p_skey'] || '';
          var pairs3 = [];
          for (mk1 in merged3) if (merged3.hasOwnProperty(mk1)) pairs3.push(mk1 + '=' + merged3[mk1]);
          var payload = '{"comm":{"g_tk":' + gtk(pSkey2) + ',"platform":"yqq","ct":24,"cv":0},' +
            '"req":{"module":"QQConnectLogin.LoginServer","method":"QQLogin",' +
            '"param":{"code":"' + oauthCode + '"}}}';
          var qqLoginBody = '';
          var ok = false;
          // 1) 按页面原样 POST（form-urlencoded + JSON body）
          try {
            var respQ = http('POST', FCG, {
              'User-Agent': UA,
              'Referer': current,
              'Origin': 'https://y.qq.com',
              'Cookie': pairs3.join('; ')
            }, payload, 'application/x-www-form-urlencoded', true);
            storeCookies(siteCookies, respQ);
            lastUrl = current;
            lastStatus = respQ.status;
            qqLoginBody = respQ.body;
            ok = respQ.status >= 200 && respQ.status < 300 && qqLoginBody.indexOf('"code":0') >= 0;
          } catch (e5) { }
          // 2) POST 通道失败时 GET data= 兜底（Set-Cookie 同样生效）
          if (!ok) {
            try {
              var respQ2 = http('GET', FCG + '?data=' + encodeURIComponent(payload), {
                'User-Agent': UA,
                'Referer': current,
                'Cookie': pairs3.join('; ')
              });
              storeCookies(siteCookies, respQ2);
              lastUrl = current;
              lastStatus = respQ2.status;
              qqLoginBody = respQ2.body;
            } catch (e6) { }
          }
          var euin = extractEncryptUin(qqLoginBody);
          if (euin) siteCookies['encryptUin'] = euin;
          break;
        }
        hops++;
        var merged4 = {};
        var mk2;
        for (mk2 in ptCookies) if (ptCookies.hasOwnProperty(mk2)) merged4[mk2] = ptCookies[mk2];
        for (mk2 in siteCookies) if (siteCookies.hasOwnProperty(mk2)) merged4[mk2] = siteCookies[mk2];
        var respC = step(current, referer, merged4);
        storeCookies(siteCookies, respC);
        lastUrl = current;
        lastStatus = respC.status;
        var loc2 = respC.location || '';
        referer = current;
        if (respC.status >= 300 && respC.status < 400 && loc2) {
          current = resolveUrl(current, loc2);
        } else {
          var r2 = extractHtmlRedirect(respC.body);
          current = r2 ? resolveUrl(current, r2) : '';
        }
      }

      // ---- 4. 汇总登录凭据 ----
      var uinRaw = siteCookies['uin'] || ptCookies['uin'] ||
        siteCookies['p_uin'] || ptCookies['p_uin'] || '';
      uinRaw = ('' + uinRaw).replace(/^o/, '');
      var musickey = siteCookies['qm_keyst'] || siteCookies['qqmusic_key'] || '';
      var euin2 = siteCookies['encryptUin'] || siteCookies['euin'] || '';
      if (!uinRaw || !musickey) {
        var names = [];
        for (var nk in siteCookies) if (siteCookies.hasOwnProperty(nk)) names.push(nk);
        return fail('登录 Cookie 不完整（uin' + (uinRaw ? '有' : '缺') +
          '/key' + (musickey ? '有' : '缺') +
          '，终点=' + lastUrl + ' HTTP' + lastStatus +
          '，收集[' + names.join(',') + ']）');
      }
      var credDto = {
        musicid: parseInt(uinRaw, 10) || 0,
        musickey: musickey,
        strMusicid: uinRaw,
        encryptUin: euin2,
        nick: nick || (siteCookies['nickname'] || ''),
        avatarUrl: ''
      };
      emit('Success', { credential: credDto });
      return credDto;
    } catch (e7) {
      return fail(e7 && e7.message ? e7.message : ('' + e7));
    }
  }
};

// ---------------------------------------------------------------------------
// 注册（宿主启动时执行到这里完成装载）
// ---------------------------------------------------------------------------

qmu.register({
  manifest: {
    id: 'qmusic-web',
    version: SOURCE_VERSION,
    minAppVersion: MIN_APP_VERSION,
    playbackHeaders: { 'User-Agent': UA, 'Referer': REFERER },
    imageHostSuffix: 'gtimg.cn',
    imageHeaders: { 'Referer': REFERER },
    qualityPrefixes: QUALITY_CHAINS
  },
  handlers: handlers
});
