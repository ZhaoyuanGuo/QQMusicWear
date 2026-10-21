#!/usr/bin/env node
/**
 * 微信扫码登录链路线上探针（**不入 CI**：依赖外网，仅在登录失败排查时手动跑）。
 *
 * 逐段验证 wxLogin 的三个前提是否仍然成立，全程不扫码：
 *   1) qrconnect 登录页可达，且页面里能按源脚本同一正则提取到二维码 uuid；
 *   2) /connect/qrcode/<uuid> 返回图片字节（微信实际下发 image/jpeg，
 *      宿主的 BitmapFactory 与 PNG 同样能解，故只断言图字节本身）；
 *   3) /connect/l/qrconnect 长轮询回 `wx_errcode=` 赋值文本（408=等待扫码为正常）。
 * 换票一步（music.login.LoginServer）必须持有真实 wx_code，无法离线验证；
 * wxLogin 在缺字段时会把实际 Cookie 名/响应字段名写进错误信息，真机扫一次即可据此补映射。
 *
 * 用法：node tools/contract-test/live_probe_wx_login.js
 */
'use strict';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36';

// 以下常量与正则必须与 source/qmusic_source.js 的 WX_* / wxLogin 保持一致
const APPID = 'wx48db31d50e334801';
const REDIRECT = 'https://y.qq.com/wkframe/client/login_success.html';
const CONNECT = 'https://open.weixin.qq.com/connect/qrconnect' +
  '?appid=' + APPID + '&scope=snsapi_login' +
  '&redirect_uri=' + encodeURIComponent(REDIRECT) +
  '&state=qmusicwear&from=sns_qqmusic';
const QR_BASE = 'https://open.weixin.qq.com/connect/qrcode/';
const POLL = 'https://long.open.weixin.qq.com/connect/l/qrconnect';

let failed = false;
const check = (name, ok, detail) => {
  console.log((ok ? '  ok  ' : '  FAIL ') + name + (detail ? ' -> ' + detail : ''));
  if (!ok) failed = true;
};

const get = (url, headers) => fetch(url, {
  headers: Object.assign({ 'User-Agent': UA }, headers || {}),
  redirect: 'follow',
});

(async () => {
  console.log('== 1. qrconnect 登录页 ==');
  const page = await get(CONNECT, { Referer: 'https://y.qq.com/' });
  const html = await page.text();
  check('页面可达', page.ok, 'HTTP ' + page.status + '，' + html.length + ' 字节');
  const uuid = (html.match(/connect\/qrcode\/([A-Za-z0-9_-]+)/) ||
    html.match(/[?&]uuid=([A-Za-z0-9_-]+)/) ||
    html.match(/["']uuid["']\s*[:=]\s*["']([A-Za-z0-9_-]+)/) || [])[1] || '';
  check('按源脚本正则取到 uuid', !!uuid, uuid);
  // 实测：微信新登录页不在 HTML 里回显 appid（只保留二维码/长轮询标记），
  // 因此 uuid 能取到就证明页面有效；appid 不匹配时微信会回错误页（无二维码）
  check('页面为微信扫码页', html.indexOf('long.open.weixin') >= 0 && html.indexOf('wx_code') >= 0);

  if (!uuid) {
    console.error('PROBE FAILED: 无法继续（登录页结构已变）');
    process.exit(1);
  }

  console.log('== 2. 二维码图片 ==');
  const qr = await get(QR_BASE + uuid, { Referer: CONNECT, Accept: 'image/png,image/*;q=0.8' });
  const bytes = Buffer.from(await qr.arrayBuffer());
  const png = bytes[0] === 0x89 && bytes.subarray(1, 4).toString() === 'PNG';
  const jpeg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  check('返回图片字节（PNG/JPEG 均可被宿主解码）', qr.ok && (png || jpeg),
    'HTTP ' + qr.status + '，' + bytes.length + ' 字节，' +
    (qr.headers.get('content-type') || '') + '，' + (png ? 'PNG' : jpeg ? 'JPEG' : '未知格式'));

  console.log('== 3. 长轮询状态 ==');
  const poll = await get(POLL + '?uuid=' + encodeURIComponent(uuid), { Referer: CONNECT, Accept: '*/*' });
  const body = await poll.text();
  const err = (body.match(/wx_errcode\s*=\s*['"]?(\d+)/) || [])[1] || '';
  // 408=等待扫码（服务端挂起到超时）、404=已扫待确认、405=已确认带 code
  check('回 wx_errcode 赋值文本', poll.ok && !!err, 'HTTP ' + poll.status + '，body=' +
    JSON.stringify(body.substring(0, 120)));
  console.log('  提示：405 才是「已确认并携带 wx_code」（404 为已扫码待确认，403 取消，402 过期）');

  if (failed) {
    console.error('PROBE FAILED');
    process.exit(1);
  }
  console.log('PROBE OK: 微信登录链路前提成立（换票一步需真机扫码验证）');
})().catch((e) => {
  console.error('PROBE FAILED: ' + e.message);
  process.exit(1);
});
