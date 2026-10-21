// 上传 APK 与官网页面到腾讯云 COS（自定义域名 dl.suspect.top）
//
// 凭证一律不入库：优先读环境变量 COS_SECRET_ID / COS_SECRET_KEY，
// 其次读本地未跟踪文件 tools/cos-upload.local.json：{"secretId":"...","secretKey":"..."}
// （该文件已在 .gitignore 中排除；密钥请放在有访问限制的子账号上）
//
// 用法（项目根目录）：
//   node tools/cos-upload.js --apk QQMusicWear-v3.1.0.apk [--site website/index.html] [--version 3.1.0]
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const Bucket = process.env.COS_BUCKET || 'app-downloads-2026-1494458480';
const Region = process.env.COS_REGION || 'ap-shanghai';
const Host = process.env.COS_HOST || 'dl.suspect.top';
const PREFIX = 'qqmusicwear';

function arg(name, fallback = undefined) {
  const i = process.argv.indexOf('--' + name);
  return i > -1 ? process.argv[i + 1] : fallback;
}

function creds() {
  const fromEnv = { id: process.env.COS_SECRET_ID, key: process.env.COS_SECRET_KEY };
  if (fromEnv.id && fromEnv.key) return fromEnv;
  const localFile = path.join(__dirname, 'cos-upload.local.json');
  if (fs.existsSync(localFile)) {
    const j = JSON.parse(fs.readFileSync(localFile, 'utf8'));
    if (j.secretId && j.secretKey) return { id: j.secretId, key: j.secretKey };
  }
  console.error(
    '缺少 COS 凭证：设置环境变量 COS_SECRET_ID / COS_SECRET_KEY，或在 ' +
    `${path.relative(process.cwd(), localFile)} 中写入 {"secretId":"...","secretKey":"..."}` +
    '（该文件已被 .gitignore 排除，切勿提交）'
  );
  process.exit(1);
}

const { id: SecretId, key: SecretKey } = creds();

function hmacSha1(key, data) {
  return crypto.createHmac('sha1', key).update(data, 'utf8').digest();
}
function sha1hex(data) {
  return crypto.createHash('sha1').update(data, 'utf8').digest('hex');
}

// 只对 host 与 x-cos-acl 签名；Content-Type / Content-Disposition 发送但不参与签名
function auth(method, uri) {
  const now = Math.floor(Date.now() / 1000);
  const keyTime = `${now};${now + 3600}`;
  const signKey = hmacSha1(SecretKey, keyTime).toString('hex');
  const headerList = 'host;x-cos-acl';
  const headers = `host=${Host}&x-cos-acl=public-read`;
  const httpString = `${method}\n${uri}\n\n${headers}\n`;
  const stringToSign = `sha1\n${keyTime}\n${sha1hex(httpString)}\n`;
  const signature = hmacSha1(signKey, stringToSign).toString('hex');
  return `q-sign-algorithm=sha1&q-ak=${SecretId}&q-sign-time=${keyTime}&q-key-time=${keyTime}&q-header-list=${headerList}&q-url-param-list=&q-signature=${signature}`;
}

async function upload(localPath, cosKey, contentType, disposition) {
  if (!fs.existsSync(localPath)) {
    console.error(`跳过（文件不存在）：${localPath}`);
    return false;
  }
  const body = fs.readFileSync(localPath);
  const uri = '/' + cosKey;
  const res = await fetch(`https://${Host}${uri}`, {
    method: 'PUT',
    headers: {
      Authorization: auth('put', uri),
      'x-cos-acl': 'public-read',
      'Content-Type': contentType,
      ...(disposition ? { 'Content-Disposition': disposition } : {}),
      Host: Host,
    },
    body,
  });
  console.log(`PUT ${cosKey} -> HTTP ${res.status} (${(body.length / 1024 / 1024).toFixed(2)} MB)`);
  if (!res.ok) console.log(await res.text());
  return res.ok;
}

(async () => {
  const apk = arg('apk');
  const site = arg('site', path.join('website', 'index.html'));
  const version = arg('version') || (apk && (apk.match(/v(\d+\.\d+\.\d+)/) || [])[1]) || '';

  if (apk) {
    const name = `QQMusicWear-v${version}.apk`;
    await upload(apk, `${PREFIX}/apk/${name}`, 'application/vnd.android.package-archive',
      `attachment; filename="${name}"`);
    // COS 默认域名会拦截 .apk，故同时放一份无扩展名 key 供直链兜底使用
    await upload(apk, `${PREFIX}/apk/QQMusicWear-v${version}`, 'application/vnd.android.package-archive',
      `attachment; filename="${name}"`);
  }
  if (site) {
    await upload(site, `${PREFIX}/website/index.html`, 'text/html; charset=utf-8', null);
  }
})();
