#!/usr/bin/env bash
# QQMusicWear 官网部署：独立子域名 https://musicwear.suspect.top
#
# 与旧版的关键区别（务必注意）：
#   旧脚本会把 suspect-site.conf 整个覆盖到共享 vhost 文件上，而该文件现在同时承载
#   my_app 官网、/traepro/、其他项目路径与 default_server —— 覆盖会牵连全部站点。
#   本项目已拆成独立 vhost 文件 musicwear.conf，只增删自己那一份，不再碰共享配置。
#
# 前置（本机执行）：
#   scp website/deploy/musicwear.conf build/../index.html ... root@111.229.33.249:/tmp/qqmusicwear-site/
#   或在项目根目录用 tools/cos-upload.js 先发布 COS 镜像，再用本脚本同步主站。
#
# 在服务器上执行：bash deploy-qqmusicwear-site.sh
set -euo pipefail

SRC=/tmp/qqmusicwear-site
SITE_DIR=/www/wwwroot/suspect-site/qqmusicwear
VHOST_DIR=/www/server/panel/vhost/nginx
VHOST="$VHOST_DIR/musicwear.conf"
NGINX_BIN=/www/server/nginx/sbin/nginx
HOST=musicwear.suspect.top

[ -f "$SRC/index.html" ] || { echo "缺少 $SRC/index.html"; exit 1; }

echo "==> 1. 备份站点目录与 vhost"
[ -d "$SITE_DIR" ] && cp -a "$SITE_DIR" "${SITE_DIR}.bak.$(date +%Y%m%d%H%M%S)"
[ -f "$VHOST" ] && cp -f "$VHOST" "${VHOST}.bak.$(date +%Y%m%d%H%M%S)"

echo "==> 2. 部署静态文件（页面 + 截图 + 备案图 + 更新清单/APK）"
mkdir -p "$SITE_DIR"
cp -f "$SRC/index.html" "$SITE_DIR/"
[ -f "$SRC/ga_beian.png" ] && cp -f "$SRC/ga_beian.png" "$SITE_DIR/"
[ -d "$SRC/shots" ] && { mkdir -p "$SITE_DIR/shots"; cp -f "$SRC"/shots/*.jpg "$SITE_DIR/shots/"; }
if [ -d "$SRC/update" ]; then
  mkdir -p "$SITE_DIR/update"
  cp -f "$SRC"/update/version.json "$SITE_DIR/update/" 2>/dev/null || true
  cp -f "$SRC"/update/*.apk "$SITE_DIR/update/" 2>/dev/null || true
fi
chown -R www:www "$SITE_DIR"
find "$SITE_DIR" -type f -exec chmod 644 {} +
ls -la "$SITE_DIR"

echo "==> 3. 安装独立 vhost（只写 $VHOST，不动共享配置）"
cp -f "$SRC/musicwear.conf" "$VHOST"
chmod 644 "$VHOST"

echo "==> 4. 校验并重载 nginx（校验失败即中止，不重载）"
"$NGINX_BIN" -t
"$NGINX_BIN" -s reload

echo "==> 5. 冒烟测试"
sleep 1
code() { curl -sI -o /dev/null -w '%{http_code}' "$1"; }
echo "   HTTP 跳转 : $(code "http://$HOST/")（期望 301）"
echo "   HTTPS 首页: $(code "https://$HOST/")（期望 200）"
echo "   轮播截图  : $(code "https://$HOST/shots/1.jpg")（期望 200）"
echo "   备案图标  : $(code "https://$HOST/ga_beian.png")（期望 200）"
echo "   更新清单  : $(code "https://$HOST/update/version.json")（期望 200）"
echo "   协议与证书:"
curl -sI --http2 -o /dev/null -w '     http_version=%{http_version}\n' "https://$HOST/"
echo | openssl s_client -connect 127.0.0.1:443 -servername "$HOST" 2>/dev/null \
  | openssl x509 -noout -subject -enddate | sed 's/^/     /'

echo "==> 完成，访问：https://$HOST/"
