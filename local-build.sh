#!/bin/bash
# Local sync/build driver for Titanium Browser.
#
# Mirrors build.sh (the CI script) but is resumable and incremental:
#   - each stage records a stamp in chromium/.titanium-local/, so re-runs skip finished work
#   - vanadium patches are rewritten in a scratch copy instead of in the submodule
#   - arm and arm64 use separate out dirs, so switching ABIs doesn't force a full rebuild
#   - signing uses ./key.jks + ./local.properties if present, else ./keys/test.jks + ./keys/local.properties
#
# Usage: ./local-build.sh [options]
#   --arch arm64|arm|all   ABIs to build (default: all)
#   --update-vanadium      check out the latest Vanadium tag first (what CI does)
#   --sync                 force gclient sync + runhooks even if already stamped
#   --reset-src            discard chromium/src changes and re-apply all patches
#   --no-build             stop after sync/patch (skip gn/ninja/sign)
#   --no-sign              build but don't sign
#   --skip-deps            never run apt / install-build-deps.sh
#   -h, --help
set -eo pipefail

ROOT=$(realpath "$(dirname "$0")")
cd "$ROOT"
source "$ROOT/common.sh"   # SCRIPT_DIR, replace, version_lt (patch.sh needs them)
export SCRIPT_DIR="$ROOT"

ARCH=all UPDATE_VANADIUM=0 FORCE_SYNC=0 RESET_SRC=0 DO_BUILD=1 DO_SIGN=1 SKIP_DEPS=0
while [ $# -gt 0 ]; do
    case "$1" in
        --arch) ARCH=$2; shift ;;
        --update-vanadium) UPDATE_VANADIUM=1 ;;
        --sync) FORCE_SYNC=1 ;;
        --reset-src) RESET_SRC=1 ;;
        --no-build) DO_BUILD=0 ;;
        --no-sign) DO_SIGN=0 ;;
        --skip-deps) SKIP_DEPS=1 ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done
case "$ARCH" in arm64) ABIS="arm64" ;; arm) ABIS="arm" ;; all) ABIS="arm arm64" ;;
    *) echo "--arch must be arm64, arm or all" >&2; exit 2 ;; esac

CHROMIUM_SOURCE=https://chromium.googlesource.com/chromium/src.git
WORK="$ROOT/chromium"
SRC="$WORK/src"
STAMPS="$WORK/.titanium-local"
LOG="$STAMPS/build-$(date +%Y%m%d-%H%M%S).log"
mkdir -p "$STAMPS"
exec > >(tee -a "$LOG") 2>&1

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31merror: %s\033[0m\n' "$*" >&2; exit 1; }
stamp() { touch "$STAMPS/$1-$VERSION"; }
stamped() { [ -e "$STAMPS/$1-$VERSION" ]; }
# git am needs an identity; pass it per-command instead of touching git config
git_id() { git -c user.name="titanium-local" -c user.email="titanium-local@localhost" "$@"; }

# ---------------------------------------------------------------- vanadium
git submodule update --init vanadium
if [ $UPDATE_VANADIUM = 1 ]; then
    log "Updating Vanadium to latest tag"
    git -C vanadium fetch --tags
    git -C vanadium checkout -f "$(git -C vanadium tag | sort -V | tail -n1)"
fi
# Earlier build.sh runs rewrite vanadium/patches in place; restore them so the
# scratch copy below always starts from pristine upstream patches.
git -C vanadium checkout -- patches
export VERSION=$(grep -m1 -o '[0-9]\+\(\.[0-9]\+\)\{3\}' vanadium/args.gn)
[ -n "$VERSION" ] || die "could not read Chromium version from vanadium/args.gn"
log "Chromium $VERSION (Vanadium $(git -C vanadium describe --tags 2>/dev/null || echo '?'))"

# ---------------------------------------------------------------- host deps
APT_PKGS="sudo lsb-release file nano git curl python3 python3-pillow imagemagick librsvg2-bin libgcc-s1:i386"
if [ $SKIP_DEPS = 0 ] && ! dpkg -s $APT_PKGS >/dev/null 2>&1; then
    log "Installing host packages (sudo)"
    sudo dpkg --add-architecture i386
    sudo apt-get update
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y $APT_PKGS
fi

# ---------------------------------------------------------------- depot_tools
if [ ! -d depot_tools/.git ]; then
    log "Cloning depot_tools"
    git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git
fi
export PATH="$ROOT/depot_tools:$PATH"

# ---------------------------------------------------------------- chromium source
if [ ! -d "$SRC/.git" ]; then
    log "Initialising chromium/src"
    mkdir -p "$SRC"
    git -C "$SRC" init
    git -C "$SRC" remote add origin "$CHROMIUM_SOURCE"
fi
cp "$ROOT/.gclient" "$WORK/.gclient"

cd "$SRC"
base_tag=$(git describe --tags --abbrev=0 2>/dev/null || true)
if [ "$base_tag" != "$VERSION" ] || [ $RESET_SRC = 1 ]; then
    [ -n "$base_tag" ] && log "Resetting chromium/src ($base_tag -> $VERSION); local changes in src are discarded"
    git rev-parse -q --verify "refs/tags/$VERSION" >/dev/null ||
        git fetch --depth 1 "$CHROMIUM_SOURCE" "+refs/tags/$VERSION:refs/tags/$VERSION"
    [ -d .git/rebase-apply ] && git am --abort
    git checkout -f --detach "$VERSION"
    git clean -fdq   # untracked, non-ignored files only; gclient deps/toolchains stay
    rm -f "$STAMPS"/*-"$VERSION"
    FORCE_SYNC=1
fi

# ---------------------------------------------------------------- vanadium patches
if [ "$(git rev-list --count "$VERSION..HEAD")" = 0 ]; then
    log "Applying Vanadium patches"
    PATCHES="$STAMPS/patches"
    rm -rf "$PATCHES"; cp -r "$ROOT/vanadium/patches" "$PATCHES"
    # https://grapheneos.org/build#browser-and-webview  (same exclusions as build.sh)
    rm -f "$PATCHES"/*trichrome-{apk-build-targets,browser-apk-targets}.patch
    rm -f "$PATCHES"/*{detailed,supported}-language*.patch
    rm -f "$PATCHES"/*javascript-optimizer-{site-setting,settings-UI}.patch
    rm -f "$PATCHES"/*component-updates.patch
    rm -f "$PATCHES"/*{pdf,PDF,for-content-public,toolbar-button,configs-from-config-app,new-tab-card,predictive-back*}*.patch
    replace "$PATCHES" "VANADIUM" "TITANIUM"
    replace "$PATCHES" "Vanadium" "Titanium"
    replace "$PATCHES" "vanadium" "titanium"
    git_id am --whitespace=nowarn --keep-non-patch "$PATCHES"/*.patch
    rm -rf "$PATCHES"
    FORCE_SYNC=1
else
    echo "Vanadium patches already applied ($(git rev-list --count "$VERSION..HEAD") commits on $VERSION)"
fi

# ---------------------------------------------------------------- gclient
if [ $FORCE_SYNC = 1 ] || ! stamped sync; then
    log "gclient sync"
    gclient sync -D --no-history --nohooks --force
    stamp sync
    rm -f "$STAMPS/hooks-$VERSION"
fi
if ! stamped hooks; then
    log "gclient runhooks"
    gclient runhooks
    stamp hooks
fi
if [ $SKIP_DEPS = 0 ] && [ ! -e "$STAMPS/install-build-deps" ]; then
    log "install-build-deps.sh (sudo)"
    # Ubuntu derivatives (Mint, Pop!_OS, ...) report their own codename and get rejected;
    # they share Ubuntu's package set, so let the script proceed with --unsupported.
    deps_flags="--no-prompt"
    case "$(lsb_release -si 2>/dev/null)" in Ubuntu|Debian) ;; *) deps_flags="$deps_flags --unsupported" ;; esac
    ./build/install-build-deps.sh $deps_flags
    touch "$STAMPS/install-build-deps"
fi

# ---------------------------------------------------------------- titanium patch.sh
if ! stamped patchsh; then
    # patch.sh is sed-based and not idempotent; refuse to run it twice on the same tree
    # (submodules ignored: the subproject-patch hook legitimately moves the v8 gitlink)
    [ -z "$(git status --porcelain --untracked-files=no --ignore-submodules=all)" ] ||
        die "chromium/src has uncommitted changes but patch.sh is not stamped; re-run with --reset-src"
    log "Applying patch.sh"
    # patch.sh may be checked out with CRLF line endings; bash can't run that
    # (commands get a trailing \r), so source a normalised copy and syntax-check it first.
    tr -d '\r' < "$ROOT/patch.sh" > "$STAMPS/patch.sh"
    bash -n "$STAMPS/patch.sh" || die "patch.sh has syntax errors"
    # build.sh sources it without errexit; keep that behaviour
    set +e +o pipefail
    source "$STAMPS/patch.sh"
    set -eo pipefail
    cd "$SRC"
    stamp patchsh
fi

[ $DO_BUILD = 1 ] || { log "Sync done (--no-build)"; exit 0; }

# ---------------------------------------------------------------- build
mkdir -p out/tmp out/release
for abi in $ABIS; do
    out="out/$abi"
    targets="chrome_public_apk"; [ "$abi" = arm64 ] && targets="$targets chrome_public_bundle"
    log "Building $targets for $abi"
    mkdir -p "$out"
    sed "s/^target_cpu = .*/target_cpu = \"$abi\"/" "$ROOT/args.gn" > "$out/args.gn.new"
    # only rewrite args.gn when it changed, so gn doesn't invalidate the build needlessly
    cmp -s "$out/args.gn.new" "$out/args.gn" && rm "$out/args.gn.new" || mv "$out/args.gn.new" "$out/args.gn"
    gn gen "$out"
    autoninja -C "$out" $targets
    name=$VERSION-$([ "$abi" = arm64 ] && echo arm64-v8a || echo armeabi-v7a)
    cp "$(find "$out/apks" -name 'Chrome*.apk' | head -n1)" "out/tmp/$name.apk"
    [ "$abi" = arm64 ] && cp "$(find "$out/apks" -name 'Chrome*.aab' | head -n1)" "out/tmp/$name.aab"
done

# ---------------------------------------------------------------- sign
if [ $DO_SIGN = 1 ]; then
    if [ -f "$ROOT/key.jks" ] && [ -f "$ROOT/local.properties" ]; then
        KS="$ROOT/key.jks" PROPS="$ROOT/local.properties"
    else
        KS="$ROOT/keys/test.jks" PROPS="$ROOT/keys/local.properties"
    fi
    [ -f "$KS" ] && [ -f "$PROPS" ] || die "no keystore found (key.jks/local.properties or keys/)"
    log "Signing with $(basename "$KS")"
    export PATH="$SRC/third_party/jdk/current/bin:$PATH"
    apksigner=$(find "$SRC/third_party/android_sdk/public/build-tools" -name apksigner | sort | tail -n1)
    # read the file the same way common.sh does (bash `source`), so shell quoting like
    # keyPassword='...' is handled; passwords go through env vars, not argv, so they
    # don't show up in ps
    export KS_PASS=$(source "$PROPS"; printf '%s' "$storePassword")
    export KEY_PASS=$(source "$PROPS"; printf '%s' "$keyPassword")
    alias_=$(source "$PROPS"; printf '%s' "$keyAlias")
    for f in out/tmp/$VERSION-*.apk; do
        "$apksigner" sign --ks "$KS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
            --ks-key-alias "$alias_" --out "out/release/$(basename "$f")" "$f"
    done
    for f in out/tmp/$VERSION-*.aab; do
        [ -e "$f" ] || continue
        jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore "$KS" \
            -storepass:env KS_PASS -keypass:env KEY_PASS \
            -signedjar "out/release/$(basename "$f")" "$f" "$alias_"
    done
    unset KS_PASS KEY_PASS
    log "Done: $SRC/out/release"
    ls -lh out/release
else
    log "Done (unsigned): $SRC/out/tmp"
    ls -lh out/tmp
fi
