package com.dsha.whalepet;

import android.os.Handler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 状态通道：SSE 优先（桥支持时一直收推送），断开退长轮询，再不行退一次性 poll。
 *
 * 从 PetService 拆出来的第二块。它只管"拿状态"，不碰窗口、播放或 UI；
 * 拿到后交给 Host 回调，由服务决定怎么联动。
 *
 * 生命周期：由服务持有一个实例，服务销毁时必须调 {@link #stop()}，否则 SSE 线程会在
 * 服务已经不存在之后继续重连并回调（既耗电，也把整个 Service 钉在内存里）。
 */
final class StatusClient {

    interface Host {
        /** 是否允许联网拉状态（熄屏/被收起/服务已销毁时不允许）。 */
        boolean active();

        void onStatus(Bridge.Status s);

        void onPending(Bridge.Approval a);

        /** 超过 15 秒没有状态：视作桥没在跑。 */
        void onStale();
    }

    private final Host host;
    private final Handler main;
    private final ExecutorService net;

    private volatile String seq = "";
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private volatile boolean sseOk;
    private final AtomicBoolean sseRunning = new AtomicBoolean(false);
    private volatile boolean stopped;
    private int sseTicks;
    /** 重连退避：2s → 4s → 8s → … → 60s，连上后复位。 */
    private long backoffMs = 2000L;

    StatusClient(Host host, Handler main, ExecutorService net) {
        this.host = host;
        this.main = main;
        this.net = net;
    }

    /** 只读：给日志或判断用。 */
    boolean sseOk() {
        return sseOk;
    }

    /** 服务销毁：置位后循环退出，且所有回调不再上抛。 */
    void stop() {
        stopped = true;
        sseOk = false;
    }

    private boolean live() {
        return !stopped && host.active();
    }

    /** 服务销毁后池可能已 shutdown：被拒绝就当这次没发。 */
    private void exec(Runnable r) {
        try {
            net.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.ignored("StatusClient.exec", e);
        }
    }

    /** 确保 SSE 在跑（熄屏/收起后回来、DSH 重启后都靠它重新连上）。 */
    void ensureSse() {
        if (!stopped) startSse();
    }

    /** SSE：桥支持时一直收推送；断开/失败就退回长轮询。 */
    void startSse() {
        if (stopped || !sseRunning.compareAndSet(false, true)) return;
        exec(() -> {
            try {
                runSseLoop();
            } finally {
                sseOk = false;
                sseRunning.set(false);
            }
        });
    }

    private void runSseLoop() {
        while (live()) {
            java.net.HttpURLConnection c = null;
            try {
                java.net.URL u = new java.net.URL(Bridge.baseUrl() + "/api/whalepet/events");
                c = (java.net.HttpURLConnection) u.openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(45000);
                c.setRequestProperty("Accept", "text/event-stream");
                if (!Bridge.token().isEmpty()) {
                    c.setRequestProperty("x-whalepet-token", Bridge.token());
                }
                if (c.getResponseCode() != 200) {
                    throw new java.io.IOException("http " + c.getResponseCode());
                }
                backoffMs = 2000L;              // 连上了，退避复位
                sseOk = true;
                Bridge.diag("sse", "connected");
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (!live()) break;          // 熄屏/销毁后不再处理挂起的事件
                    if (line.startsWith("data:")) {
                        final Bridge.Poll p = Bridge.parsePoll(line.substring(5).trim());
                        if (p != null && p.status != null) {
                            if (p.pending != null) host.onPending(p.pending);
                            host.onStatus(p.status);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.ignored("StatusClient.sse", t);
            } finally {
                sseOk = false;
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Throwable t) {
                        Log.ignored("StatusClient.sse.close", t);
                    }
                }
            }
            if (!live()) return;
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 60_000L);
        }
    }

    /** 每个节拍调用：SSE 正常时只做低频兜底查询，避免 TCP 假活。 */
    void poll() {
        if (!live()) return;
        if (!sseOk || (++sseTicks % 8) == 0) pollOnce();
    }

    private void pollOnce() {
        if (stopped || !host.active()) return;
        if (!fetching.compareAndSet(false, true)) return;
        exec(() -> {
            // 长轮询：状态没变时挂起最多 26 秒；桥不支持/超时就退回一次性 /poll
            final Bridge.Poll p = Bridge.wait(seq, 26000);
            final Bridge.Poll poll = p != null ? p : Bridge.poll(2000);
            if (stopped) {
                fetching.set(false);
                return;
            }
            if (poll == null) {
                main.post(() -> fetching.set(false));
                return;
            }
            if (poll.seq != null && !poll.seq.isEmpty()) seq = poll.seq;
            final Bridge.Status s = poll.status;
            if (poll.pending != null) main.post(() -> { if (!stopped) host.onPending(poll.pending); });
            main.post(() -> {
                fetching.set(false);
                if (stopped) return;
                if (s == null) {
                    host.onStale();
                    return;
                }
                host.onStatus(s);
            });
        });
    }
}
