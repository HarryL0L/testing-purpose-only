# Test bed with the real kwalletd6 and ksecretd

These scripts build kwallet 6.32.0 with and without the patches and run
client scenarios against the real daemons under Xvfb, with `xdotool` playing
the user who answers the wallet dialogs after a chosen delay.

They were run in an Ubuntu development-release ("tenacious") chroot, which
has KDE Frameworks 6.30, Qt 6.11, libsecret 0.21.8 and QtKeychain 0.18. Paths
in the scripts assume everything lives in `/root` inside that chroot.

## Setup

    debootstrap --variant=minbase --components=main,universe tenacious /srv/tn \
        http://archive.ubuntu.com/ubuntu /usr/share/debootstrap/scripts/gutsy
    # inside the chroot (with /proc and /dev mounted):
    apt-get install build-essential cmake ninja-build pkgconf gettext extra-cmake-modules \
        qt6-base-dev qt6-base-dev-tools qt6-declarative-dev libgcrypt20-dev libgpgme-dev \
        libgpgmepp-dev libsecret-1-dev libxkbcommon-dev libkf6colorscheme-dev libkf6config-dev \
        libkf6coreaddons-dev libkf6crash-dev libkf6dbusaddons-dev libkf6i18n-dev \
        libkf6notifications-dev libkf6widgetsaddons-dev libkf6windowsystem-dev libqca-qt6-dev \
        libqca-qt6-plugins libkf6wallet-dev qtkeychain-qt6-dev dbus dbus-x11 xvfb xauth \
        xdotool x11-utils fonts-dejavu-core openbox

Copy this directory's files to `/root`, the patches to `/root/patches`, and
`kwallet-6.32.0.tar.xz` to `/root/src`. Then:

    ./build.sh base                                   # unpatched
    ./build.sh fixed /root/patches/*.patch            # the three patches
    cmake -S clients -B clients/build -G Ninja && ninja -C clients/build

`build.sh` lowers kwallet's required Frameworks version from 6.32 to 6.30 and
builds without GPG support, so that creating a wallet is a single password
dialog the driver can fill in.

## Running

    ./run_scenario.sh <base|fixed> <scenario> <seconds until the user answers>

| scenario | what it does |
|---|---|
| `one-async` | one client that works like libkgapi: `NetworkWallet()`, asynchronous open, then a folder |
| `one-keychain` | one QtKeychain client writing a password, like MailTransport |
| `two` | QtKeychain client first, the libkgapi-like one 1.5 s later |
| `two-async-first` | the same two in the other order |
| `cancel-then-retry` | the user cancels the dialogs, then both clients try again |
| `later-session` | create the wallet, restart both daemons, then unlock with two clients |
| `window-parent` | a client with a window passes its window id; prints the dialog's `WM_TRANSIENT_FOR` |

Each run uses a fresh home directory, so every scenario starts without a
wallet.

## Running it against the real Termux packages

The same files work in a `termux/termux-docker:x86_64` container with these
changes:

* install `x11-repo`, then `kf6-kwallet qtkeychain clang cmake ninja pkg-config
  extra-cmake-modules kf6-kservice libsecret qca gpgme gpgmepp libgcrypt
  qt6-qtbase qt6-qtbase-gtk-platformtheme qt6-qttools dbus xorg-server-xvfb
  xdotool openbox xorg-xprop xorg-setxkbmap gettext patch glib`;
* replace `/root` with `$HOME` and `/opt/kw-` with `$HOME/kw-` in the scripts,
  use `$P/lib` and `$PREFIX/share` for the library and data paths, and start
  `scenario_inner.sh` with `bash`;
* configure kwallet as the Termux recipe does (`-DCMAKE_SYSTEM_NAME=Linux
  -DKDE_INSTALL_QMLDIR=lib/qt6/qml -DKDE_INSTALL_QTPLUGINDIR=lib/qt6/plugins`)
  and drop the line that lowers the Frameworks version, Termux has 6.32;
* Termux's `pkill -x` does not match the running daemons, kill them with
  `pkill -f bin/kwalletd6` and `pkill -f bin/ksecretd` instead. Without that
  the `later-session` scenario never restarts them and shows no unlock dialog.

