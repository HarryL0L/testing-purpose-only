#!/bin/bash
scen=$1; delay=$2; R=$3
echo create > $R/mode
driver() {
  local seen="" n=0 w mode now
  while true; do
    now=$(xdotool search --onlyvisible --name 'KDE Wallet Service' 2>/dev/null | tr '\n' ' ')
    # a window id can be reused once a dialog is gone, so only remember the ones still on screen
    [ -z "$now" ] && seen=""
    for w in $now; do
      case " $seen " in *" $w "*) continue;; esac
      seen="$seen $w"; n=$((n+1)); mode=$(cat $R/mode)
      echo "[driver] wallet dialog #$n on screen ($mode), user answers in ${delay}s"
      sleep $delay
      xdotool windowactivate --sync $w 2>/dev/null; sleep 0.3
      case "$mode" in
        create) xdotool type --delay 40 'pw123'; xdotool key Tab; xdotool type --delay 40 'pw123'; sleep 0.2; xdotool key Return ;;
        unlock) xdotool type --delay 40 'pw123'; sleep 0.2; xdotool key Return ;;
        cancel) xdotool key Escape ;;
      esac
      echo "[driver] wallet dialog #$n answered ($mode)"
    done
    sleep 0.3
  done
}
wallets() { echo "--- wallets known to kwalletd6:"; timeout 10 gdbus call --session --dest org.kde.kwalletd6 --object-path /modules/kwalletd6 --method org.kde.KWallet.wallets 2>&1 | sed 's/^/    /'; }
new_session() { echo "--- (logout/login: wallet daemons restarted, wallet is locked again)"; pkill -x kwalletd6; pkill -x ksecretd; sleep 1; }
driver & DRV=$!
case "$scen" in
  one-async)       async_client gmail 60 ;;
  one-keychain)    keychain_client smtp write ;;
  two)             keychain_client smtp write & sleep 1.5; async_client gmail 90; wait %2 2>/dev/null ;;
  two-async-first) async_client gmail 90 & sleep 1.5; keychain_client smtp write; wait %2 2>/dev/null ;;
  cancel-then-retry)
      echo cancel > $R/mode
      keychain_client smtp write & sleep 1.5; async_client gmail 60; wait %2 2>/dev/null
      wallets
      echo "--- (second attempt, this time the user fills in the dialog)"; echo create > $R/mode
      keychain_client smtp write & sleep 1.5; async_client gmail 60; wait %3 2>/dev/null ;;
  later-session)
      keychain_client smtp write
      new_session; echo unlock > $R/mode
      keychain_client smtp read & sleep 1.5; async_client gmail 90; wait %2 2>/dev/null
      keychain_client smtp2 read ;;
  window-parent)
      window_client &
      sleep 4
      app=$(xdotool search --onlyvisible --name '^FakeKMail$' | head -1); dlg=$(xdotool search --onlyvisible --name 'KDE Wallet Service' | head -1)
      echo "    app window: $app   wallet dialog: $dlg"
      echo "    dialog WM_TRANSIENT_FOR: $(xprop -id $dlg WM_TRANSIENT_FOR 2>&1 | sed 's/.*# //') (app window in hex: $(printf '0x%x' $app))"
      wait %2 2>/dev/null ;;
esac
sleep 1
wallets
kill $DRV 2>/dev/null
