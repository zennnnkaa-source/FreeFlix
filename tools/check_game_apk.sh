#!/usr/bin/env bash
# Read-only survey of a game APK for the checks a game would use to notice a
# VPN, a tunnel, or odd traffic. Modifies nothing.
# usage: tools/check_game_apk.sh /path/to/game.apk
set -u
APK="${1:?usage: $0 game.apk}"
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
unzip -q "$APK" -d "$W" || { echo "not a zip/apk: $APK"; exit 1; }

echo "== Java side (classes*.dex): presence, not proof of use =="
for pat in TRANSPORT_VPN getAllNetworks NetworkCapabilities getActiveNetwork \
           hasTransport getNetworkInterfaces NetworkInterface isVpn; do
  n=$(cat "$W"/classes*.dex 2>/dev/null | grep -a -c "$pat")
  printf '  %-22s %s\n' "$pat" "$n"
done

echo "== native side (lib/*/*.so) =="
for pat in tun0 ppp0 VpnService /proc/net/route /proc/net/tcp TRANSPORT_VPN; do
  n=$(find "$W/lib" -name '*.so' -exec cat {} + 2>/dev/null | grep -a -c "$pat")
  printf '  %-22s %s\n' "$pat" "$n"
done

echo "== engine markers =="
find "$W" \( -name 'global-metadata.dat' -o -name 'libil2cpp.so' -o -name 'libunity.so' -o -name 'libmono*.so' \) \
  -printf '  %P\n' 2>/dev/null
echo "Counts above 0 mean the API or string is in the APK. Whether it runs is a separate question."
