#!/bin/bash
# Builds and runs the stale-collection-list reproduction on a private session bus.
# Needs: g++, gcc, pkg-config, libsecret-1 and glib development files, dbus-run-session.
#
#   ./run.sh            run all four cases
#
# "current" mirrors kwalletd6 6.32.0, "fixed" uses reloadCollections() from
# ../kf6-kwallet/reload-collections-from-service.patch.
set -e
cd "$(dirname "$0")"
B="${TMPDIR:-/tmp}/kwallet-repro-$$"; mkdir -p "$B"
gcc -Wall -o "$B/mock_service" mock_service.c $(pkg-config --cflags --libs gio-2.0)
g++ -std=c++17 -Wall -o "$B/shim_test" shim_test.cpp $(pkg-config --cflags --libs libsecret-1)

cat > "$B/one.sh" <<'EOF'
#!/bin/bash
B="$1"; shift
"$B/mock_service" > "$B/mock.log" 2>&1 &
M=$!
for i in $(seq 1 50); do grep -q 'MOCK: ready' "$B/mock.log" 2>/dev/null && break; sleep 0.1; done
timeout 60 "$B/shim_test" "$@"; rc=$?
kill $M 2>/dev/null; wait $M 2>/dev/null
sed 's/^/   /' "$B/mock.log"
echo "exit code: $rc"
EOF
chmod +x "$B/one.sh"
for args in "current" "current spin" "fixed" "fixed spin"; do
    echo; echo "################ $args"
    dbus-run-session -- "$B/one.sh" "$B" $args 2>&1 | grep -v 'Failed to set fd limit'
done
