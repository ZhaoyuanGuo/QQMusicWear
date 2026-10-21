// 发布应用内更新：本地计算 APK 体积/MD5，生成 website/update/version.json
// 用法（在项目根目录）：
//   node tools/publish-update.js --apk QQMusicWear-v3.1.0.apk --code 42 --name 3.1.0 --notes "更新说明"
// 然后上传到 COS：
//   node tools/cos-upload.js --apk QQMusicWear-v3.1.0.apk --version 3.1.0 \
//        --site website/index.html --json website/update/version.json
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// APK 直链前缀：COS 自定义域名（带 CDN，国内可达）。
// 旧的轻量服务器路径 http://111.229.33.249/qqmusicwear/update/ 已无文件（404），
// 且 AppUpdateManager 会依次回退到 GitHub raw 的 version.json，故直链统一改走 COS。
const SERVER_BASE = process.env.APK_BASE_URL || 'https://dl.suspect.top/qqmusicwear/apk/';

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
console.log('\n下一步（上传到 COS）：');
console.log(`  node tools/cos-upload.js --apk ${apkPath} --version ${versionName} \\`);
console.log('       --site website/index.html --json website/update/version.json');
console.log('   （凭证走环境变量或 tools/cos-upload.local.json，不得写回仓库）');
