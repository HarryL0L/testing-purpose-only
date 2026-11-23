# KMail on Termux: root cause and proper fix

The guide at https://github.com/BullyMaguire-lol/kmail-guide-termux is a
workaround. This directory contains the analysis of *why* KMail cannot set up
an account on a fresh Termux Plasma session, and two patches for the
termux-packages `kf6-kwallet` recipe that fix it at the source.

Checked against what Termux ships as of 2026-11-23: `kf6-kwallet` 6.32.0,
`qtkeychain` 0.18.0, `libsecret` 0.21.8.

## Background

Since KF 6.2x, KWallet is two daemons:

* `ksecretd` owns the wallet files and the dialogs. It speaks the freedesktop
  Secret Service API on `org.freedesktop.secrets` and `org.kde.secretservicecompat`.
* `kwalletd6` is only a shim. It implements the legacy `org.kde.KWallet` D-Bus
  API and forwards everything to ksecretd through libsecret.

Desktop distributions rarely see the problems below because kwallet-pam or an
earlier KWallet client has already created `kdewallet` by the time a mail
client asks for it. On Termux nothing does, so KMail's account setup is the
first thing to ask for a wallet and has to create it on demand. Both bugs are
in that create-on-demand path of the shim.

## Bug 1: the shim's wallet list is stale right after a wallet is created

**Symptom (seen on a device, 2026-11-23):** after `kdewallet` has been created,
a second dialog asks to create a wallet named `kdewallet__0_`.

`KWalletD::openInternal()` decides whether to create a wallet by calling
`listCollections()`. That calls `secret_service_load_collections_sync()`, which
does not query the service: it rebuilds the list from the `Collections`
property cached in libsecret's D-Bus proxy. The cache is only updated when
ksecretd's `PropertiesChanged` signal is dispatched by the shim's main loop,
and the shim does not get back to its main loop in time:

* Right after `createCollection()` the shim looks the new collection up in
  order to unlock it. It is not in the cache yet, so the `open()` that just
  created the wallet returns -1.
* If another `open()` was queued while the create dialog was on screen, it can
  be handled before the pending signal. It does not see the wallet either and
  calls `createCollection()` again. ksecretd picks a free name for the same
  label, and the user is asked to create `kdewallet__0_`.

**Fix:** `kf6-kwallet/reload-collections-from-service.patch`. Before reloading,
fetch the `Collections` property from the service and put it in the proxy's
cache. Both callers (`listCollections` and `retrieveCollection`) use the new
helper.

**Status:** reproduced and fixed in `repro/` (see below). Not yet run on a
device. Still present in kwallet master.

## Bug 2: `networkWallet()` fails when no wallet exists yet

On a fresh profile ksecretd answers `ReadAlias("default")` with `/`. The shim's
`defaultCollection()` therefore fails and `KWalletD::networkWallet()` replies
with the D-Bus error `Failed to query default collection` (kwalletd.cpp, lines
1010-1020). That error reply was introduced by kwallet MR 157 (merged
2026-07-28, first released in KF 6.29.0). Before it, kwalletd returned the
configured name ("kdewallet") unconditionally.

Two client paths used by KMail depend on that call:

1. **qtkeychain** (Akonadi IMAP resource, MailTransport, account wizard) asks
   for the network wallet name before every read or write. With 0.18.0 the job
   ends with the daemon's error.
2. **libkgapi** (Gmail OAuth tokens) calls `KWallet::Wallet::NetworkWallet()`,
   which returns an empty string on the error, and then tries `openWallet("")`.

**Fix:** `kf6-kwallet/networkwallet-fallback-when-no-default-collection.patch`.
When no default collection exists yet, `networkWallet()` and `localWallet()`
return the configured name instead of an error.

**Status:** derived from the code. With this patch installed, the create dialog
for `kdewallet` did appear on a device. Whether a create dialog would also
appear without it has not been tested yet, so treat this patch as likely
needed rather than proven needed. Still present in kwallet master.

## Installing the patches

Drop both files from `kf6-kwallet/` into `x11-packages/kf6-kwallet/` in
termux-packages and bump `TERMUX_PKG_REVISION`. They touch different source
files and apply independently, without offset, to kwallet v6.32.0 and master.
Each patch header is written as a commit message so it can be sent upstream to
invent.kde.org/frameworks/kwallet unchanged.

No qtkeychain patch is needed: 0.18.0 already contains the two fixes that were
backported here earlier (upstream PRs 304 and 305).

## Reproduction of bug 1

`repro/` has a mock Secret Service that does what ksecretd does on
`CreateCollection` (create, emit `CollectionCreated` and `PropertiesChanged`,
uniquify the name when the label is taken) and a test that makes the same
libsecret calls as the shim's `openInternal()`, twice in a row.

    ./repro/run.sh

| case | result |
|---|---|
| current, second open already queued | creates `kdewallet` and `kdewallet__0_`, both opens fail |
| current, main loop runs between opens | one wallet, first open fails, second succeeds |
| fixed, second open already queued | one wallet, both opens succeed |
| fixed, main loop runs between opens | one wallet, both opens succeed |

The "fixed" cases use `reloadCollections()` exactly as it is in the patch. This
exercises real libsecret (0.21.4 here) but not the real kwalletd6 or ksecretd.

## Testing on a device

Reset to a fresh profile first. **This deletes your KMail accounts, stored
passwords and Akonadi cache.** Skip the last line to keep locally stored mail.

    akonadictl stop
    pkill kwalletd6; pkill ksecretd
    rm -rf ~/.local/share/kwalletd ~/.config/kwalletrc
    rm -rf ~/.local/share/akonadi ~/.config/akonadi*
    rm -f  ~/.config/kmail2rc ~/.config/emailidentities ~/.config/mailtransports ~/.config/specialmailcollectionsrc
    rm -rf ~/.local/share/local-mail

Then start Plasma, open KMail directly and add an account. Expected with both
patches: one dialog for `kdewallet`, Blowfish plus a password creates it, the
wizard completes, no second wallet prompt and no logout.

To find out whether the `networkWallet` patch is needed, repeat with only
`reload-collections-from-service.patch` installed and note the wallet name the
create dialog shows, if one appears at all.

## Also worth doing

* In the create-wallet dialog GPG is still the preselected option. 6.32.0
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
