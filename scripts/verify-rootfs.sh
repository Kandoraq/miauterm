#!/usr/bin/env bash
set -euo pipefail
archive="app/src/main/assets/debian-arm64.tar.zst"
rootfs="${RUNNER_TEMP:?RUNNER_TEMP must be set}/miauterm-rootfs"
cleanup() {
    sudo rm -rf "$rootfs"
}
trap cleanup EXIT
sudo rm -rf "$rootfs"
sudo mkdir -p "$rootfs"
zstd -dc "$archive" | sudo tar --xattrs --xattrs-include='*' -xf - -C "$rootfs"
commands=(
    usr/bin/passwd usr/sbin/useradd usr/sbin/groupadd usr/sbin/userdel usr/sbin/usermod
    usr/bin/chown usr/bin/dpkg usr/bin/apt usr/bin/apt-get
    usr/bin/ping usr/sbin/ip usr/bin/ss usr/sbin/ifconfig usr/sbin/route
    usr/bin/netstat usr/sbin/arp usr/bin/curl usr/bin/wget
)
for command in "${commands[@]}"; do
    size="$(sudo stat -L -c '%s' "$rootfs/$command")"
    if [ "$size" -lt 4096 ]; then
        echo "Bundled Debian command is missing or truncated: /$command ($size bytes)" >&2
        exit 1
    fi
done
ping_caps="$(getcap "$rootfs/usr/bin/ping" 2>/dev/null || true)"
case "$ping_caps" in
    *cap_net_raw*) ;;
    *) echo "Bundled ping lost cap_net_raw: ${ping_caps:-none}" >&2; exit 1 ;;
esac
docker run --rm --platform linux/arm64 \
    --volume "$rootfs:/mnt/debian" \
    debian:trixie-slim \
    sh -ec '
      backup=""
      if [ -e /mnt/debian/etc/resolv.conf ] || [ -L /mnt/debian/etc/resolv.conf ]; then
        backup="$(mktemp /mnt/debian/etc/resolv.conf.XXXXXX)"
        rm -f "$backup"
        cp -a /mnt/debian/etc/resolv.conf "$backup"
      fi
      cp /etc/resolv.conf /mnt/debian/etc/resolv.conf
      chroot /mnt/debian /bin/sh -ec "
        apt update -qq
        curl -fsSI --max-time 15 https://deb.debian.org/debian/ >/dev/null
        setpriv --reuid=65534 --regid=65534 --clear-groups /usr/bin/ping -c 1 -W 1 127.0.0.1 >/dev/null
        ip -Version >/dev/null
        ss -V >/dev/null
        ifconfig --version >/dev/null
        route --version >/dev/null
        netstat --version >/dev/null
        arp --version >/dev/null
        useradd miauterm-home-check
        test -d /home/miauterm-home-check
      "
      rm -f /mnt/debian/etc/resolv.conf
      if [ -n "$backup" ]; then
        mv "$backup" /mnt/debian/etc/resolv.conf
      fi
    '
