// 发布应用内更新：本地计算 APK 体积/MD5，生成 website/update/version.json
// 用法（在项目根目录）：
//   node tools/publish-update.js --apk QQMusicWear-v3.1.0.apk --code 42 --name 3.1.0 --notes "更新说明" [--min 30]
// 之后把 website/update/version.json 与新 APK 一起 scp 上服务器（见输出提示 / publish-update.sh）
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// 轻量应用服务器 APK 直链前缀（与 AppUpdateManager.UPDATE_URLS 首选保持一致）
const SERVER_BASE = 'http://111.229.33.249/qqmusicwear/update/';

function arg(name, fallback = undefined) {
  const i = process.argv.indexOf('--' + name);
  return i > -1 ? process.argv[i + 1] : fallback;
}

const apkPath = arg('apk');
const versionCode = parseInt(arg('code'), 10);
const versionName = arg('name');
const notes = arg('notes', '');
const minVersionCode = parseInt(arg('min', '0'), 10);

if (!apkPath || !fs.existsSync(apkPath) || !(versionCode > 0) || !versionName) {
  console.error('缺少参数：--apk <路径> --code <versionCode> --name <versionName> [--notes "..."] [--min <code>]');
  process.exit(1);
}

const buf = fs.readFileSync(apkPath);
const info = {
  versionCode,
  versionName,
  apkUrl: SERVER_BASE + path.basename(apkPath),
  notes,
  minVersionCode,
  sizeBytes: buf.length,
  md5: crypto.createHash('md5').update(buf).digest('hex'),
};

const out = path.join('website', 'update', 'version.json');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify(info, null, 2) + '\n', 'utf8');
console.log(`已生成 ${out}:`);
console.log(JSON.stringify(info, null, 2));
console.log('\n下一步（上传到轻量服务器）：');
console.log(`  scp ${out} ${apkPath} root@111.229.33.249:/tmp/qqmusicwear-update/`);
console.log('  ssh root@111.229.33.249 "bash /tmp/publish-update.sh"');
