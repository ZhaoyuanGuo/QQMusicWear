#!/usr/bin/env node
/**
 * 扫码登录链回环测试（CI 用，不触网、不真扫码）。
 *
 * 用脚本化 mock 服务器驱动源插件的 qrLogin / wxLogin 状态机，校验：
 *   - 二维码事件（QrReady + PNG 透传）与扫码状态迁移（等待/已扫/过期/拒绝）
 *   - 授权跳转链的 Cookie 收集与 musicu.fcg 换票请求体（协议漂移即失败）
 *   - 凭据 DTO 结构（QQ 数值 uin / 微信 openid 形态、authCookie 透传）
 *   - 宿主取消桥接 qmu.cancelled() 能让轮询立即退出（不占住单线程引擎）
 *   - authCookies()/commUin() 对微信账号的兼容（非数值 uin、鉴权 Cookie 合并）
 *
 * 用法：node login_flow.js <源脚本>
 * 说明：mock 路由按 QQ 音乐 Web 登录链编写，其他源（酷狗/网易云等）直接跳过。
 */
'use strict';
const fs = require('fs');
const crypto = require('crypto');

const scriptPath = process.argv[2];
if (!scriptPath) {
  console.error('用法: node login_flow.js <源脚本>');
  process.exit(2);
}
const src = fs.readFileSync(scriptPath, 'utf8');

// 非 QQ 音乐源不套本链路（各源登录协议不同，由 mock_smoke.js 覆盖）
if (src.indexOf("id: 'qmusic-web'") < 0) {
  console.log('SKIP login flow（非 QQ 音乐源）: ' + scriptPath);
  process.exit(0);
}

/** 假二维码 PNG（只验证字节透传，头 8 字节是真实 PNG 签名） */
const QR_BYTES = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex');
const QR_B64 = QR_BYTES.toString('base64');

/**
 * 取脚本函数作用域里的内部函数做白盒断言：
 * 源脚本整体是 new Function('qmu', body) 的 body，追加 return 即可拿到同作用域符号。
 * 用 typeof 兜底，避免其它源缺符号时报错。
 */
const PROBE = '\n;return {' +
  ' authCookies: typeof authCookies === "function" ? authCookies : null,' +
  ' commUin: typeof commUin === "function" ? commUin : null' +
  '};';

let failed = false;
const check = (name, ok, got) => {
  if (ok) { console.log('  ok  ' + name); return; }
  failed = true;
  console.error('  FAIL ' + name + (got === undefined ? '' : ' -> ' + JSON.stringify(got)));
};

/**
 * 装载一次源脚本并注入 mock 桥接。
 * @param routes (method, url, body, seq) => {status, location, setCookie, body, bodyB64} | undefined
 * @param opt    { cancelled?: () => boolean, credential?: object }
 */
function boot(routes, opt) {
  opt = opt || {};
  let registered = null;
  const events = [];
  const hits = [];
  const misses = [];
  const qmu = {
    http: (method, url, headersJson, body) => {
      hits.push({ method: method, url: url, headers: headersJson, body: body });
      const r = routes(method, url, body, hits.length) || {};
      if (r.preset === undefined) misses.push(method + ' ' + url);
      return JSON.stringify({
        status: r.status === undefined ? 200 : r.status,
        location: r.location || '',
        setCookie: r.setCookie || [],
        body: r.body || '',
        bodyB64: r.bodyB64 || '',
      });
    },
    credential: () => JSON.stringify(Object.assign({
      musicid: 0, musickey: '', strMusicid: '', encryptUin: '', nick: '', avatarUrl: '', authCookie: '',
    }, opt.credential || {})),
    md5: (s) => crypto.createHash('md5').update(String(s), 'utf8').digest('hex'),
    aesCbcHex: () => '0'.repeat(64),
    aesCbcDecryptB64: () => '',
    inflateB64: () => '',
    rsaNoPadHex: () => '0'.repeat(256),
    b64decode: (s) => Buffer.from(String(s), 'base64').toString('latin1'),
    sleep: () => {},
    log: () => {},
    guid: () => '0123456789abcdef0123456789abcdef',
    emit: (json) => events.push(JSON.parse(json)),
    cancelled: () => (opt.cancelled ? opt.cancelled() : false),
    register: (obj) => { registered = obj; },
  };
  const internals = new Function('qmu', src + PROBE)(qmu);
  const types = () => events.map((e) => e.type);
  return { handlers: registered.handlers, events, types, hits, misses, internals };
}

const ck = (pairs) => pairs.map((p) => p + '; Domain=.qq.com; Path=/');

// ---------------------------------------------------------------------------
// 1) QQ 扫码：预热 -> 二维码 -> 66/67 -> 0 -> OAuth 跳转链 -> QQLogin 换票
// ---------------------------------------------------------------------------
console.log('== qrLogin 成功链 ==');
{
  let polls = 0;
  const s = boot((method, url, body) => {
    if (url.indexOf('/cgi-bin/xlogin') >= 0) {
      return { preset: 1, setCookie: ck(['pt_login_sig=SIG1']) };
    }
    if (url.indexOf('/ssl/ptqrshow') >= 0) {
      return { preset: 1, bodyB64: QR_B64, setCookie: ck(['qrsig=QRSIG1']) };
    }
    if (url.indexOf('/ssl/ptqrlogin') >= 0) {
      polls++;
      const map = [
        "ptuiCB('66','0','','0','未登录。','');",
        "ptuiCB('67','0','','0','扫描成功，请在手机上确认。','');",
        "ptuiCB('0','0','https://graph.qq.com/oauth2.0/login_jump?client_id=100497308&response_type=code&state=state','0','登录成功！','小明','ENCRYPT_UIN_9','1');",
      ];
      return { preset: 1, body: map[Math.min(polls, 3) - 1] };
    }
    if (url.indexOf('/oauth2.0/login_jump') >= 0) return { preset: 1, body: 'ok' };
    if (url.indexOf('/oauth2.0/authorize') >= 0) {
      return {
        preset: 1, status: 302,
        location: 'https://y.qq.com/portal/wx_redirect.html?login_type=1&use_customer_cb=0&code=OAUTHCODE1',
        setCookie: ck(['p_skey=PSKEY1']),
      };
    }
    if (url.indexOf('/cgi-bin/musicu.fcg') >= 0) {
      return {
        preset: 1,
        body: JSON.stringify({ code: 0, req: { code: 0, data: { encrypt_uin: 'ENCUIN1', uin: '123456', strNick: '小明' } } }),
        setCookie: ck(['uin=o123456', 'qm_keyst=QMKEY1', 'qqmusic_key=QQMKEY1', 'skey=SKEY1']),
      };
    }
    return undefined;
  });
  const cred = s.handlers.qrLogin({});
  check('路由全覆盖', s.misses.length === 0, s.misses);
  check('事件顺序 QrReady→WaitingScan→ScannedConfirm→Success',
    s.types().join(',') === 'QrReady,WaitingScan,ScannedConfirm,Success', s.types());
  check('二维码字节透传',
    Buffer.from(s.events[0].b64, 'base64').equals(QR_BYTES), s.events[0].b64);
  check('返回凭据', !!cred && cred.musickey === 'QMKEY1', cred);
  check('QQ 号解析为数值 musicid', cred && cred.musicid === 123456 && cred.strMusicid === '123456', cred);
  check('encryptUin 取自换票响应', cred && cred.encryptUin === 'ENCUIN1', cred && cred.encryptUin);
  check('换票请求带 OAuth code',
    typeof s.hits.find((h) => h.url.indexOf('musicu.fcg') >= 0 && String(h.body).indexOf('OAUTHCODE1') >= 0) !== 'undefined');
  check('authCookie 不覆盖合成值（只收微信专有 Cookie）',
    cred && String(cred.authCookie).indexOf('uin=') < 0 && String(cred.authCookie).indexOf('qm_keyst') < 0,
    cred && cred.authCookie);

  // 换票请求头应带上 ptlogin/OAuth 链收集的 Cookie
  const fcgHit = s.hits.find((h) => h.url.indexOf('musicu.fcg') >= 0);
  check('换票带上前序 Cookie', /QMKEY1|PSKEY1/.test(fcgHit.headers || ''), fcgHit.headers);
}

// ---------------------------------------------------------------------------
// 2) QQ 扫码：二维码过期
// ---------------------------------------------------------------------------
console.log('== qrLogin 过期链 ==');
{
  const s = boot((method, url) => {
    if (url.indexOf('/cgi-bin/xlogin') >= 0) return { preset: 1, setCookie: ck(['pt_login_sig=SIG1']) };
    if (url.indexOf('/ssl/ptqrshow') >= 0) return { preset: 1, bodyB64: QR_B64, setCookie: ck(['qrsig=QRSIG1']) };
    if (url.indexOf('/ssl/ptqrlogin') >= 0) {
      return { preset: 1, body: "ptuiCB('65','0','','0','二维码已过期，请刷新重试。','');" };
    }
    return undefined;
  });
  const cred = s.handlers.qrLogin({});
  check('过期只发 Expired 事件', s.types().join(',') === 'QrReady,Expired', s.types());
  check('过期返回 null（宿主保持登录页可重试）', cred === null, cred);
}

// ---------------------------------------------------------------------------
// 3) QQ 扫码：宿主取消（离开登录页 / 切换扫码方式）
// ---------------------------------------------------------------------------
console.log('== qrLogin 取消桥接 ==');
{
  let polls = 0;
  const s = boot((method, url) => {
    if (url.indexOf('/cgi-bin/xlogin') >= 0) return { preset: 1, setCookie: ck(['pt_login_sig=SIG1']) };
    if (url.indexOf('/ssl/ptqrshow') >= 0) return { preset: 1, bodyB64: QR_B64, setCookie: ck(['qrsig=QRSIG1']) };
    if (url.indexOf('/ssl/ptqrlogin') >= 0) {
      polls++;
      // 不因取消而退出则会把 120 轮跑满（轮询次数即断言点）
      return { preset: 1, body: "ptuiCB('66','0','','0','未登录。','');" };
    }
    return undefined;
  }, { cancelled: () => polls >= 1 });
  const cred = s.handlers.qrLogin({});
  check('取消后轮询立即退出', polls === 1, polls);
  check('取消返回 null 且不发 Error', cred === null && s.types().indexOf('Error') < 0, s.types());
}

// ---------------------------------------------------------------------------
// 4) 微信扫码：qrconnect -> 二维码 -> 长轮询 404/405 -> music.login.LoginServer 换票
// ---------------------------------------------------------------------------
console.log('== wxLogin 成功链 ==');
{
  let polls = 0;
  const s = boot((method, url, body) => {
    if (url.indexOf('/connect/qrconnect?') === 0 || url.indexOf('/connect/qrconnect') >= 0 && url.indexOf('/qrcode/') < 0 && url.indexOf('/l/qrconnect') < 0) {
      return {
        preset: 1,
        body: '<div class="web_login"><img src="https://open.weixin.qq.com/connect/qrcode/WXUUID123456" /></div>',
      };
    }
    if (url.indexOf('/connect/qrcode/') >= 0) return { preset: 1, bodyB64: QR_B64 };
    if (url.indexOf('/l/qrconnect') >= 0) {
      polls++;
      return polls === 1
        ? { preset: 1, body: 'try{parent.wx_errcode=404}catch(e){};' }
        : { preset: 1, body: 'wx_errcode=405;wx_code=WXCODE9;' };
    }
    if (url.indexOf('/cgi-bin/musicu.fcg') >= 0) {
      return {
        preset: 1,
        body: JSON.stringify({ code: 0, req: { code: 0, data: { openid: 'oxABC123', nickname: '微信用户', figureurl: 'https://qq.qlogo.cn/w.jpg' } } }),
        setCookie: ck(['wx_key=WXKEY1', 'login_type=3', 'uin=o987654', 'qm_keyst=QMKEYWX']),
      };
    }
    return undefined;
  });
  const cred = s.handlers.wxLogin({});
  check('路由全覆盖', s.misses.length === 0, s.misses);
  check('事件顺序 QrReady→ScannedConfirm→Success（405 当轮直接结束，不再补 WaitingScan）',
    s.types().join(',') === 'QrReady,ScannedConfirm,Success', s.types());
  check('二维码来自 /connect/qrcode/<uuid>',
    s.hits.some((h) => h.url === 'https://open.weixin.qq.com/connect/qrcode/WXUUID123456'), s.hits.map((h) => h.url));
  check('长轮询带 uuid', s.hits.some((h) => h.url.indexOf('/l/qrconnect?uuid=WXUUID123456') >= 0), s.hits.map((h) => h.url));
  check('凭据 key 取 qm_keyst', !!cred && cred.musickey === 'QMKEYWX', cred);
  check('凭据 uin 剥掉 QQ 的 o 前缀', cred && cred.musicid === 987654 && cred.strMusicid === '987654', cred);
  check('昵称/头像自响应提取', cred && cred.nick === '微信用户' && cred.avatarUrl.indexOf('qlogo.cn') > 0, cred);
  check('authCookie 透传微信专有 Cookie',
    cred && cred.authCookie.indexOf('wx_key=WXKEY1') >= 0 && cred.authCookie.indexOf('login_type=3') >= 0,
    cred && cred.authCookie);
  const exchange = s.hits.filter((h) => h.url.indexOf('musicu.fcg') >= 0)[0];
  check('换票走 music.login.LoginServer/Login',
    exchange && exchange.body.indexOf('music.login.LoginServer') >= 0 && exchange.body.indexOf('"method":"Login"') >= 0,
    exchange && exchange.body);
  check('换票带 strAppid 与 wx_code',
    exchange && exchange.body.indexOf('wx48db31d50e334801') >= 0 && exchange.body.indexOf('WXCODE9') >= 0,
    exchange && exchange.body);
  check('换票带 tmeLoginType=1',
    exchange && exchange.body.indexOf('"tmeLoginType":"1"') >= 0, exchange && exchange.body);
}

// ---------------------------------------------------------------------------
// 5) 微信扫码：取消 / 拒绝 / 过期 / 纯 openid 账号
// ---------------------------------------------------------------------------
console.log('== wxLogin 状态分支 ==');
{
  const wxPage = (url) => url.indexOf('/connect/qrconnect') >= 0 && url.indexOf('/qrcode/') < 0 && url.indexOf('/l/qrconnect') < 0;
  const pageRoute = { preset: 1, body: '<img src="https://open.weixin.qq.com/connect/qrcode/UUIDX" />' };

  // 宿主取消：轮询首行自查退出
  let polls = 0;
  const c = boot((m, url) => {
    if (wxPage(url)) return pageRoute;
    if (url.indexOf('/connect/qrcode/') >= 0) return { preset: 1, bodyB64: QR_B64 };
    if (url.indexOf('/l/qrconnect') >= 0) { polls++; return { preset: 1, body: 'wx_errcode=404;' }; }
    return undefined;
  }, { cancelled: () => polls >= 1 });
  check('取消后不排队占住引擎', c.handlers.wxLogin({}) === null && polls === 1, polls);

  // 用户取消授权（403）
  polls = 0;
  const r = boot((m, url) => {
    if (wxPage(url)) return pageRoute;
    if (url.indexOf('/connect/qrcode/') >= 0) return { preset: 1, bodyB64: QR_B64 };
    if (url.indexOf('/l/qrconnect') >= 0) return { preset: 1, body: 'wx_errcode=403;' };
    return undefined;
  });
  check('403 映射为 Refused', r.handlers.wxLogin({}) === null && r.types().join(',') === 'QrReady,Refused', r.types());

  // 二维码过期（402）
  const e = boot((m, url) => {
    if (wxPage(url)) return pageRoute;
    if (url.indexOf('/connect/qrcode/') >= 0) return { preset: 1, bodyB64: QR_B64 };
    if (url.indexOf('/l/qrconnect') >= 0) return { preset: 1, body: 'wx_errcode=402;' };
    return undefined;
  });
  check('402 映射为 Expired', e.handlers.wxLogin({}) === null && e.types().join(',') === 'QrReady,Expired', e.types());

  // 桥接超时截断长轮询（非 2xx）应继续下一轮而不是报错退出
  let n = 0;
  const t = boot((m, url) => {
    if (wxPage(url)) return pageRoute;
    if (url.indexOf('/connect/qrcode/') >= 0) return { preset: 1, bodyB64: QR_B64 };
    if (url.indexOf('/l/qrconnect') >= 0) {
      n++;
      return n === 1
        ? { status: 500, body: '' }             // 宿主 callTimeout 截断
        : { preset: 1, body: 'wx_errcode=405;wx_code=C2;qqmusic_key=K9' };
    }
    if (url.indexOf('/cgi-bin/musicu.fcg') >= 0) {
      return { preset: 1, body: JSON.stringify({ code: 0, req: { code: 0, data: { openid: 'oxZ9', qqmusic_key: 'DK9' } } }) };
    }
    return undefined;
  });
  const tc = t.handlers.wxLogin({});
  check('长轮询被截断时继续重试', n === 2 && !!tc, { n, tc });
  check('无 uin Cookie 时退化用响应 openid', tc && tc.strMusicid === 'oxZ9' && tc.musicid === 0, tc);
  check('openid 形态不剥 o 前缀', tc && tc.strMusicid.charAt(0) === 'o' && tc.strMusicid.charAt(1) === 'x', tc.strMusicid);

  // 换票既无 Cookie 也无字段：必须给出可诊断的失败信息（便于按真机响应补映射）
  const d = boot((m, url) => {
    if (wxPage(url)) return pageRoute;
    if (url.indexOf('/connect/qrcode/') >= 0) return { preset: 1, bodyB64: QR_B64 };
    if (url.indexOf('/l/qrconnect') >= 0) return { preset: 1, body: 'wx_errcode=405;wx_code=C3;' };
    if (url.indexOf('/cgi-bin/musicu.fcg') >= 0) return { preset: 1, body: '{"code":0,"req":{"code":-9,"data":{}}}' };
    return undefined;
  });
  check('换票失败上抛 Cookie/字段诊断',
    d.handlers.wxLogin({}) === null && /\[.*\]/.test(String(d.events.find((x) => x.type === 'Error').message)),
    d.events.find((x) => x.type === 'Error'));

  // qrconnect 页拿不到 uuid（结构变更）应明确报错而不是空转
  const u = boot((m, url) => {
    if (wxPage(url)) return { preset: 1, body: '<html>改版了</html>' };
    return undefined;
  });
  check('登录页结构变更即报错',
    u.handlers.wxLogin({}) === null && u.types().join(',') === 'Error', u.types());
}

// ---------------------------------------------------------------------------
// 6) 白盒：登录态 Cookie 合成兼容微信账号（宿主 authCookie 透传的实际消费点）
// ---------------------------------------------------------------------------
console.log('== 登录态兼容 ==');
{
  const { authCookies, commUin } = boot(() => ({ preset: 1, body: '{}' })).internals;
  check('内部符号可探测', typeof authCookies === 'function' && typeof commUin === 'function');

  const qq = authCookies({ musicid: 123456, strMusicid: '123456', musickey: 'QMKEY1', authCookie: '' });
  check('QQ 账号沿用原合成值',
    qq.uin === '123456' && qq.qm_keyst === 'QMKEY1' && qq.qqmusic_key === 'QMKEY1', qq);

  const wx = authCookies({
    musicid: 0, strMusicid: 'oxABC123', musickey: 'WXKEY1',
    authCookie: 'wx_key=WXKEY1; login_type=3; p_uin=o999',
  });
  check('微信账号鉴权 Cookie 合并',
    wx.wx_key === 'WXKEY1' && wx.login_type === '3' && wx.p_uin === 'o999' &&
    wx.uin === 'oxABC123' && wx.qm_keyst === 'WXKEY1', wx);

  check('未登录不发 Cookie', Object.keys(authCookies({ musicid: 0, musickey: '', authCookie: 'wx_key=X' })).length === 0,
    authCookies({ musicid: 0, musickey: '', authCookie: 'wx_key=X' }));

  check('comm.uin 数值形态（QQ）', commUin({ musicid: 123456, strMusicid: '123456' }) === 123456, commUin({ musicid: 123456, strMusicid: '123456' }));
  check('comm.uin 退化字符串（微信 openid）', commUin({ musicid: 0, strMusicid: 'oxABC123' }) === 'oxABC123', commUin({ musicid: 0, strMusicid: 'oxABC123' }));
}

if (failed) {
  console.error('LOGIN FLOW FAILED');
  process.exit(1);
}
console.log('LOGIN FLOW OK: qrLogin + wxLogin 状态机与凭据结构回环通过');
