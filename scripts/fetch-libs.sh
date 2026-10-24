#!/usr/bin/env bash
# Скачивает ядро Xray для Android (AndroidLibXrayLite, LGPL-3.0) в app/libs.
# Его нет в Maven, а файл весит ~60 МБ, поэтому в git он не хранится.
set -euo pipefail
VERSION="${1:-v26.10.17}"
cd "$(dirname "$0")/.."
mkdir -p app/libs
curl -fL -o app/libs/libv2ray.aar \
  "https://github.com/2dust/AndroidLibXrayLite/releases/download/$VERSION/libv2ray.aar"
echo "app/libs/libv2ray.aar ($VERSION)"
