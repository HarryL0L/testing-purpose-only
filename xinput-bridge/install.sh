#!/usr/bin/env bash
# Installs the XInput bridge into a Wine prefix, so games see the Termux:X11 onscreen gamepad
# as an Xbox controller. Run it in the environment where you run Wine (Termux or proot distro).
#
#   ./install.sh [WINEPREFIX]          default: $WINEPREFIX, then ~/.wine
#   ./install.sh --uninstall [WINEPREFIX]
#
# Wine prefers its builtin xinput, so the DLLs are also set to "native, builtin" in the prefix's
# registry. When Wine runs under box64, use the same `wine` command you start games with
# (set WINE=..., e.g. WINE="box64 wine64").
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WINE="${WINE:-wine}"
DLLS=(xinput1_3 xinput1_4 xinput9_1_0)

uninstall=0
if [ "${1:-}" = "--uninstall" ]; then uninstall=1; shift; fi
PREFIX="${1:-${WINEPREFIX:-$HOME/.wine}}"
WIN="$PREFIX/drive_c/windows"
[ -d "$WIN" ] || { echo "No Wine prefix at $PREFIX (missing drive_c/windows)." >&2; exit 1; }

# 64-bit prefixes keep 64-bit DLLs in system32 and 32-bit ones in syswow64; 32-bit prefixes only have system32.
if [ -d "$WIN/syswow64" ]; then
    targets=("$WIN/system32:x86_64" "$WIN/syswow64:i686")
else
    targets=("$WIN/system32:i686")
fi

for t in "${targets[@]}"; do
    dir="${t%%:*}" arch="${t##*:}"
    for dll in "${DLLS[@]}"; do
        if [ $uninstall = 1 ]; then
            [ -f "$dir/$dll.dll.orig" ] && mv -f "$dir/$dll.dll.orig" "$dir/$dll.dll"
        else
            [ -f "$dir/$dll.dll" ] && [ ! -f "$dir/$dll.dll.orig" ] && cp "$dir/$dll.dll" "$dir/$dll.dll.orig"
            cp "$HERE/$arch/$dll.dll" "$dir/$dll.dll"
        fi
    done
    echo "$([ $uninstall = 1 ] && echo Restored || echo Installed) $arch DLLs in $dir"
done

for dll in "${DLLS[@]}"; do
    if [ $uninstall = 1 ]; then
        WINEPREFIX="$PREFIX" $WINE reg delete 'HKCU\Software\Wine\DllOverrides' /v "$dll" /f >/dev/null 2>&1 || true
    else
        WINEPREFIX="$PREFIX" $WINE reg add 'HKCU\Software\Wine\DllOverrides' /v "$dll" /d native,builtin /f >/dev/null
    fi
done
echo "$([ $uninstall = 1 ] && echo Removed || echo Set) DLL overrides."
[ $uninstall = 1 ] || echo "Done. In Termux:X11: Preferences > Onscreen gamepad > Gamepad sends > xbox controller."
