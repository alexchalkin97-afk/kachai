#!/usr/bin/env bash
# Установка сервера Качай: Xray (VLESS + Reality) с белым списком сайтов.
#
# Запуск на чистом VPS (Ubuntu 22.04/24.04 или Debian 12) от root:
#   bash install-vps.sh
# Необязательные переменные:
#   SNI=www.microsoft.com  — под какой сайт маскируется сервер (TLS 1.3 + HTTP/2, не заблокирован в РФ)
#   PORT=443
#
# В конце скрипт печатает строки для secrets.properties.
set -euo pipefail

SNI="${SNI:-www.microsoft.com}"
PORT="${PORT:-443}"
CONFIG=/usr/local/etc/xray/config.json
SAVED=/root/kachai-client.txt

if [[ $EUID -ne 0 ]]; then
  echo "Запустите от root: sudo bash $0" >&2
  exit 1
fi

if [[ -f "$SAVED" && "${FORCE:-0}" != "1" ]]; then
  echo "Сервер уже настроен, параметры клиента:"
  cat "$SAVED"
  echo
  echo "Чтобы переустановить с новыми ключами: FORCE=1 bash $0"
  exit 0
fi

echo "==> Пакеты"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl openssl vnstat >/dev/null

echo "==> Xray (официальный установщик XTLS)"
bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install >/dev/null

XRAY=/usr/local/bin/xray
UUID="$($XRAY uuid)"
KEYS="$($XRAY x25519)"
# Формат вывода менялся между версиями: «Private key/Public key» или «PrivateKey/Password»
PRIVATE_KEY="$(echo "$KEYS" | grep -iE '^private' | awk -F': *' '{print $2}')"
PUBLIC_KEY="$(echo "$KEYS" | grep -iE '^(public key|password)' | awk -F': *' '{print $2}')"
SHORT_ID="$(openssl rand -hex 8)"
if [[ -z "$PRIVATE_KEY" || -z "$PUBLIC_KEY" ]]; then
  echo "Не удалось разобрать ключи xray x25519:" >&2
  echo "$KEYS" >&2
  exit 1
fi

echo "==> Конфиг $CONFIG"
cat > "$CONFIG" <<EOF
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "listen": "0.0.0.0",
      "port": $PORT,
      "protocol": "vless",
      "settings": {
        "clients": [ { "id": "$UUID", "flow": "xtls-rprx-vision" } ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "dest": "$SNI:443",
          "serverNames": [ "$SNI" ],
          "privateKey": "$PRIVATE_KEY",
          "shortIds": [ "$SHORT_ID" ]
        }
      },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls"], "routeOnly": true }
    }
  ],
  "outbounds": [
    { "tag": "direct", "protocol": "freedom", "settings": { "domainStrategy": "UseIPv4" } },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [
      { "type": "field", "ip": ["geoip:private"], "outboundTag": "block" },
      {
        "type": "field",
        "comment": "Белый список: только сайты с видео. Всё остальное режется, чтобы сервер не стал бесплатным VPN.",
        "domain": [
          "domain:youtube.com", "domain:youtu.be", "domain:googlevideo.com", "domain:ytimg.com",
          "domain:ggpht.com", "domain:youtube-nocookie.com", "domain:youtubei.googleapis.com",
          "domain:yt3.googleusercontent.com",
          "domain:instagram.com", "domain:cdninstagram.com", "domain:fbcdn.net", "domain:facebook.com",
          "domain:twitter.com", "domain:x.com", "domain:twimg.com",
          "domain:tiktok.com", "domain:tiktokcdn.com", "domain:tiktokv.com", "domain:tiktokcdn-us.com",
          "domain:ibytedtos.com", "domain:byteoversea.com", "domain:tiktokcdn-eu.com",
          "domain:tiktokv.eu", "domain:tiktokv.us", "domain:ttwstatic.com", "domain:byteimg.com", "domain:ibyteimg.com"
        ],
        "outboundTag": "direct"
      },
      { "type": "field", "network": "tcp,udp", "outboundTag": "block" }
    ]
  }
}
EOF

$XRAY run -test -c "$CONFIG" >/dev/null

echo "==> BBR (быстрее отдаёт видео на плохих каналах)"
cat > /etc/sysctl.d/99-kachai.conf <<EOF
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr
EOF
sysctl -q --system || true

systemctl enable -q xray
systemctl restart xray
systemctl enable -q --now vnstat || true

HOST="$(curl -fsS4 https://api.ipify.org || hostname -I | awk '{print $1}')"

cat > "$SAVED" <<EOF
# ---- в secrets.properties ----
vps.host=$HOST
vps.port=$PORT
vps.uuid=$UUID
vps.sni=$SNI
vps.publicKey=$PUBLIC_KEY
vps.shortId=$SHORT_ID

# ---- или в remote-config JSON ----
{"servers":[{"host":"$HOST","port":$PORT,"uuid":"$UUID","sni":"$SNI","publicKey":"$PUBLIC_KEY","shortId":"$SHORT_ID"}]}
EOF
chmod 600 "$SAVED"

echo
echo "Готово. Параметры для приложения (сохранены в $SAVED):"
echo
cat "$SAVED"
echo
echo "Трафик за месяц: vnstat -m"

# ---- Раздача настроек туннеля (remoteConfigUrl = http://IP:8080/config.json) ----
# Приложение берёт отсюда адрес сервера, отпечаток и дробление TLS — их можно менять без нового APK.
mkdir -p /var/www/kachai
cat > /var/www/kachai/config.json <<CFG
{"servers":[{"host":"$HOST","port":$PORT,"uuid":"$UUID","sni":"$SNI","publicKey":"$PUBLIC_KEY","shortId":"$SHORT_ID","fingerprint":"chrome","fragment":true,"fragmentLength":"100-200","fragmentInterval":"10-20"}]}
CFG
cat > /etc/systemd/system/kachai-config.service <<UNIT
[Unit]
Description=Kachai remote config (static JSON)
After=network.target

[Service]
ExecStart=/usr/bin/python3 -m http.server 8080 --bind 0.0.0.0 --directory /var/www/kachai
User=nobody
Restart=always

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable -q --now kachai-config
echo "Настройки туннеля: http://$HOST:8080/config.json"

# Поиск видео на сервере (быстрее, чем на телефоне): bash setup-api.sh
