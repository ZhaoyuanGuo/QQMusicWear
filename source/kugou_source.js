//qmu-sig:v1:IRU05AhUWddAsF1y2e5xQ6JE4j3SfbLZYOm8ez85Y8JT8Yn7o/RczxbQSRoA3fu+NNsKpi0XasM57CQ8O2tYBA==
/*
 * QQMusicWear 音乐源插件（酷狗音乐 · 移动端接口）
 * ---------------------------------------------------------------------------
 * 与 qmusic_source.js 同一宿主契约：qmu.register({ manifest, handlers })。
 * 一次只加载一个源；本文件只实现酷狗，不与其它源聚合。
 *
 * 关键约定：
 *   - Song.mid 加前缀 kg_<hash>，hash 即酷狗播放唯一键，避免主键碰撞
 *   - 播放地址走 trackercdn /i/v2（key=md5(hash+"kgcloudv2")），无需登录
 *   - 扫码登录：login-user.kugou.com /v2/qrcode 取 qrcode 键，
 *     二维码文本由宿主渲染；轮询 /v2/get_userinfo_qrcode（1 待扫 / 2 已扫 / 4 成功 / 0 过期）
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

function sub(o, k) {
  var v = o ? o[k] : null;
  return (v && typeof v === 'object') ? v : null;
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
var REFERER = 'https://www.kugou.com/';
// 注意：mobilecdn.kugou.com 已被 CNAME 到腾讯 CDN，其证书 SAN 不含该域名 → HTTPS 必然校验失败；
// mobileservice.kugou.com 为可用的 HTTPS 主机
var CDN = 'https://mobileservice.kugou.com/api/v3';
var LOGIN = 'https://login-user.kugou.com';
var CROSS = 'https://loginservice.kugou.com';

var APPID = '1005';
var PLAT = '4';

/** 设备标识：单次运行内稳定（酷狗接口要求 mid/dfid/uuid） */
var MID = qmu.guid();
var DFID = qmu.guid();

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
    var seg = list[i].split(';')[0];
    var eq = seg.indexOf('=');
    if (eq <= 0) continue;
    cookieJar[seg.substring(0, eq).trim()] = seg.substring(eq + 1).trim();
  }
}

function seedCookies() {
  var c = cred();
  // 酷狗会话 cookie（token/userid）由登录链写入；持久化的 musickey 为整串 cookie
  if (c && c.musickey && c.musickey.indexOf('=') > 0) {
    var parts = c.musickey.split(';');
    for (var i = 0; i < parts.length; i++) {
      var seg = parts[i].trim();
      var eq = seg.indexOf('=');
      if (eq > 0) cookieJar[seg.substring(0, eq)] = seg.substring(eq + 1);
    }
  }
  if (!cookieJar['kg_mid']) cookieJar['kg_mid'] = MID;
  if (!cookieJar['kg_dfid']) cookieJar['kg_dfid'] = DFID;
}

function headers(extra) {
  var h = {
    'User-Agent': UA,
    'Referer': REFERER,
    'Cookie': cookieHeader()
  };
  if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) h[k] = extra[k];
  return h;
}

/** 部分接口（with_res_tag=1）会把 JSON 包在 <!--KG_TAG_RES_START--> 注释里，需先剥离 */
function stripTags(body) {
  if (!body) return '';
  return ('' + body)
    .replace('<!--KG_TAG_RES_START-->', '')
    .replace('<!--KG_TAG_RES_END-->', '')
    .trim();
}

function apiGet(url, query) {
  seedCookies();
  var full = url + (query ? ('?' + query) : '');
  var resp = http('GET', full, headers(), null, null, true);
  storeCookies(resp);
  try { return JSON.parse(stripTags(resp.body)); } catch (e) { return {}; }
}

function cdn(path, query) {
  return apiGet(CDN + path, query);
}

function checkAuth(status) {
  if ((status === -1 || status === 20001) && cred().isLogged) {
    qmu.emit(JSON.stringify({ type: 'CredentialExpired' }));
  }
}

// ---------------------------------------------------------------------------
// 模型映射
// ---------------------------------------------------------------------------

function coverOf(imgurl) {
  if (!imgurl) return '';
  if (imgurl.indexOf('{size}') >= 0) return imgurl.replace('{size}', '240').replace(/^http:/, 'https:');
  return imgurl.replace(/^http:/, 'https:');
}

function hashOfSong(t) {
  // 优先无损/高品哈希，回退主 hash
  return S(t, 'hash') || S(t, '320hash') || S(t, 'sqhash') || S(t, '128hash');
}

function parseSong(t) {
  if (!t) return null;
  var hash = hashOfSong(t);
  if (!hash) return null;
  var img = S(t, 'imgurl') || S(t, 'imgUrl') || S(t, 'album_sizable_cover') ||
    S(t, 'album_img') || S(t, 'album_img_9') || S(t, 'banner_9') || S(t, 'img');
  var dur = N(t, 'duration') || N(t, 'timelen');
  if (dur > 10000) dur = Math.floor(dur / 1000);
  return {
    songId: N(t, 'audio_id') || N(t, 'album_audio_id'),
    mid: 'kg_' + hash,
    name: S(t, 'songname') || S(t, 'audio_name') || S(t, 'filename'),
    singers: S(t, 'singername') || S(t, 'author_name'),
    albumName: S(t, 'album_name'),
    albumMid: '' + (N(t, 'album_id') || ''),
    // mediaMid 承载 320/无损哈希（"320hash|sqhash"）：播放解析按音质挑对应文件
    mediaMid: (S(t, '320hash') || '') + '|' + (S(t, 'sqhash') || ''),
    intervalSec: dur > 0 ? dur : 0,
    songType: 0,
    vip: N(t, 'privilege') === 10,
    cover300: coverOf(img),
    cover500: coverOf(img).replace('/240/', '/480/')
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

function infoList(resp) {
  var data = Ob(resp, 'data') || resp;
  var v = Ar(data, 'info') || Ar(data, 'list') || Ar(resp, 'info');
  if (v) return v;
  // 部分接口（search/singer 等）data 直接就是数组
  return Ar(resp, 'data') || Ar(resp, 'info');
}

function hashOfArgs(args) {
  var mid = args && args.mid ? ('' + args.mid) : '';
  if (mid.indexOf('kg_') === 0) return mid.substring(3);
  return mid;
}

// ---------------------------------------------------------------------------
// 业务处理器
// ---------------------------------------------------------------------------

var handlers = {

  ping: function () { return 'ok'; },

  /** 首页推送大卡（酷狗：热歌推荐 / 排行榜 / 歌单广场） */
  homeFeed: function (args) {
    var cards = [];
    var hot = [];
    try { hot = rankSongs(8888, 30); } catch (e) { qmu.log('kg homeFeed err: ' + e); }
    if (!hot) hot = [];
    var rep = hot.length ? hot[0] : null;
    cards.push({
      id: 'daily', title: '热歌推荐',
      subtitle: hot.length ? ('酷狗热歌 · ' + hot.length + ' 首') : '正在加载…',
      action: 'daily',
      coverUrl: rep ? rep.cover300 : '', songName: rep ? rep.name : '', singers: rep ? rep.singers : '',
      songs: hot
    });
    cards.push({ id: 'rank', title: '排行榜', subtitle: '酷狗TOP500 · 飙升榜', action: 'rank' });
    cards.push({ id: 'square', title: '歌单广场', subtitle: '官方精选歌单', action: 'square' });
    return { cards: cards };
  },

  /** 推荐歌曲（酷狗 TOP500 前 30，无需登录） */
  recommendSongs: function (args) {
    return rankSongs(8888, 30);
  },

  /** 新歌推荐（酷狗新歌榜 rankid=31308） */
  recommendNewSongs: function (args) {
    var s = rankSongs(31308, 30);
    return s.length ? s : rankSongs(8888, 30);
  },

  /** 歌单详情（specialid） */
  playlistDetail: function (args) {
    var id = N(args, 'disstid');
    var page = N(args, 'page') || 1;
    var num = N(args, 'num') || 100;
    var resp = cdn('/special/song', 'version=9108&plat=0&pagesize=' + num + '&area_code=1&page=' + page +
      '&specialid=' + id + '&with_cover=1&apiver=6&with_res_tag=1');
    var songs = parseSongs(infoList(resp));
    var plResp = cdn('/special/info', 'specialid=' + id + '&version=9108&plat=0&apiver=6&with_res_tag=1');
    var pd = Ob(plResp, 'data') || plResp;
    var playlist = {
      disstid: id,
      name: S(pd, 'specialname') || S(pd, 'name'),
      picUrl: coverOf(S(pd, 'imgurl') || S(pd, 'img') || S(pd, 'album_sizable_cover')),
      songCount: N(pd, 'songcount') || songs.length,
      creatorNick: S(pd, 'nickname')
    };
    return { playlist: playlist, songs: songs };
  },

  /** 排行榜列表 */
  toplists: function (args) {
    var resp = cdn('/rank/list', 'version=9108&plat=0&showtype=2&parentid=0&apiver=6&area_code=1&tagid=0&withsong=1&sizelist=100');
    var list = infoList(resp) || [];
    var out = [];
    for (var i = 0; i < list.length; i++) {
      out.push({
        topId: N(list[i], 'rankid'),
        title: S(list[i], 'rankname'),
        picUrl: coverOf(S(list[i], 'imgurl') || S(list[i], 'banner_9') || S(list[i], 'album_img_9')),
        updateInfo: S(list[i], 'update_frequency') || S(list[i], 'update_rate')
      });
    }
    return out;
  },

  /** 排行榜歌曲 */
  toplistSongs: function (args) {
    return rankSongs(N(args, 'topId'), 100);
  },

  /** 歌手歌曲（singerid） */
  artistSongs: function (args) {
    var sid = parseInt('' + (args.singerMid || '0'), 10) || 0;
    var page = N(args, 'page') || 1;
    var size = N(args, 'size') || 30;
    var resp = cdn('/singer/song', 'singerid=' + sid + '&page=' + page + '&pagesize=' + size +
      '&version=9108&plat=0&area_code=1&with_cover=1&apiver=6&with_res_tag=1');
    var songs = parseSongs(infoList(resp));
    return { songs: songs, hasMore: songs.length >= size };
  },

  /** 专辑歌曲（albumid） */
  albumSongs: function (args) {
    var aid = parseInt('' + (args.albumMid || '0'), 10) || 0;
    var resp = cdn('/album/song', 'albumid=' + aid + '&page=1&pagesize=100&version=9108&plat=0&area_code=1&with_cover=1&apiver=6&with_res_tag=1');
    var songs = parseSongs(infoList(resp));
    var info = cdn('/album/info', 'albumid=' + aid + '&version=9108&plat=0&apiver=6&with_res_tag=1');
    var ad = Ob(info, 'data') || info;
    return {
      name: S(ad, 'albumname') || S(ad, 'album_name'),
      coverUrl: coverOf(S(ad, 'imgurl') || S(ad, 'album_sizable_cover') || S(ad, 'img')),
      songs: songs
    };
  },

  /**
   * 歌单广场栏目：酷狗匿名接口没有官方歌单列表（special/list 返回 Access Deny），
   * 改用「按分类关键词搜索歌单」构造若干栏目。
   */
  musicHallShelves: function (args) {
    var tags = ['流行', '摇滚', '民谣', '电子', '古风'];
    var shelves = [];
    for (var i = 0; i < tags.length; i++) {
      var tag = tags[i];
      var list = infoList(cdn('/search/special',
        'format=json&keyword=' + encodeURIComponent(tag) + '&page=1&pagesize=8')) || [];
      var pls = [];
      for (var j = 0; j < list.length; j++) {
        var p = list[j];
        if (!N(p, 'specialid')) continue;
        pls.push({
          disstid: N(p, 'specialid'),
          name: S(p, 'specialname'),
          picUrl: coverOf(S(p, 'imgurl') || S(p, 'imgUrl') || S(p, 'intro')),
          songCount: N(p, 'songcount'),
          creatorNick: S(p, 'nickname')
        });
      }
      if (pls.length) shelves.push({ title: tag + '歌单', playlists: pls });
    }
    return shelves;
  },

  /** 我的歌单（酷狗需完整登录 cookie，暂不支持） */
  myPlaylists: function (args) {
    return [];
  },

  /** 收藏歌单（同上） */
  favPlaylists: function (args) {
    return [];
  },

  /** 当前登录用户资料（登录成功时已带昵称/头像则直接返回） */
  userProfile: function (args) {
    var c = cred();
    if (!c || !c.isLogged) return null;
    return {
      musicid: c.musicid || 0,
      nick: c.nick || '',
      avatarUrl: c.avatarUrl || '',
      encryptUin: ''
    };
  },

  /** 歌词（krcs 搜索 -> download 取 LRC 文本） */
  lyric: function (args) {
    return fetchLyric(args);
  },

  /** 歌词翻译（酷狗无独立译文接口，返回空串） */
  lyricTrans: function (args) {
    return '';
  },

  /** 歌词罗马音（酷狗无，返回空串） */
  lyricRoma: function (args) {
    return '';
  },

  /** 聚合搜索：歌曲 / 歌手 / 歌单 */
  searchAll: function (args) {
    var q = args.query ? ('' + args.query) : '';
    var enc = encodeURIComponent(q);
    var songs = parseSongs(infoList(cdn('/search/song', 'format=json&keyword=' + enc + '&page=1&pagesize=30&showtype=1')));
    var singers = [];
    var sinfo = infoList(cdn('/search/singer', 'format=json&keyword=' + enc + '&page=1&pagesize=10'));
    if (sinfo) {
      for (var i = 0; i < sinfo.length; i++) {
        singers.push({
          mid: '' + N(sinfo[i], 'singerid'),
          id: N(sinfo[i], 'singerid'),
          name: S(sinfo[i], 'singername')
        });
      }
    }
    var playlists = [];
    var pinfo = infoList(cdn('/search/special', 'format=json&keyword=' + enc + '&page=1&pagesize=10'));
    if (pinfo) {
      for (var j = 0; j < pinfo.length; j++) {
        playlists.push({
          disstid: N(pinfo[j], 'specialid'),
          name: S(pinfo[j], 'specialname'),
          picUrl: coverOf(S(pinfo[j], 'imgurl') || S(pinfo[j], 'img')),
          songCount: N(pinfo[j], 'songcount'),
          creatorNick: S(pinfo[j], 'nickname')
        });
      }
    }
    return { songs: songs, singers: singers, playlists: playlists };
  },

  /** 批量解析播放地址（trackercdn /i/v2，key=md5(hash+"kgcloudv2")） */
  resolveUrls: function (args) {
    var quality = args.quality || 'STANDARD';
    var songs = Ar(args, 'songs') || [];
    var chain = brChain(quality);
    var items = [];
    var debug = '';
    for (var i = 0; i < songs.length; i++) {
      var hash = hashOfArgs(songs[i]);
      var mm = S(songs[i], 'mediaMid');
      var parts = mm.split('|');
      var item = resolveOne(hash, parts[0] || '', parts[1] || '', chain,
        i === 0 ? function (d) { debug = d; } : null);
      items.push(item);
    }
    return { items: items, debug: debug };
  },

  /** 加入/移出「我喜欢」（酷狗需完整登录，暂不支持） */
  setLike: function (args) {
    return false;
  },

  /**
   * 扫码登录：
   *   1) GET /v2/qrcode 取 data.qrcode（+ 可选 qrcode_img）
   *   2) emit QrReady{text=<h5 url?qrcode=key>}（宿主渲染）；若有 img 则直接给 b64
   *   3) 轮询 /v2/get_userinfo_qrcode（1 待扫 / 2 已扫 / 4 成功 / 0 过期）
   *   4) 成功后用 token 换登录 cookie（login_by_token_get，best-effort）
   */
  qrLogin: function (args) {
    try {
      cookieJar = {};
      var q = apiGet(LOGIN + '/v2/qrcode',
        'appid=' + APPID + '&clientver=1000&clienttime=' + Date.now() +
        '&mid=' + MID + '&uuid=' + MID + '&dfid=' + DFID + '&plat=' + PLAT + '&type=1');
      var data = Ob(q, 'data');
      if (N(q, 'status') !== 1 || !data || !S(data, 'qrcode')) {
        return fail('获取二维码失败（status=' + N(q, 'status') + '）');
      }
      var key = S(data, 'qrcode');
      if (S(data, 'qrcode_img')) {
        var imgResp = http('GET', S(data, 'qrcode_img'), { 'User-Agent': UA }, null, null, true);
        if (imgResp.status >= 200 && imgResp.status < 300 && imgResp.bodyB64) {
          emit('QrReady', { b64: imgResp.bodyB64 });
        } else {
          emit('QrReady', { text: qrText(key) });
        }
      } else {
        emit('QrReady', { text: qrText(key) });
      }

      var polls = 0;
      var token = '';
      var userid = 0;
      while (true) {
        if (++polls > 120) return fail('登录轮询超时');
        qmu.sleep(2000);
        var r = apiGet(LOGIN + '/v2/get_userinfo_qrcode',
          'appid=' + APPID + '&clientver=1000&clienttime=' + Date.now() +
          '&mid=' + MID + '&uuid=' + MID + '&dfid=' + DFID + '&plat=' + PLAT + '&qrcode=' + key);
        if (N(r, 'status') !== 1) { emit('WaitingScan'); continue; }
        var d = Ob(r, 'data') || {};
        var st = N(d, 'status');
        if (st === 0) { emit('Expired'); return null; }
        if (st === 2) { emit('ScannedConfirm'); continue; }
        if (st === 4 || st === 3) {
          token = S(d, 'token');
          userid = N(d, 'userid');
          break;
        }
        emit('WaitingScan');
      }

      if (!token) return fail('登录成功但未取得 token');
      exchangeToken(token, userid);
      var cookieStr = cookieHeader();
      if (!cookieStr) return fail('登录成功但未取得会话 cookie');
      var cred = {
        musicid: userid,
        musickey: cookieStr,
        strMusicid: userid ? ('' + userid) : '',
        encryptUin: '',
        nick: S(data, 'nickname') || '',
        avatarUrl: ''
      };
      emit('Success', { credential: cred });
      return cred;
    } catch (e) {
      return fail(e && e.message ? e.message : ('' + e));
    }
  }
};

// ---------------------------------------------------------------------------
// 辅助实现
// ---------------------------------------------------------------------------

var BR_CHAIN = {
  STANDARD: ['128'],
  HIGH: ['hq', '128'],
  LOSSLESS: ['sq', 'flac', 'hq'],
  HI_RES: ['flac', 'sq', 'hq']
};

function brChain(quality) {
  return BR_CHAIN[quality] || BR_CHAIN.STANDARD;
}

function rankSongs(rankId, size) {
  if (!rankId) return [];
  var resp = cdn('/rank/song', 'version=9108&plat=0&pagesize=' + size + '&area_code=1&page=1&rankid=' + rankId +
    '&with_cover=1&apiver=6&with_res_tag=1');
  return parseSongs(infoList(resp));
}

/**
 * 单曲按音质链解析；返回 {url, ekey, encrypted, prefix, ext} 或 null。
 * trackercdn /i/v2 需要「与音质匹配的哈希」：128→hash、hq→320hash、sq/flac→sqhash。
 * 全部失败时回退 m.kugou.com getSongInfo（免费曲直链，128k）。
 */
function resolveOne(hash, h320, hsq, chain, onDebug) {
  if (!hash) { if (onDebug) onDebug('no hash'); return null; }
  for (var i = 0; i < chain.length; i++) {
    var br = chain[i];
    var h = br === '128' ? hash : (br === 'hq' ? (h320 || hash) : (hsq || h320 || hash));
    var key = qmu.md5(h + 'kgcloudv2');
    var resp = apiGet('https://trackercdn.kugou.com/i/v2/',
      'key=' + key + '&hash=' + h + '&br=' + br + '&appid=' + APPID + '&pid=2&cmd=25&behavior=play');
    var urls = Ar(resp, 'url');
    if (N(resp, 'status') === 1 && urls && urls.length && urls[0]) {
      var ext = S(resp, 'extName') || (br === 'flac' || br === 'sq' ? 'flac' : 'mp3');
      return { url: urls[0], ekey: '', encrypted: false, prefix: br, ext: ext };
    }
    if (onDebug) onDebug('br=' + br + ' status=' + N(resp, 'status'));
  }
  // 兜底：getSongInfo（付费曲同样会被拒绝，此时返回 null 由宿主降级/跳过）
  var gi = apiGet('https://m.kugou.com/app/i/getSongInfo.php', 'cmd=playInfo&hash=' + hash);
  if (S(gi, 'url')) {
    return { url: S(gi, 'url'), ekey: '', encrypted: false, prefix: '128', ext: S(gi, 'extName') || 'mp3' };
  }
  if (onDebug) onDebug('getSongInfo url 空');
  return null;
}

/** 歌词：krcs 搜索候选 -> download 取 LRC（base64 解码） */
function fetchLyric(args) {
  var hash = hashOfArgs(args);
  var name = args && args.name ? ('' + args.name) : '';
  var duration = N(args, 'intervalSec');
  if (!hash) return '';
  var search = apiGet('https://krcs.kugou.com/search',
    'ver=1&man=yes&client=mobi&keyword=' + encodeURIComponent(name) +
    '&hash=' + hash + '&duration=' + (duration > 0 ? duration : ''));
  var cands = Ar(search, 'candidates');
  if (!cands || !cands.length) return '';
  var cand = cands[0];
  var dl = apiGet('https://lyrics.kugou.com/download',
    'ver=1&client=pc&id=' + S(cand, 'id') + '&accesskey=' + S(cand, 'accesskey') + '&fmt=lrc&charset=utf8');
  var content = S(dl, 'content');
  if (!content) return '';
  try { return qmu.b64decode(content); } catch (e) { return ''; }
}

function qrText(key) {
  return 'https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=' + APPID + '&qrcode=' + key;
}

/** 用 token 换取登录 cookie（best-effort：酷狗网页端另有 AES/RSA 包体，这里先试明文） */
function exchangeToken(token, userid) {
  try {
    var q = 'appid=' + APPID + '&clientver=1000&clienttime=' + Math.floor(Date.now() / 1000) +
      '&mid=' + MID + '&uuid=' + MID + '&dfid=' + DFID + '&dev=web&userid=' + userid +
      '&plat=' + PLAT + '&clienttime_ms=' + Date.now();
    var resp = http('POST', CROSS + '/v1/login_by_token_get?' + q, headers({ 'Content-Type': 'application/x-www-form-urlencoded' }), '', 'application/x-www-form-urlencoded', true);
    storeCookies(resp);
    try {
      var body = JSON.parse(resp.body);
      if (N(body, 'status') === 0) {
        var d = Ob(body, 'data') || {};
        if (S(d, 'token')) cookieJar['token'] = S(d, 'token');
        if (S(d, 'userid')) cookieJar['userid'] = S(d, 'userid');
      }
    } catch (e) { }
  } catch (e2) { qmu.log('kg exchangeToken err: ' + e2); }
  // 兜底：token 直接作为会话密钥，保证登录态成立（VIP/高音质仍可能受限）
  cookieJar['token'] = cookieJar['token'] || token;
  cookieJar['userid'] = cookieJar['userid'] || (userid ? ('' + userid) : '');
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
    id: 'kugou-web',
    name: '酷狗音乐',
    themeColor: '#2BA3F0',
    version: SOURCE_VERSION,
    minAppVersion: MIN_APP_VERSION,
    playbackHeaders: { 'User-Agent': UA, 'Referer': REFERER },
    imageHostSuffix: 'kugou.com',
    imageHeaders: { 'Referer': REFERER },
    qualityPrefixes: {
      STANDARD: ['128'],
      HIGH: ['hq'],
      LOSSLESS: ['sq', 'flac'],
      HI_RES: ['flac']
    }
  },
  handlers: handlers
});