#!/usr/bin/env python3
"""
Поиск видео на сервере: телефон присылает ссылку, сервер разбирает страницу yt-dlp
и возвращает тот же JSON, что дал бы `yt-dlp --dump-json` на телефоне.

Зачем: на сервере есть Deno (быстро решает JS-защиту YouTube) и быстрый канал до сайтов,
поэтому поиск идёт ~5 с вместо 15–20 с на телефоне. Ссылки на файлы YouTube привязаны
к IP, который их запросил, — поэтому -4: телефон потом качает через этот же сервер (UseIPv4).

Служба слушает только 127.0.0.1. Снаружи её не видно: Xray пускает к ней лишь
клиентов туннеля по адресу http://kachai.api/ (см. маршрут в install-vps.sh).
"""
import json
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

YT_DLP = "/usr/local/bin/yt-dlp"
TIMEOUT_S = 60
# Одно ядро: больше двух разборов сразу только замедлят каждый
SLOTS = threading.BoundedSemaphore(2)
QUEUE_WAIT_S = 20


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/info":
            return self._send(404, {"error": "not found"})
        try:
            length = min(int(self.headers.get("Content-Length", 0)), 8192)
            url = json.loads(self.rfile.read(length)).get("url", "")
        except Exception:
            return self._send(400, {"error": "bad request"})
        if not isinstance(url, str) or not url.startswith(("http://", "https://")) or len(url) > 2048:
            return self._send(400, {"error": "bad url"})

        if not SLOTS.acquire(timeout=QUEUE_WAIT_S):
            return self._send(503, {"error": "busy"})
        try:
            p = subprocess.run(
                [YT_DLP, "-4", "--no-playlist", "--dump-json", "--no-warnings",
                 # mweb — с PO-токеном (setup-pot.sh): запасной клиент, если остальные YouTube
                 # начнёт встречать «подтвердите, что вы не бот». Поиск дольше всего на ~0,5 с
                 "--extractor-args", "youtube:player_client=default,mweb",
                 "--socket-timeout", "15", "--retries", "2", "--extractor-retries", "1", "--", url],
                capture_output=True, text=True, timeout=TIMEOUT_S,
            )
        except subprocess.TimeoutExpired:
            return self._send(504, {"error": "timeout"})
        finally:
            SLOTS.release()

        if p.returncode == 0 and p.stdout.strip():
            body = p.stdout.strip().splitlines()[0].encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            # Текст ошибки yt-dlp — телефон переводит его так же, как свои ошибки
            self._send(422, {"error": "\n".join(p.stderr.strip().splitlines()[-6:])})

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        # Ссылки пользователей в журнал не пишем
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8090), Handler).serve_forever()
