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
# The pinned gomobile and gobind are installed into a private folder that is first on PATH, so neither a custom
# GOBIN nor an older copy in GOPATH or elsewhere on PATH can be picked up instead.
BIN="$WORK/bin"
rm -rf "$BIN" && mkdir -p "$BIN"
export GOTOOLCHAIN=go1.26.10 GOBIN="$BIN" PATH="$BIN:$PATH"
# NetBird's embeddable client for the NetBird endpoint: release v0.83.1 with unbraid's patches, used in place of the
# published module. Its own replaces don't carry over to a module that requires it, so they are repeated here;
# route53 1.6 is the release built for the libdns 1.x that sing-box uses.
NB_PIN=499a86f4bc4795310ead5f06ecd5907e585acef4
[ -d "$WORK/netbird" ] || git clone -q --depth 1 --branch v0.83.1 https://github.com/netbirdio/netbird.git "$WORK/netbird"
[ "$(git -C "$WORK/netbird" rev-parse HEAD)" = $NB_PIN ] || git -C "$WORK/netbird" fetch -q --depth 1 origin tag v0.83.1
git -C "$WORK/netbird" checkout -q -f $NB_PIN && git -C "$WORK/netbird" clean -qfd
[ "$(git -C "$WORK/netbird" rev-parse HEAD)" = $NB_PIN ] || { echo "netbird checkout is not v0.83.1"; exit 1; }
git -C "$WORK/netbird" apply "$ROOT"/tools/netbird-*.patch
# Relative: an absolute path would end up in the built engine and differ between machines.
go mod edit -replace github.com/netbirdio/netbird=../netbird \
  -replace github.com/cloudflare/circl=codeberg.org/cunicu/circl@v0.0.0-20230801113412-fec58fc7b5f6 \
  -replace github.com/mailru/easyjson=github.com/netbirdio/easyjson@v0.9.0
go get github.com/netbirdio/netbird@v0.83.1 github.com/libdns/route53@v1.6.3
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk} ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}
# gomobile needs a JDK; Android Studio ships one.
export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13 github.com/sagernet/gomobile/cmd/gobind@v0.1.13
for h in gomobile gobind; do
  go version -m "$BIN/$h" | grep -q "mod[[:space:]]*github.com/sagernet/gomobile[[:space:]]*v0.1.13" || { echo "$h in $BIN is not v0.1.13"; exit 1; }
done
# Every other version follows from the pinned go.mod files; this lets the bind record their checksums.
export GOFLAGS=-mod=mod
TAGS=with_gvisor,with_wireguard,with_utls,with_tailscale,ts_omit_logtail,ts_omit_cachenetmap,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird,with_openvpn,badlinkname,tfogo_checklinkname0
# NetBird's daemon.proto and sing-box's started_service.proto are both proto package "daemon" and both define
# daemon.LogLevel. Go's protobuf panics on such a clash while the library loads (the app would crash at start), unless
# told to only warn. Each side's generated code uses its own descriptors, so nothing reads the other's by that name.
PROTO="-X google.golang.org/protobuf/reflect/protoregistry.conflictPolicy=warn"
# The engine's packages initialised once on this host, as the phone does when it loads the library: a panic there, or
# any name clash beyond that known one, stops the build here instead of on a phone.
out=$(go test -count=1 -v -run '^$' -tags "$TAGS" -ldflags "$PROTO -checklinkname=0" ./experimental/libbox 2>&1) || { echo "$out"; echo "engine init failed"; exit 1; }
if grep "conflict over" <<<"$out" | grep -v 'file "daemon/started_service.proto" has a name conflict over daemon\.'; then echo "a new protobuf name clash"; exit 1; fi
# Build beside the real AAR and swap only on success: a failed bind truncates its output file.
OUT="$ROOT/app/libs/libbox.new.aar" # gomobile insists on the .aar suffix
"$BIN/gomobile" bind -o "$OUT" -target android/arm64 -androidapi 33 -javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=1.14.4 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 $PROTO -checklinkname=0 -s -w -buildid=" \
  -tags "$TAGS" ./experimental/libbox
# The NDK's start-up object (crtbegin_so.o) carries the label of the compiler that built that NDK, and the macOS and
# Linux NDKs differ in it, so the same source gave a different libbox.so on each. Drop that label (.comment, unused at
# run time) and store the entries uncompressed, so the AAR is the same on every host.
python3 - "$OUT" "$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-objcopy)" <<'PY'
import os, subprocess, sys, tempfile, zipfile
aar, objcopy = sys.argv[1:]
with zipfile.ZipFile(aar) as z:
    items = [(i, z.read(i)) for i in z.infolist()]
with tempfile.TemporaryDirectory() as d, zipfile.ZipFile(aar + ".tmp", "w") as out:
    for info, data in items:
        if info.filename.endswith(".so"):
            p = os.path.join(d, "lib.so")
            open(p, "wb").write(data)
            subprocess.run([objcopy, "--remove-section=.comment", p], check=True)
            data = open(p, "rb").read()
        info.compress_type = zipfile.ZIP_STORED
        out.writestr(info, data)
os.replace(aar + ".tmp", aar)
PY
mv "$OUT" "$ROOT/app/libs/libbox.aar"
# The app build checks this: commit it with the change that needs the new engine.
(cd "$ROOT/app/libs" && shasum -a 256 libbox.aar > libbox.aar.sha256)
"$ROOT/tools/notices.sh"
