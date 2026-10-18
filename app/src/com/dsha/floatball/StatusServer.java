package com.dsha.floatball;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

/**
 * 极简本机 HTTP 口，只监听 127.0.0.1:3091。
 * agent 侧用 curl 推送状态：
 *   /update?state=running&task=...&step=...&recent=a|b|c&steps=一|二|三&cur=2
 *   /state   读取当前状态（含 stop 标记与进度）
 *   /stop    请求停止
 *   /clearstop 清除停止标记
 *
 * steps / cur 只在提供时才覆盖；插件自动推送不带这两个参数，不会冲掉 agent 声明的计划。
 */
public class StatusServer implements Runnable {

    public interface Listener {
        /** cur < 1 表示本次未提供进度。 */
        void onUpdate(String state, String task, String step, String recent, String steps, int cur);
        String onQueryState();
        void onStopRequest();
        void onClearStop();
        void onShutdown();
    }

    private final Listener listener;
    private ServerSocket server;
    private volatile boolean running = true;

    public StatusServer(Listener l) {
        this.listener = l;
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket(3091, 8, InetAddress.getByName("127.0.0.1"));
            while (running) {
                Socket s = server.accept();
                try {
                    handle(s);
                } catch (Exception ignored) {
                } finally {
                    try { s.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            // 端口被占用或退出
        }
    }

    private void handle(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String line = in.readLine();
        String body;
        if (line == null) return;
        String[] parts = line.split(" ");
        String path = parts.length > 1 ? parts[1] : "/";
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        }

        if (path.equals("/state")) {
            body = listener.onQueryState();
        } else if (path.equals("/stop")) {
            listener.onStopRequest();
            body = "ok";
        } else if (path.equals("/clearstop")) {
            listener.onClearStop();
            body = "ok";
        } else if (path.equals("/shutdown")) {
            // 让服务自己停自己：比 App 侧 stopService 更确定，MIUI 不会拦
            listener.onShutdown();
            body = "bye";
        } else if (path.equals("/update")) {
            String state = param(query, "state", "idle");
            String task = param(query, "task", "");
            String step = param(query, "step", "");
            String recent = param(query, "recent", "");
            String steps = param(query, "steps", null);
            int cur = parseInt(param(query, "cur", null), -1);
            listener.onUpdate(state, task, step, recent, steps, cur);
            body = "ok";
        } else {
            body = "DSHA floatball alive";
        }

        byte[] out = body.getBytes("UTF-8");
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                + out.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(out);
        os.flush();
    }

    private static String param(String query, String key, String def) {
        if (query == null || query.isEmpty()) return def;
        String[] kv = query.split("&");
        for (String p : kv) {
            int i = p.indexOf('=');
            if (i < 0) continue;
            if (p.substring(0, i).equals(key)) {
                try {
                    return URLDecoder.decode(p.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return def;
                }
            }
        }
        return def;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    public void shutdown() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }
}
