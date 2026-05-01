#!/bin/bash

set -e

SINGBOX_VERSION="1.11.0"
SINGBOX_REPO="https://github.com/SagerNet/sing-box"
GOPATH_DIR="$(go env GOPATH)"
OUTPUT_DIR="TMessagesProj/libs"

echo ">> Building sing-box v${SINGBOX_VERSION} for Android"

mkdir -p "${OUTPUT_DIR}"

TMPDIR=$(mktemp -d)
cd "${TMPDIR}"

echo ">> Downloading sing-box source"
git clone --depth 1 --branch "v${SINGBOX_VERSION}" "${SINGBOX_REPO}" sing-box
cd sing-box

echo ">> Installing gomobile"
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

echo ">> Building sing-box AAR (arm64-v8a, armeabi-v7a)"
gomobile bind -target=android/arm64,android/arm -androidapi=21 \
  -tags="with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api" \
  -o "${GOPATH_DIR}/src/github.com/SagerNet/sing-box/libbox/mobile/sing-box.aar" \
  ./libbox

echo ">> Copying AAR to project"
cp "${GOPATH_DIR}/src/github.com/SagerNet/sing-box/libbox/mobile/sing-box.aar" \
   "${OLDPWD}/${OUTPUT_DIR}/sing-box.aar"

cd "${OLDPWD}"
rm -rf "${TMPDIR}"

echo ">> sing-box AAR built successfully at ${OUTPUT_DIR}/sing-box.aar"
