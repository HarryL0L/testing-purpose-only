# KMail on Termux: root cause and proper fix

The guide at https://github.com/BullyMaguire-lol/kmail-guide-termux is a
workaround. This directory has the analysis of *why* KMail cannot set up a
Gmail account on a fresh Termux Plasma session, three patches for the
termux-packages `kf6-kwallet` recipe that fix it at the source, and the tests
used to find and check them.

Checked against what Termux ships as of 2026-11-23: `kf6-kwallet` 6.32.0,
`qtkeychain` 0.18.0, `libsecret` 0.21.8. Nothing in the `kmail`, `libkgapi`,
`kdepim-runtime` or `qtkeychain` packages needs to change.

## Background

Since KF 6.2x, KWallet is two daemons:

* `ksecretd` owns the wallet files and the dialogs. It speaks the freedesktop
  Secret Service API.
* `kwalletd6` is only a shim. It implements the legacy `org.kde.KWallet` D-Bus
  API and forwards everything to ksecretd through libsecret.

Desktop distributions rarely see the problems below because the wallet is
created and unlocked at login by kwallet-pam, before any application asks. On
Termux nothing does that, so KMail's account setup is the first thing to ask
for a wallet, several of its components ask at the same time, and the user
has to type a password on a phone while they wait. All three bugs are in the
shim.

## The three bugs

### 1. kwalletd6 answers nobody while a wallet dialog is open

`kwalletd6` creates and unlocks wallets with `secret_collection_create_sync()`
and `secret_service_unlock_sync()`. Those run the prompt themselves and only
return when the user is done, and the shim serves no other request until then.
Everything else has the default 25 second D-Bus timeout, so when the user
needs longer than that for the dialog:

* `KWallet::Wallet::NetworkWallet()` in another component times out and
  returns an empty string;
* that component asks to open the wallet `""`. ksecretd shows a second dialog
  for a wallet without a name, cannot report its result (invalid object path),
  and the shim never returns from the prompt. Every wallet call then hangs
  until `kwalletd6` is restarted, which is why the guide needs a logout;
* a failed asynchronous open is not reported at all (`walletAsyncOpened` is
  only emitted on success), so libkgapi waits forever before starting the
  Google sign-in and the browser never opens;
* components that made a blocking call show up as "KMail (Not Responding)" and
  as `org.kde.pim.akonadicontrol ... NoReply` in the log.

**Fix:** `kf6-kwallet/nonblocking-open.patch`. Open requests are queued and
handled one at a time; the two steps that can prompt use plain D-Bus calls to
the Secret Service (`CreateCollection`, `Unlock`, `Prompt`, `Completed`)
instead of the blocking libsecret helpers. Failed asynchronous opens are
reported, an empty wallet name is refused, and the caller's window id is
passed on to the prompt.

### 2. The shim's wallet list is stale right after a wallet is created

`listCollections()` and `retrieveCollection()` call
`secret_service_load_collections_sync()`, which does not query the service: it
rebuilds the list from the `Collections` property cached in libsecret's D-Bus
proxy. Right after a create that cache does not contain the new wallet, so the
shim cannot find the wallet it just made, and a request queued behind it asks
to create it again. ksecretd then offers `kdewallet__0_`.

**Fix:** `kf6-kwallet/reload-collections-from-service.patch`. Fetch the
property from the service before reloading.

### 3. `networkWallet()` fails when no wallet exists yet

On a fresh profile there is no default collection, and since kwallet MR 157
(first released in KF 6.29.0) `KWalletD::networkWallet()` answers with the
D-Bus error `Failed to query default collection`. QtKeychain jobs end with that
error, and `KWallet::Wallet::NetworkWallet()` returns an empty string.

**Fix:** `kf6-kwallet/networkwallet-fallback-when-no-default-collection.patch`.
Return the configured name (`kdewallet`) instead, as the old kwalletd did.

## Installing the patches

Drop the three files from `kf6-kwallet/` into `x11-packages/kf6-kwallet/` in
termux-packages and bump `TERMUX_PKG_REVISION`. They apply in filename order to
kwallet v6.32.0. Each patch header is written as a commit message.

For upstream (invent.kde.org/frameworks/kwallet): all three bugs are still in
master. Patches 2 and 3 apply to master as they are. `nonblocking-open.patch`
needs a small rebase there, because master replaced `QPair` with `std::pair`
in the code it rewrites.

## Evidence

### Real daemons (`testbed/`)

kwallet 6.32.0 built unpatched and with the three patches, real `kwalletd6`
and `ksecretd` under Xvfb, clients that do what KMail's components do
(a QtKeychain client like MailTransport, an asynchronous KWallet client like
libkgapi), and a scripted user answering the dialogs after a delay. Every run
starts from an empty home directory.

| scenario | unpatched 6.32.0 | with the three patches |
|---|---|---|
| libkgapi-like client alone | wallet name is empty, dialog for a nameless wallet, shim hangs for good | one dialog, `kdewallet` created, open succeeds |
| QtKeychain client alone | fails at once: `Failed to query default collection` | one dialog, password written |
| both clients, user answers after 3 s | (fails as above) | one dialog, one wallet, both succeed |
| both clients, user answers after 35 s | (fails as above) | one dialog, one wallet, both succeed; wallet name answered immediately |
| same, clients started in the other order | (fails as above) | one dialog, one wallet, both succeed |
| user cancels, then tries again | (fails as above) | both clients get an error instead of hanging; the retry succeeds |
| later session: unlock, user answers after 35 s | not reachable, no wallet | one unlock dialog, both succeed, stored password reads back |
| client passes its window id | dialog not attached to the window | dialog's `WM_TRANSIENT_FOR` is the client's window |

An intermediate patch set that fixed bugs 2 and 3 and only reported failed
asynchronous opens still hung in the 35 second cases (second dialog for a
nameless wallet, then a permanent hang), both when creating and when unlocking.
That is what was seen on the device as "still the same".

The table is from an Ubuntu development-release chroot (KDE Frameworks 6.30
libraries, kwallet 6.32.0 sources). The patched shim was also compiled there
with clang 22 without new warnings.

### Real Termux packages (termux-docker)

The same test bed was then run in a `termux/termux-docker:x86_64` container
with the packages from the Termux repositories: `kf6-kwallet` 6.32.0,
`libsecret` 0.21.8.2, `qt6-qtbase` 6.12.0, `qtkeychain` 0.18.0. kwallet 6.32.0
was built there with Termux's clang 21, unpatched and with the three patches.

* Unpatched: the libkgapi-like client gets an empty wallet name, a dialog for
  a nameless wallet appears, and the shim hangs, exactly as on Ubuntu.
* Patched: no new compiler warnings, and these scenarios pass: one client;
  both clients with a 35 second user; cancel and retry; later session with a
  35 second unlock (stored password reads back); window id passed to the
  dialog.

Limits of both environments: no Android and no Termux:X11 (the X server is
Xvfb); x86_64 instead of ARM; the wallet is created without GPG support, so
the dialog is the plain password dialog rather than the Blowfish/GPG chooser;
and the real KMail, Akonadi and Google sign-in are not part of it.

### libsecret only (`repro/`)

A mock Secret Service and a test that makes the shim's libsecret calls, for
bug 2 in isolation. `./repro/run.sh` shows two wallets (`kdewallet`,
`kdewallet__0_`) and failed opens with the current code, and one wallet with
`reloadCollections()`.

### On the device

* With only the `networkWallet` patch: the create dialog for `kdewallet`
  appeared, followed by one for `kdewallet__0_` hidden behind a frozen KMail.
* With the first three-patch set: still no browser, and
  `org.kde.pim.akonadicontrol ... NoReply` in the log.
* The current set has not been run on a device yet. It has been run against
  the real Termux packages in termux-docker, see above.

## Testing on a device

Reset to a fresh profile first. **This deletes your KMail accounts, stored
passwords, wallets and Akonadi cache.** Skip the last line to keep locally
stored mail. Log out of Plasma and run it from the plain Termux shell, so
nothing is still running that could write its state back.

    akonadictl stop 2>/dev/null
    pkill kmail; pkill accountwizard; pkill -f akonadi; pkill kwalletd6; pkill ksecretd
    rm -rf ~/.local/share/kwalletd ~/.config/kwalletrc
    rm -rf ~/.local/share/akonadi ~/.config/akonadi* ~/.cache/akonadi*
    rm -rf ~/.config/kmail2rc ~/.config/emailidentities ~/.config/mailtransports ~/.config/specialmailcollectionsrc ~/.local/share/kmail2 ~/.cache/kmail2
    rm -rf ~/.local/share/local-mail ~/.local/share/.local-mail.directory

Check with `ls ~/.local/share/kwalletd ~/.local/share/akonadi`: both should be
reported as missing.

Then start Plasma, open KMail directly and add an account. Expected: one
dialog for `kdewallet` (choose Blowfish and a password, take as long as you
like), then the browser opens for the Google sign-in without pressing Previous
and Next, the wizard completes, there is no second wallet prompt, and no
logout is needed.

If the browser still does not open, look in the taskbar for a "KDE Wallet
Service" entry hidden behind another window, and run
`xdg-open https://example.com` in Konsole to check that Plasma can start a
browser at all.

## Known remaining problems

* **Wallet dialogs can still open behind other windows.** The window id is
  now passed on, but libkgapi and QtKeychain pass none, so for KMail's own
  requests ksecretd still shows a dialog without a parent window. The
  applications no longer freeze while it is open, and it can be brought
  forward from the taskbar.
* **ksecretd accepts an empty collection label** and then cannot complete the
  prompt. The shim no longer sends one, but another Secret Service client
  could.
* In the create-wallet dialog GPG is the preselected option; a user without a
  GPG key has to switch to Blowfish.
* `kmail` could depend on `kwalletmanager` so the System Settings wallet page
  is always present.
