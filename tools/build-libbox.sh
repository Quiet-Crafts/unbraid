#!/bin/bash
# Reproducible build of app/libs/libbox.aar (sing-box v1.14.4, arm64, unbraid patches applied).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$HOME/.cache/unbraid-libbox}
mkdir -p "$WORK" && cd "$WORK"
[ -d sing-box ] || git clone -q --depth 1 --branch v1.14.4 https://github.com/SagerNet/sing-box.git
cd sing-box
# Pinned: v1.14.4 = c005f35300ce76427dc285481ec5695f5b179cd2. A checkout left by an older pin moves to it.
PIN=c005f35300ce76427dc285481ec5695f5b179cd2
[ "$(git rev-parse HEAD)" = $PIN ] || git fetch -q --depth 1 origin tag v1.14.4
git checkout -q -f $PIN && git clean -qfd
[ "$(git rev-parse HEAD)" = $PIN ] || { echo "sing-box checkout is not v1.14.4"; exit 1; }
git apply "$ROOT"/tools/sing-box-*.patch
# go1.26.10: the toolchain sing-box builds 1.14.4 with upstream; Go 1.25 no longer gets security fixes.
# The pinned gomobile goes first on PATH, so another installed version cannot win.
export GOTOOLCHAIN=go1.26.10 PATH=$(go env GOPATH)/bin:$PATH
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk} ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}
# gomobile needs a JDK; Android Studio ships one.
export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13 github.com/sagernet/gomobile/cmd/gobind@v0.1.13
# Build beside the real AAR and swap only on success: a failed bind truncates its output file.
OUT="$ROOT/app/libs/libbox.new.aar" # gomobile insists on the .aar suffix
gomobile bind -o "$OUT" -target android/arm64 -androidapi 33 -javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=1.14.4 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=" \
  -tags with_gvisor,with_wireguard,with_utls,with_tailscale,ts_omit_logtail,ts_omit_cachenetmap,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird,with_openvpn,badlinkname,tfogo_checklinkname0 \
  ./experimental/libbox
mv "$OUT" "$ROOT/app/libs/libbox.aar"
# The app build checks this: commit it with the change that needs the new engine.
(cd "$ROOT/app/libs" && shasum -a 256 libbox.aar > libbox.aar.sha256)
"$ROOT/tools/notices.sh"
