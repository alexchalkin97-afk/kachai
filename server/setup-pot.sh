#!/usr/bin/env bash
# PO-токены для YouTube (bgutil-ytdlp-pot-provider): сервер выглядит для YouTube как настоящий
# браузер — реже «подтвердите, что вы не бот» и меньше урезание скорости.
# Запускать после setup-api.sh:  scp setup-pot.sh root@HOST:/root/ && ssh root@HOST bash /root/setup-pot.sh
# Скрипт можно запускать повторно.
set -euo pipefail
VER=2.0.2

# ---- Подкачка: памяти 1 ГБ, а генератор токенов на JS берёт 150–250 МБ ----
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q /swapfile /etc/fstab || echo "/swapfile none swap sw 0 0" >> /etc/fstab
fi

# ---- Генератор токенов: HTTP-служба на Deno, только 127.0.0.1:4416 ----
DIR=/opt/bgutil
if [ ! -f "$DIR/.version-$VER" ]; then
  rm -rf "$DIR"
  git clone -q --single-branch --branch "$VER" https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git "$DIR"
  (cd "$DIR/server" && deno install --allow-scripts=npm:canvas --frozen)
  touch "$DIR/.version-$VER"
fi
chown -R kachai:kachai "$DIR"

cat > /etc/systemd/system/kachai-pot.service <<'EOF'
[Unit]
Description=bgutil PO token provider for yt-dlp (127.0.0.1:4416)
After=network-online.target

[Service]
User=kachai
WorkingDirectory=/opt/bgutil/server/node_modules
ExecStart=/usr/local/bin/deno run --allow-env --allow-net --allow-ffi=. --allow-read=. ../src/main.ts --port 4416
Restart=always
RestartSec=3
Environment=DENO_DIR=/var/lib/kachai/.deno
NoNewPrivileges=true
MemoryMax=400M

[Install]
WantedBy=multi-user.target
EOF

# ---- Плагин для yt-dlp: общая папка плагинов, видна и службе поиска, и root ----
mkdir -p /etc/yt-dlp/plugins
curl -fsSL -o /etc/yt-dlp/plugins/bgutil-ytdlp-pot-provider.zip \
  "https://github.com/Brainicism/bgutil-ytdlp-pot-provider/releases/download/$VER/bgutil-ytdlp-pot-provider.zip"

systemctl daemon-reload
systemctl enable --now kachai-pot.service
systemctl restart kachai-pot.service kachai-api.service
sleep 5
systemctl is-active kachai-pot
