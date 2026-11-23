#!/bin/bash
# run_scenario.sh <build name> <scenario> <seconds the "user" takes to answer a wallet dialog>
name=$1; scen=$2; delay=${3:-3}
P=/opt/kw-$name
R=/root/run/$name-$scen-$delay-$$; mkdir -p $R/home $R/rt; chmod 700 $R/rt
export HOME=$R/home XDG_RUNTIME_DIR=$R/rt XDG_CONFIG_HOME=$R/home/.config XDG_DATA_HOME=$R/home/.local/share XDG_CACHE_HOME=$R/home/.cache
export XDG_CURRENT_DESKTOP=KDE KDE_SESSION_VERSION=6 KDE_FULL_SESSION=true
export XDG_DATA_DIRS=$P/share:/usr/share PATH=$P/bin:/root/clients/build:$PATH LD_LIBRARY_PATH=$P/lib/x86_64-linux-gnu
export QT_QPA_PLATFORM=xcb QT_LOGGING_RULES="${WALLET_LOG:-kf.wallet.*.debug=false}"
D=:$((100 + RANDOM % 400))
Xvfb $D -screen 0 1280x800x24 >/dev/null 2>&1 & XV=$!
export DISPLAY=$D; sleep 1
openbox >/dev/null 2>&1 & OB=$!
sleep 0.5
echo "===== build=$name scenario=$scen user-delay=${delay}s ====="
dbus-run-session -- /root/scenario_inner.sh "$scen" "$delay" "$R" 2> $R/daemons.err
echo "--- wallet files on disk:"; ls $XDG_DATA_HOME/kwalletd 2>/dev/null | grep -E '\.kwl$' | sed 's/^/    /' ; [ -d $XDG_DATA_HOME/kwalletd ] || echo "    (none)"
echo "--- daemon messages:"; grep -v -E '^$|fd limit|QStandardPaths|xkbcommon|Fontconfig|propagateSizeHints|kf.windowsystem' $R/daemons.err | sort | uniq -c | sort -rn | head -${MAXMSG:-6} | sed 's/^/    /'
kill $OB $XV 2>/dev/null; pkill -x kwalletd6; pkill -x ksecretd; pkill -x async_client; pkill -x keychain_client; wait 2>/dev/null
