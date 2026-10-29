package com.dsha.whalepet;

/**
 * 播放状态机：IDLE / CHAIN / ONE_SHOT / STATUS 四态，外加被外部置成 MOVING / DRAG / FLY。
 *
 * 从 PetService 拆出来的核心一块。它只决定"现在该播什么、何时回到什么态"，
 * 具体怎么把画面播出来、窗口宽度怎么同步，交给 Host。
 */
final class PlaybackController {

    interface Host {
        Anim anim();

        boolean petReady();

        /** 真正起播（Host 里做窗口宽度同步）。 */
        void play(Anim.Act a, boolean loop, Runnable end);

        long durationMs(String dir);

        void bubble(String text);

        String str(int id);

        /** DSH 是否在思考/干活（12 秒内有事件）。 */
        boolean agentBusy();

        String statusName();

        /** 重排待机 tick（idleTick 仍在服务里）。 */
        void scheduleIdle(long ms);

        void diag(String tag, String msg);
    }

    enum Mode { IDLE, CHAIN, ONE_SHOT, STATUS, MOVING, DRAG, FLY }

    private static final long STATUS_FRESH_MS = 90_000L;

    private final Host host;
    private final java.util.Random rnd = new java.util.Random();

    private Mode mode = Mode.IDLE;
    /** 一次性动作/状态锁：期间待机 tick 不抢。 */
    private long busyUntil;
    private int idleRun;
    /** 每次状态切换自增：完成回调拿旧代号回来就丢弃，避免重播/抢状态。 */
    private int generation;

    PlaybackController(Host host) {
        this.host = host;
    }

    // ---------------- 状态查询 ----------------

    boolean isIdle() { return mode == Mode.IDLE; }
    boolean isOneShot() { return mode == Mode.ONE_SHOT; }
    boolean isStatus() { return mode == Mode.STATUS; }
    boolean isMoving() { return mode == Mode.MOVING; }
    boolean isDrag() { return mode == Mode.DRAG; }
    boolean isFly() { return mode == Mode.FLY; }
    long busyUntil() { return busyUntil; }

    Mode mode() { return mode; }

    // ---------------- 外部状态切换 ----------------

    void toMoving() { setMode(Mode.MOVING, "moving"); }
    void toDrag() { setMode(Mode.DRAG, "drag"); }
    void toFly() { setMode(Mode.FLY, "fly"); }
    void toIdle(String why) { setMode(Mode.IDLE, why); }

    /** 解开待机节流的锁（桥 15 秒没状态时用）。 */
    void unlock() { busyUntil = 0L; }

    /** 锁一段时间（抛掷、深夜睡眠循环等）。 */
    void lockFor(long ms) { busyUntil = System.currentTimeMillis() + ms; }

    /** 瞬态循环（等待确认）：播 loop 但不锁到天荒地老，lockMs 后待机链可接管。 */
    void playTransient(Anim.Act a, long lockMs) {
        setMode(Mode.IDLE, "transient");
        if (a != null) host.play(a, true, null);
        busyUntil = System.currentTimeMillis() + lockMs;
    }

    // ---------------- 播放 ----------------

    /** 底态：DSH 在思考/干活就播状态循环，否则回待机。 */
    void playBase() {
        if (host.agentBusy()) {
            Anim.Act a = host.anim().statusAct(host.statusName());
            if (a != null) {
                setMode(Mode.STATUS, "base");
                host.play(a, true, null);
                busyUntil = Long.MAX_VALUE / 4;
                return;
            }
        }
        setMode(Mode.IDLE, "base");
        playIdle();
    }

    void playIdle() {
        Anim.Act idle = host.anim().idle;
        if (idle == null) return;
        host.play(idle, true, null);
        busyUntil = 0L;
    }

    /** 用户点播/完成/报错：播完才回底态，状态变化不抢。 */
    void playOnce(Anim.Act a) {
        if (a == null) return;
        setMode(Mode.ONE_SHOT, "once");
        final int my = generation;
        busyUntil = System.currentTimeMillis() + durationOf(a) + 350L;
        host.play(a, false, () -> {
            if (my != generation) return;             // 已被拖动/抛掷/其它动作取代
            if (mode == Mode.ONE_SHOT) playBase();
            host.scheduleIdle(6000L + rnd.nextInt(8000));
        });
    }

    /** 待机链的一环：播完接下一件事，每第 5 件歇久一点。 */
    void playChain(Anim.Act a) {
        if (a == null) return;
        setMode(Mode.CHAIN, "chain");
        final int my = generation;
        busyUntil = System.currentTimeMillis() + durationOf(a) + 300L;
        host.play(a, false, () -> {
            if (my != generation) return;
            if (mode == Mode.CHAIN) playBase();
            idleRun++;
            long gap = (idleRun % 5 == 0)
                    ? (7000L + rnd.nextInt(7000))
                    : (1500L + rnd.nextInt(2600));
            host.scheduleIdle(gap);
        });
    }

    /** 思考/干活/等待的状态循环（锁住，等状态离开）。 */
    void playStatusLoop(Anim.Act a) {
        if (a == null) return;
        setMode(Mode.STATUS, "status");
        host.play(a, true, null);
        busyUntil = Long.MAX_VALUE / 4;
    }

    /** 素材真实时长；还没量到按 10 秒（素材都是 10.04 秒）。 */
    long durationOf(Anim.Act a) {
        if (a == null) return 10_000L;
        long d = host.durationMs(a.dir);
        return d > 0 ? d : 10_000L;
    }

    private void setMode(Mode m, String why) {
        if (mode == m) return;
        host.diag("mode", mode + "->" + m + (why.isEmpty() ? "" : " " + why));
        mode = m;
        generation++;
    }
}
