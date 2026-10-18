package com.dsha.floatball;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;

/**
 * 极简本机 HTTP 口，只监听 127.0.0.1:3091。
 * agent 侧用 curl 推送状态：
 *   /update?state=running&task=...&step=...&recent=a|b|c&steps=一|二|三&cur=2&mode=device
 *   /state   读取当前状态（含 stop 标记与进度；值里的 ; = % 会转义成 %3B %3D %25）
 *   /stop    请求停止
 *   /clearstop 清除停止标记
 *
 * steps / cur 只在提供时才覆盖；插件自动推送不带这两个参数，不会冲掉 agent 声明的计划。
 * mode 表示这一轮有没有真正接管屏幕：
 *   device = 正在模拟触屏/拉起应用（用户需要避让）
 *   chat   = 只是在写回复、读屏、改代码（手机不会自己动）
 * 旧推送方用 op=1/0 表达同一件事，这里兼容映射；两者都不传按 device 处理。
 */
public class StatusServer implements Runnable {

    public interface Listener {
        /** cur < 1 表示本次未提供进度；mode 为 "chat" 或 "device"。 */
        void onUpdate(String state, String task, String step, String recent, String steps, int cur, String mode);
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
            server = new ServerSocket();
            server.setReuseAddress(true);
            // 服务被杀后快速重启时，旧 socket 可能还没释放完，重试几次再放弃
            int tries = 0;
            while (true) {
                try {
                    server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 3091), 8);
                    break;
                } catch (Exception e) {
                    tries++;
                    if (!running || tries >= 10) {
                        // 端口被别的进程长期占用：与其做一颗收不到指令的"瞎球"，不如自己退场
                        listener.onShutdown();
                        return;
                    }
                    Thread.sleep(500);
                }
            }
            while (running) {
                Socket s = server.accept();
                try {
                    s.setSoTimeout(2000); // 客户端连上不发数据也不能把唯一的 accept 线程卡死
                    handle(s);
                } catch (SocketTimeoutException ignored) {
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
            // mode 优先；老推送方只发 op=1/0，映射成 device/chat；都没发按 device
            String mode = param(query, "mode", null);
            if (mode == null) mode = parseInt(param(query, "op", null), 1) > 0 ? "device" : "chat";
            listener.onUpdate(state, task, step, recent, steps, cur, mode);
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
