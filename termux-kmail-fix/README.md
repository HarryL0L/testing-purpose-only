# KMail on Termux: root cause and proper fix

The guide at https://github.com/BullyMaguire-lol/kmail-guide-termux is a
workaround. This directory contains the analysis of *why* KMail cannot set up
an account on a fresh Termux Plasma session, and a ready-to-apply patch for the
termux-packages `kf6-kwallet` recipe that fixes it at the source.

Checked against what Termux ships as of 2026-11-23: `kf6-kwallet` 6.32.0 and
`qtkeychain` 0.18.0.

## Root cause (verified against the shipped sources)

Since KF 6.2x, KWallet is two daemons:

* `ksecretd` owns the wallet files and the dialogs. It speaks the freedesktop
  Secret Service API on `org.freedesktop.secrets` and `org.kde.secretservicecompat`.
* `kwalletd6` is only a shim. It implements the legacy `org.kde.KWallet` D-Bus
  API and forwards everything to ksecretd through libsecret.

On a fresh profile there is no wallet, so ksecretd answers
`ReadAlias("default")` with `/`. The shim's `defaultCollection()` therefore
fails, and `KWalletD::networkWallet()` replies with the D-Bus error
`Failed to query default collection` (kwalletd.cpp, lines 1010-1020).
That error reply was introduced by kwallet MR 157 (merged 2026-07-28, first
released in KF 6.29.0). Before it, kwalletd returned the configured wallet name
("kdewallet") unconditionally. It is still present in 6.32.0 and in master.

Two client paths used by KMail break on that reply:

1. **qtkeychain** (Akonadi IMAP resource, MailTransport, account wizard).
   Every read or write job first asks kwalletd6 for the network wallet name.
   With qtkeychain 0.18.0 the job ends right there with the daemon's error, so
   the password is never stored and no wallet is created. (0.17.0 behaved
   worse: its availability probe used the same call, concluded KWallet was not
   available, and silently switched to libsecret. That part is fixed upstream
   in 0.18.0 by PRs 304 and 305, so no qtkeychain patch is needed any more.)
2. **libkgapi** (Gmail OAuth tokens via `KGAPI2::AccountManager`). It calls
   `KWallet::Wallet::NetworkWallet()`, which returns an empty string on the
   error, and then tries `openWallet("")`.

This is why the guide insists on creating `kdewallet` through System Settings
*before* ever starting KMail, and on logging out afterwards: the first step
makes the default alias resolve, the second restarts kwalletd6 and the Akonadi
agents. Desktop distributions rarely hit this because kwallet-pam or another
KWallet client creates the wallet at first login.

## The fix

`kf6-kwallet/networkwallet-fallback-when-no-default-collection.patch`

When no default collection exists yet, `networkWallet()` and `localWallet()`
return the configured name instead of a D-Bus error. The first `open()` then
reaches ksecretd and shows the normal create-wallet dialog. Drop the file into
`x11-packages/kf6-kwallet/` in termux-packages and bump `TERMUX_PKG_REVISION`.
The same change should be proposed upstream at invent.kde.org/frameworks/kwallet;
the patch header is written as a commit message for that purpose.

The patch applies without offset to kwallet v6.31.0, v6.32.0 and master.

## What happens after the fix

1. KMail (or an Akonadi resource) asks qtkeychain to store a password.
   qtkeychain pings `org.kde.kwalletd6`, which D-Bus-activates the shim.
2. qtkeychain calls `networkWallet()`. No default collection exists, so the
   patched shim answers `kdewallet` (the `Default Wallet` value from
   `kwalletrc`, which defaults to that name).
3. qtkeychain calls `open("kdewallet")`. The shim sees that no such collection
   exists and asks ksecretd to create it.
4. ksecretd shows its create-wallet dialog. The user picks Blowfish and a
   password; ksecretd writes `kdewallet.kwl` and the password is stored.
5. From now on the default alias resolves (ksecretd maps "default" to the
   configured name, which now exists), so the fallback is never taken again.
   libkgapi gets `kdewallet` from `NetworkWallet()` and stores the Gmail token
   in the same, already open wallet.

On later sessions the wallet only needs unlocking: the first access shows a
password prompt, since Termux has no PAM login to unlock it automatically.

## Also worth doing

* In the create-wallet dialog GPG is still the preselected option. 6.32.0 now
  explains that it needs a GPG key and that Blowfish only needs a password, but
  a user without a GPG key still has to switch to Blowfish.
* `kmail` should depend on `kwalletmanager` so the System Settings wallet page
  is always present. Today it is only a recommendation of `kf6-kwallet`.
* Google sign-in opens the browser with `QDesktopServices::openUrl` and listens
  on `127.0.0.1:<port>` for the redirect (libkgapi `fullauthenticationjob.cpp`).
  On Termux that goes `xdg-open` -> `kde-open6`, which needs a browser registered
  for `x-scheme-handler/https`. Installing a browser, or registering
  `termux-open-url` as the https handler so the Android browser completes the
  redirect, removes the "browser does not open" step from the guide.

## How to verify on a device

1. `pkg install` the rebuilt `kf6-kwallet`.
2. Remove `~/.local/share/kwalletd/` and `~/.config/kwalletrc` to simulate a
   fresh profile, start Plasma, open KMail directly, add an account.
3. Expected: the "KDE has requested to create a new wallet named kdewallet"
   dialog appears, Blowfish plus a password creates it, the wizard completes,
   and no logout is needed.

The patch was dry-run with `patch -p1` against the exact upstream sources. It
has not been built or run on a device, and the flow above is derived from
reading the code, not from a test run.
