#!/data/data/com.termux/files/usr/bin/sh
# 重启 DSH 控制守护进程（幂等；杀掉旧进程后拉起）
for p in /proc/[0-9]*; do
  c=$(cat "$p/cmdline" 2>/dev/null | tr '\000' ' ')
  case "$c" in
    *dsh/control/server.mjs*) kill -9 "${p##*/}" 2>/dev/null ;;
  esac
done
sleep 1
sh /data/data/com.termux/files/home/dsh/control/start-daemon.sh
