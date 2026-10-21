#!/usr/bin/env bash
# QQMusicWear 官网部署（挂载到 my_app 官网站点的 /qqmusicwear/ 路径下）
# 前置：静态文件已 scp 到 /tmp/qqmusicwear-site/，nginx 配置已 scp 到 /tmp/suspect-site.conf
set -euo pipefail

ROOT=/www/wwwroot/suspect-site
SITE_DIR="$ROOT/qqmusicwear"
NGINX_BIN=/www/server/nginx/sbin/nginx
VHOST=/www/server/panel/vhost/nginx/suspect-site.conf

echo "==> 1. 备份现有 nginx 站点配置"
if [ -f "$VHOST" ]; then
  cp -f "$VHOST" "${VHOST}.bak.$(date +%Y%m%d%H%M%S)"
  echo "   已备份"
fi

echo "==> 2. 部署静态文件到 ${SITE_DIR}"
mkdir -p "$SITE_DIR"
cp -f /tmp/qqmusicwear-site/index.html "$SITE_DIR/"
chown -R www:www "$SITE_DIR"
ls -la "$SITE_DIR"

echo "==> 3. 安装 nginx 站点配置"
cp -f /tmp/suspect-site.conf "$VHOST"
chmod 644 "$VHOST"

echo "==> 4. 校验并重载 nginx"
"$NGINX_BIN" -t
"$NGINX_BIN" -s reload

echo "==> 5. 本机冒烟测试"
sleep 1
echo -n "  QQMusicWear 官网: "; curl -s -o /dev/null -w '%{http_code}\n' "http://127.0.0.1/qqmusicwear/"
echo -n "  my_app 官网:      "; curl -s -o /dev/null -w '%{http_code}\n' "http://127.0.0.1/"
echo -n "  TRAE Pro 官网:    "; curl -s -o /dev/null -w '%{http_code}\n' "http://127.0.0.1/traepro/"

echo "==> 完成，访问：http://111.229.33.249/qqmusicwear/"
