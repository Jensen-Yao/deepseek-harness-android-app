#!/data/data/com.termux/files/usr/bin/sh
# 手动启动/兜底启动 DSH 控制守护进程 + sshd（幂等）
export PATH=/data/data/com.termux/files/usr/bin:$PATH
if ! curl -s --max-time 2 http://127.0.0.1:8023/api/ping >/dev/null 2>&1; then
  mkdir -p /data/data/com.termux/files/home/dsh/control
  nohup /data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/home/dsh/control/server.mjs >> /data/data/com.termux/files/home/dsh/control/daemon.log 2>&1 &
  echo "[dsh-control] daemon started"
else
  echo "[dsh-control] daemon already running"
fi
# 顺带恢复 sshd（远程维护通道，幂等）
nohup /data/data/com.termux/files/usr/bin/sshd >/dev/null 2>&1 || true
