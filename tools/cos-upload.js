// 发布产物上传到腾讯云 COS：APK、官网 index.html、update/version.json
//
// 为什么走桶默认域名而不是自定义域名：
//   dl.suspect.top 前面挂了 CDN（CNAME 到 cdn.dnsv1.com），回源时会改写 Host，
//   导致按 host=dl.suspect.top 计算的签名与云端不一致（SignatureDoesNotMatch）。
//   因此**写操作一律用桶默认域名**，读操作（给用户的下载直链）继续用 dl.suspect.top。
//
// 凭证不入库：优先读环境变量 COS_SECRET_ID / COS_SECRET_KEY，
// 其次读本地未跟踪文件 tools/cos-upload.local.json：{"secretId":"...","secretKey":"..."}
//
// 用法（项目根目录）：
//   node tools/cos-upload.js --apk QQMusicWear-v3.1.0.apk --version 3.1.0 \
//        [--site website/index.html] [--json website/update/version.json]
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const API_HOST = process.env.COS_API_HOST || 'app-downloads-2026-1494458480.cos.ap-shanghai.myqcloud.com';
const PUBLIC_BASE = process.env.COS_PUBLIC_BASE || 'https://dl.suspect.top';
const PREFIX = 'qqmusicwear';

function arg(name, fallback = undefined) {
  const i = process.argv.indexOf('--' + name);
  return i > -1 ? process.argv[i + 1] : fallback;
}

function creds() {
  const id = process.env.COS_SECRET_ID, key = process.env.COS_SECRET_KEY;
  if (id && key) return { id, key };
  const localFile = path.join(__dirname, 'cos-upload.local.json');
  if (fs.existsSync(localFile)) {
    const j = JSON.parse(fs.readFileSync(localFile, 'utf8'));
    if (j.secretId && j.secretKey) return { id: j.secretId, key: j.secretKey };
  }
  console.error(
    '缺少 COS 凭证：设置环境变量 COS_SECRET_ID / COS_SECRET_KEY，或在 ' +
    `${path.relative(process.cwd(), localFile)} 写入 {"secretId":"...","secretKey":"..."}` +
    '（该文件已被 .gitignore 排除，切勿提交）'
  );
  process.exit(1);
}

const { id: SecretId, key: SecretKey } = creds();

// COS 现签规则：HttpString = Method\nUri\nParams\nHeaders\n，键名一律小写并按字典序，值需 URL 编码
const enc = s => encodeURIComponent(String(s))
  .replace(/!/g, '%21').replace(/\*/g, '%2A').replace(/'/g, '%27')
  .replace(/\(/g, '%28').replace(/\)/g, '%29');

function authorize(method, uri, params, headers) {
  const now = Math.floor(Date.now() / 1000);
  const keyTime = `${now};${now + 3600}`;
  const signKey = crypto.createHmac('sha1', SecretKey).update(keyTime).digest('hex');
  // 键名先转小写再取值，避免 Content-Type / X-Cos-Acl 这类大小写写法取到 undefined
  const lower = {};
  for (const [k, v] of Object.entries(headers)) lower[k.toLowerCase()] = v;
  const pKeys = Object.keys(params).map(k => k.toLowerCase()).sort();
  const hKeys = Object.keys(lower).sort();
  const paramStr = pKeys.map(k => `${enc(k)}=${enc(params[k])}`).join('&');
  const headerStr = hKeys.map(k => `${enc(k)}=${enc(lower[k])}`).join('&');
  const httpString = `${method}\n${uri}\n${paramStr}\n${headerStr}\n`;
  const stringToSign = `sha1\n${keyTime}\n${crypto.createHash('sha1').update(httpString).digest('hex')}\n`;
  const signature = crypto.createHmac('sha1', signKey).update(stringToSign).digest('hex');
  return `q-sign-algorithm=sha1&q-ak=${SecretId}&q-sign-time=${keyTime}&q-key-time=${keyTime}` +
    `&q-header-list=${hKeys.map(enc).join(';')}&q-url-param-list=${pKeys.map(enc).join(';')}&q-signature=${signature}`;
}

async function put(localPath, cosKey, contentType, disposition) {
  if (!fs.existsSync(localPath)) { console.error(`跳过（文件不存在）：${localPath}`); return false; }
  const body = fs.readFileSync(localPath);
  const uri = '/' + cosKey;
  const send = { 'Content-Type': contentType, 'x-cos-acl': 'public-read' };
  if (disposition) send['Content-Disposition'] = disposition;
  const res = await fetch(`https://${API_HOST}${uri}`, {
    method: 'PUT',
    headers: {
      // 参与签名的头必须与实际发出的值逐字一致，故同一对象两处复用
      Authorization: authorize('put', uri, {}, { host: API_HOST, ...send }),
      Host: API_HOST,
      ...send,
    },
    body,
  });
  const text = await res.text();
  console.log(`PUT ${cosKey} -> HTTP ${res.status} (${(body.length / 1024 / 1024).toFixed(2)} MB)`);
  if (!res.ok) console.log('   ' + (text.slice(0, 300) || '(无响应体)'));
  return res.ok;
}

(async () => {
  const apk = arg('apk');
  const site = arg('site');
  const json = arg('json');
  const version = arg('version') || (apk && (apk.match(/v(\d+\.\d+\.\d+)/) || [])[1]) || '';
  let failed = false;

  if (apk) {
    if (!version) { console.error('--apk 需要配合 --version 或文件名内含 vX.Y.Z'); process.exit(1); }
    const name = `QQMusicWear-v${version}.apk`;
    // 无扩展名副本：COS 默认域名会拦截 .apk 直链，自定义域名/CDN 侧也保留一份更稳
    failed |= !await put(apk, `${PREFIX}/apk/${name}`, 'application/vnd.android.package-archive',
      `attachment; filename="${name}"`);
    failed |= !await put(apk, `${PREFIX}/apk/QQMusicWear-v${version}`, 'application/vnd.android.package-archive',
      `attachment; filename="${name}"`);
    // 不带版本号的 latest 别名：文档/官网可以用固定链接指向最新版，避免写死版本号造成版本漂移
    failed |= !await put(apk, `${PREFIX}/apk/QQMusicWear-latest.apk`, 'application/vnd.android.package-archive',
      `attachment; filename="${name}"`);
    console.log(`   下载直链：${PUBLIC_BASE}/${PREFIX}/apk/${name}`);
    console.log(`   最新版别名：${PUBLIC_BASE}/${PREFIX}/apk/QQMusicWear-latest.apk`);
  }
  if (site) failed |= !await put(site, `${PREFIX}/website/index.html`, 'text/html; charset=utf-8', null);
  if (json) failed |= !await put(json, `${PREFIX}/update/version.json`, 'application/json; charset=utf-8', null);

  process.exit(failed ? 1 : 0);
})();
