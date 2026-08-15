package com.dshharness.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 极简本地 HTTP 服务器（127.0.0.1:8045）：
 * - GET /deploy.sh  下发部署脚本（Termux 内 curl|sh 一键部署）
 * - POST /log       接收部署脚本推送的最新日志（Termux 侧心跳上报）
 * - GET  /log       返回最新日志（App 进度卡读取）
 * 由 DeployServerService（前台服务）承载，避免后台冻结。
 */
public final class DeployServer {

    private static volatile String latestLog = "";

    private DeployServer() {}

    public static String getLatestLog() {
        return latestLog;
    }

    /** 阻塞式运行服务（调用方负责放到自己的线程） */
    public static void run(final String script) {
        try {
            ServerSocket ss = new ServerSocket(8045, 8, InetAddress.getByName("127.0.0.1"));
            ss.setReuseAddress(true);
            while (true) {
                try {
                    Socket s = ss.accept();
                    s.setSoTimeout(10000);
                    InputStream in = s.getInputStream();
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String requestLine = r.readLine();
                    int contentLength = 0;
                    String line;
                    while ((line = r.readLine()) != null && line.length() > 0) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                        }
                    }
                    OutputStream out = s.getOutputStream();
                    if (requestLine != null && requestLine.startsWith("GET /log")) {
                        byte[] body = latestLog.getBytes("UTF-8");
                        writeResponse(out, 200, body);
                    } else if (requestLine != null && requestLine.startsWith("GET /update.sh")) {
                        // 守护进程自服务更新脚本：写新文件 + 杀掉旧进程 + 拉起新进程（不依赖任何现成脚本）
                        String update = "mkdir -p $HOME/dsh/control\n"
                                + "echo '" + DeployAssets.SERVER_B64 + "' | base64 -d > $HOME/dsh/control/server.mjs\n"
                                + "for p in /proc/[0-9]*; do c=$(cat $p/cmdline 2>/dev/null | tr '\\000' ' '); case \"$c\" in *dsh/control/server.mjs*) kill -9 \"${p##*/}\" 2>/dev/null ;; esac; done\n"
                                + "sleep 1\n"
                                + "sh $HOME/dsh/control/start-daemon.sh\n"
                                + "echo '[dsh] daemon updated'\n";
                        writeResponse(out, 200, update.getBytes("UTF-8"));
                    } else if (requestLine != null && requestLine.startsWith("POST /log")) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int remaining = contentLength;
                        while (remaining > 0) {
                            int n = in.read(buf, 0, Math.min(buf.length, remaining));
                            if (n < 0) break;
                            bos.write(buf, 0, n);
                            remaining -= n;
                        }
                        latestLog = new String(bos.toByteArray(), "UTF-8");
                        writeResponse(out, 200, "ok".getBytes("UTF-8"));
                    } else {
                        byte[] body = script.getBytes("UTF-8");
                        writeResponse(out, 200, body);
                    }
                    s.close();
                } catch (Exception ignored) { /* 单次请求失败不影响服务 */ }
            }
        } catch (Exception ignored) { /* 端口占用等场景直接放弃 */ }
    }

    private static void writeResponse(OutputStream out, int code, byte[] body) throws Exception {
        out.write(("HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }
}
