# KMail on Termux: root cause and proper fix

The guide at https://github.com/BullyMaguire-lol/kmail-guide-termux is a
workaround. This directory contains the analysis of *why* KMail cannot set up
an account on a fresh Termux Plasma session and ready-to-apply patches for the
termux-packages recipes that fix it at the source.

## Root cause (verified against the shipped sources)

Termux ships `kf6-kwallet` 6.31.0. Since KF 6.2x, KWallet is two daemons:

* `ksecretd` owns the wallet files and the dialogs. It speaks the freedesktop
  Secret Service API on `org.freedesktop.secrets` and `org.kde.secretservicecompat`.
* `kwalletd6` is only a shim. It implements the legacy `org.kde.KWallet` D-Bus
  API and forwards everything to ksecretd through libsecret.

On a fresh profile there is no wallet, so ksecretd answers
`ReadAlias("default")` with `/`. The shim's `defaultCollection()` therefore
fails, and `KWalletD::networkWallet()` replies with the D-Bus error
`Failed to query default collection` (kwalletd.cpp, lines 1010-1020 in 6.31.0).
That error reply was introduced by kwallet MR 157 (merged 2026-07-28). Before
it, kwalletd returned the configured wallet name ("kdewallet") unconditionally.

Two client paths used by KMail break on that reply:

1. **qtkeychain 0.17.0** (Akonadi IMAP resource, MailTransport, account wizard).
   Its availability probe calls `networkWallet()` and treats the error as
   "KWallet not available", so it silently switches to the libsecret backend
   on `org.freedesktop.secrets`. libsecret then auto-creates a collection named
   "Default keyring" with the GPG option preselected. The chosen backend is
   cached per process, so Akonadi agents that started before a wallet existed
   stay on the wrong backend until they are restarted.
2. **libkgapi** (Gmail OAuth tokens via `KGAPI2::AccountManager`). It calls
   `KWallet::Wallet::NetworkWallet()`, which returns an empty string on the
   error, and then tries `openWallet("")`.

This is why the guide insists on creating `kdewallet` through System Settings
*before* ever starting KMail, and on logging out afterwards: the first step
makes the default alias resolve, the second restarts kwalletd6 and every
Akonadi agent that cached a backend decision. Desktop distributions rarely
hit this because kwallet-pam or another KWallet client creates the wallet at
first login.

## The fix

### 1. `x11-packages/kf6-kwallet` (essential)

`kf6-kwallet/networkwallet-fallback-when-no-default-collection.patch`

When no default collection exists yet, `networkWallet()` and `localWallet()`
return the configured name instead of a D-Bus error. The first `open()` then
reaches ksecretd and shows the normal create-wallet dialog. Drop the file into
`x11-packages/kf6-kwallet/` in termux-packages and bump `TERMUX_PKG_REVISION`.
The same change should be proposed upstream at invent.kde.org/frameworks/kwallet.

### 2. `x11-packages/qtkeychain` (hardening, both are upstream commits after 0.17.0)

* `qtkeychain/0001-handle-dbus-error-in-networkWallet.patch` backports
  frankosterfeld/qtkeychain PR 305 (KDE bug 524718): an error reply is no
  longer used as a wallet name.
* `qtkeychain/0002-ping-based-kwallet-availability-probe.patch` backports PR 304:
  the probe uses `org.freedesktop.DBus.Peer.Ping`, so a missing wallet or a busy
  kwalletd no longer makes qtkeychain abandon KWallet.

Apply them in order (0002 is based on 0001). Both can be dropped at the next
qtkeychain release that contains them.

### 3. Also worth doing

* KF 6.32 contains kwallet MR 87 "Improve new wallet onboarding", which stops
  the GPG option from being a dead end when no GPG key exists. A version bump
  picks it up.
* `kmail` should depend on `kwalletmanager` so the System Settings wallet page
  is always present. Today it is only a recommendation of `kf6-kwallet`.
* Google sign-in opens the browser with `QDesktopServices::openUrl` and listens
  on `127.0.0.1:<port>` for the redirect (libkgapi `fullauthenticationjob.cpp`).
  On Termux that goes `xdg-open` -> `kde-open6`, which needs a browser registered
  for `x-scheme-handler/https`. Installing a browser, or registering
  `termux-open-url` as the https handler so the Android browser completes the
  redirect, removes the "browser does not open" step from the guide.

## How to verify on a device

1. `pkg install` the rebuilt `kf6-kwallet` and `qtkeychain`.
2. Remove `~/.local/share/kwalletd/` and `~/.config/kwalletrc` to simulate a
   fresh profile, start Plasma, open KMail directly, add an account.
3. Expected: the "KDE has requested to create a new wallet named kdewallet"
   dialog appears, Blowfish plus a password creates it, the wizard completes,
   and no logout is needed.

Patches were dry-run against the exact upstream sources (kwallet v6.31.0 and
qtkeychain 0.17.0) with `patch -p1`; they have not been built or run on a device.
