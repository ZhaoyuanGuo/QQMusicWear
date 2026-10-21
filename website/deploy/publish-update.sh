#!/usr/bin/env bash
# 在服务器上执行：落地新 version.json 与 APK 到更新目录
# 前置：scp website/update/version.json <新APK> root@111.229.33.249:/tmp/qqmusicwear-update/
#       （或配合 tools/publish-update.js 输出的上传命令）
set -euo pipefail

SRC=/tmp/qqmusicwear-update
DIR=/www/wwwroot/suspect-site/qqmusicwear/update

[ -f "$SRC/version.json" ] || { echo "缺少 $SRC/version.json"; exit 1; }

echo "==> 1. 校验 version.json 是合法 JSON 且字段齐全"
node -e '
  const j = JSON.parse(require("fs").readFileSync("'"$SRC"'/version.json", "utf8"));
  if (!(j.versionCode > 0) || !j.apkUrl) { console.error("version.json 字段不完整"); process.exit(1); }
  console.log("   目标版本 v" + j.versionName + " (code " + j.versionCode + ")");
' 2>/dev/null || cat "$SRC/version.json"

echo "==> 2. 落地文件到 $DIR"
mkdir -p "$DIR"
cp -f "$SRC/version.json" "$DIR/version.json"
for apk in "$SRC"/*.apk; do
  [ -e "$apk" ] && cp -f "$apk" "$DIR/" && echo "   APK: $(basename "$apk")" || true
done
chown -R www:www "$DIR"

echo "==> 3. 冒烟测试"
sleep 1
echo -n "  version.json: "
curl -s -o /dev/null -w '%{http_code}\n' "http://127.0.0.1/qqmusicwear/update/version.json"
curl -s "http://127.0.0.1/qqmusicwear/update/version.json" | head -c 300; echo

echo "==> 完成。外网访问：http://111.229.33.249/qqmusicwear/update/version.json"
