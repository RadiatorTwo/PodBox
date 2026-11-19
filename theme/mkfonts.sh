#!/bin/bash
# PodBox: converts the Inter TTFs in theme/fonts into anti-aliased Rockbox
# fonts (4 bit). Called by build.sh; the .fnt files are not committed.
# tools/buildzip.pl copies theme/fonts/*.fnt into /.rockbox/fonts.
set -e
SRC="$(cd "$(dirname "$0")/.." && pwd)"
F="$SRC/theme/fonts"
CONV=${PODBOX_BUILD:-~/podbox/build-arm64}/convttf

if [ ! -x "$CONV" ] || [ "$SRC/tools/convttf.c" -nt "$CONV" ]; then
    gcc -O2 -std=c99 "$SRC/tools/convttf.c" -o "$CONV" -lm $(pkg-config --cflags --libs freetype2)
fi

# size weight -> <size>-Inter[-Weight].fnt
# Characters 0x20..0x2122: Latin, Greek, Cyrillic, punctuation, Euro, TM.
mk() {
    local size=$1 weight=$2 name
    if [ "$weight" = Regular ]; then name="$size-Inter"; else name="$size-Inter-$weight"; fi
    local out="$F/$name.fnt" ttf="$F/Inter-$weight.ttf"
    if [ ! -f "$out" ] || [ "$ttf" -nt "$out" ] || [ "$0" -nt "$out" ]; then
        "$CONV" -L -p "$size" -s 32 -l 8482 -o "$out" "$ttf" > /dev/null
    fi
}

mk 22 SemiBold   # status bar, times, small labels
mk 24 Regular    # secondary lines
mk 28 Medium     # artist
mk 30 Regular    # UI font (lists, menus)
mk 28 SemiBold   # mini player title, overlay label
mk 36 Bold       # title, screen headers
mk 44 Bold       # volume number
