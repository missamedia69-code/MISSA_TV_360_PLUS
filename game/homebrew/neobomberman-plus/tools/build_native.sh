#!/usr/bin/env bash
# Build a real Neo Geo MVS cartridge with ngdevkit, then pack a .neo image.
# Prerequisites: ngdevkit >= 0.5, autoconf, automake, rsync, zip, imagemagick, sox.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
PROJECT="$ROOT/game/homebrew/neobomberman-plus"
REF=4e7900b79b1555f9f1737549a49c26fbdb1aa589
: "${BUILD_OUTPUT:=$PROJECT/build}"
command -v m68k-neogeo-elf-gcc >/dev/null || { echo 'ngdevkit toolchain missing' >&2; exit 2; }
command -v romtool.py >/dev/null || { echo 'ngdevkit tools missing' >&2; exit 2; }
mkdir -p "$BUILD_OUTPUT"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
git clone --quiet https://github.com/dciabrin/ngdevkit-examples.git "$TMP/examples"
(cd "$TMP/examples" && git checkout --quiet "$REF")
cd "$TMP/examples"
# When invoked via `make -C`, MAKEFLAGS=--print-directory pollutes the upstream
# `make _examples` output parsed by configure; its braces also need bash.
unset MAKEFLAGS MFLAGS GNUMAKEFLAGS
autoreconf -iv
bash ./configure
cp "$PROJECT/neo_main.c" 00-template/main.c
cp "$PROJECT/bomber.c" "$PROJECT/bomber.h" 00-template/
sed -i 's/^GAMEROM=ngdevkit$/GAMEROM=bomberplus/' 00-template/Makefile
sed -i 's/^LDFLAGS=$/LDFLAGS=-Wl,--defsym,rom_NGH_ID=1987/' 00-template/Makefile
sed -i 's@$(ELF):[[:space:]]*$(BUILDDIR)/main.o@$(ELF): $(BUILDDIR)/main.o $(BUILDDIR)/bomber.o@' 00-template/rom.mk
make -C 00-template cart
CHIPS=00-template/build/rom
cp "$CHIPS/bomberplus.zip" "$BUILD_OUTPUT/bomberplus.zip"
python3 "$PROJECT/tools/pack_neo.py" "$CHIPS" "$BUILD_OUTPUT/bomberplus.neo"
echo 'Prototype built. Boot and gameplay still require emulator + hardware validation.'
