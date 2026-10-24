#!/usr/bin/env bash
# Поиск видео на сервере (kachai-api.py). Запускать после install-vps.sh, из папки server/:
#   scp kachai-api.py setup-api.sh root@HOST:/root/ && ssh root@HOST bash /root/setup-api.sh
# Скрипт можно запускать повторно.
set -euo pipefail
cd "$(dirname "$0")"

# ---- yt-dlp и Deno (Deno решает JS-защиту YouTube) ----
if [ ! -x /usr/local/bin/yt-dlp ]; then
  curl -fsSL -o /usr/local/bin/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux
  chmod +x /usr/local/bin/yt-dlp
fi
if ! command -v deno >/dev/null; then
  curl -fsSL -o /tmp/deno.zip https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip
  python3 -c 'import zipfile; zipfile.ZipFile("/tmp/deno.zip").extract("deno", "/usr/local/bin")'
  chmod +x /usr/local/bin/deno
  rm -f /tmp/deno.zip
fi

# ---- Служба: отдельный пользователь без прав, только 127.0.0.1:8090 ----
id kachai >/dev/null 2>&1 || useradd --system --create-home --home-dir /var/lib/kachai --shell /usr/sbin/nologin kachai
install -D -m 755 kachai-api.py /opt/kachai/kachai-api.py

cat > /etc/systemd/system/kachai-api.service <<'EOF'
[Unit]
Description=Kachai video lookup API (127.0.0.1:8090)
After=network-online.target

[Service]
User=kachai
ExecStart=/usr/bin/python3 /opt/kachai/kachai-api.py
Restart=always
RestartSec=2
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/kachai
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

# ---- yt-dlp ломается, когда сайты что-то меняют, — обновляем раз в сутки ----
cat > /etc/systemd/system/kachai-ytdlp-update.service <<'EOF'
[Unit]
Description=Update yt-dlp

[Service]
Type=oneshot
ExecStart=/usr/local/bin/yt-dlp -U
EOF
cat > /etc/systemd/system/kachai-ytdlp-update.timer <<'EOF'
[Unit]
Description=Daily yt-dlp update

[Timer]
OnCalendar=daily
RandomizedDelaySec=1h
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now kachai-api.service kachai-ytdlp-update.timer
systemctl restart kachai-api.service

# ---- Xray: адрес http://kachai.api/ внутри туннеля ведёт на службу ----
python3 - <<'EOF'
import json
p = "/usr/local/etc/xray/config.json"
c = json.load(open(p))
# freedom по умолчанию не пускает на внутренние адреса сервера — разрешаем ровно этот порт
c["outbounds"] = [o for o in c["outbounds"] if o.get("tag") != "api"]
c["outbounds"].append({"tag": "api", "protocol": "freedom", "settings": {
    "redirect": "127.0.0.1:8090",
    "finalRules": [{"action": "allow", "network": "tcp", "ip": ["127.0.0.1"], "port": "8090"}],
}})
rules = c["routing"]["rules"]
if not any(r.get("outboundTag") == "api" for r in rules):
    rules.insert(0, {"type": "field", "domain": ["full:kachai.api"], "outboundTag": "api"})
json.dump(c, open(p, "w"), indent=2, ensure_ascii=False)
EOF
xray run -test -c /usr/local/etc/xray/config.json >/dev/null
systemctl restart xray

sleep 1
curl -fsS http://127.0.0.1:8090/health && echo " — поиск на сервере работает"
