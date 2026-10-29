package com.dsha.whalepet;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 桌面桌宠：一只小人，把「看」和「动」都收在一个窗口里。
 *
 * 手势契约（用户定的）：
 *   单击  -> 压扁回弹 + 播一个点击回应动作（偶尔抽日常小动作）
 *   双击  -> 余额面板
 *   三击  -> 启动 DSH
 *   长按  -> 菜单（余额/花费/天气/喂食/尺寸/说明/收起）
 *   拖动  -> 换位置（拖拽动作）
 *   用力甩 -> 抛物线飞出去，撞屏幕左右边按 0.78 反弹、落地摩擦减速、每次落地压一次
 *
 * 灵气来源（照插件的三条线）：
 *   1. 待机随机链：闲着自己玩魔方/哼歌/打哈欠/伸懒腰，不是只会呼吸
 *   2. 状态联动：DSH 在思考/干活/等确认/出错/收尾时换动作并冒一句话
 *   3. 时间感知：深夜犯困、早上打招呼
 *
 * 没有前台通知：这个 App 从头到尾不发通知栏通知，全部提醒都用小人头顶的气泡。
 */
public class PetService extends Service implements PetSurface.Listener {

    public static final String ACTION_START = "com.dsha.whalepet.START";
    public static final String ACTION_STOP = "com.dsha.whalepet.STOP";
    public static final String ACTION_PANEL = "com.dsha.whalepet.PANEL";
    public static final String ACTION_RESIZE = "com.dsha.whalepet.RESIZE";
    public static final String ACTION_PLAY = "com.dsha.whalepet.PLAY";
    public static final String EXTRA_DIR = "dir";
    /** 无障碍自动收起开关（默认开，但只有开了无障碍服务才生效）。 */
    public static final String KEY_A11Y_HIDE = "a11yHide";

    public static final String DSH_PKG = "com.dsha.client";
    /** 三击要打开的应用：优先用户设置，其次是 DSH，再不行退到 DeepSeek 官方 App。 */
    public static final String KEY_TARGET_PKG = "targetPkg";
    /** 可选的用量数据源地址（空 = 本机 127.0.0.1:3080）。 */
    public static final String KEY_BASE_URL = "baseUrl";
    /** 渲染方式：video（默认，单文件双区视频）/ frames（逐帧 WebP，兜底）。 */


    public static final String PREFS = "pet";
    public static final String KEY_API = "apikey";
    public static final String KEY_SIZE = "sizeDp";
    public static final String KEY_X = "x";
    public static final String KEY_Y = "y";
    /** 坐标口径版本：0/缺失 = 4.x 的「距右/距底」，1 = 5.x 的绝对左上角。 */
    public static final String KEY_COORD_VERSION = "coordV";
    /** 1.2.0：KEY_X/KEY_Y 改存「人物脚底锚点」而不是窗口左上角。 */
    public static final String KEY_ANCHOR_VERSION = "anchorV";

    /** 松手速度低于它 = 原地放下（dsh-pet 的 DEAD_ZONE_SPEED）。 */
    private static final float DEAD_SPEED = 1400f;   // 甩出去的速度门槛（px/s），调高=更难甩飞
    private static final long STEP_MS = 16L;

    /** 三档尺寸（dp）。100 最小；默认 100。 */
    public static final int[] SIZE_TIERS = {90, 120, 150};
    public static final int SIZE_DEFAULT = 90;

    /** 老档位一次性迁移：120→90 / 150→120 / 180→150（其余旧值先吸附到旧档再映射）。 */
    public static void migrateSizeTier(android.content.Context c) {
        android.content.SharedPreferences sp = Prefs.pet(c);
        if (sp.getBoolean("sizeV2", false)) return;
        int v = sp.getInt(KEY_SIZE, 120);
        int[] old = {120, 150, 180};
        int idx = 1;
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < old.length; i++) {
            int d = Math.abs(v - old[i]);
            if (d < best) { best = d; idx = i; }
        }
        sp.edit().putInt(KEY_SIZE, SIZE_TIERS[idx]).putBoolean("sizeV2", true).apply();
        Bridge.diag("size", "migrate " + v + "->" + SIZE_TIERS[idx]);
    }

    /** 老版本存的是任意数值（100/120/150/180/200/240/300…），统一吸附到最近的档位。 */
    public static int snapSize(int dp) {
        int best = SIZE_TIERS[0];
        int bestD = Integer.MAX_VALUE;
        for (int t : SIZE_TIERS) {
            int d = Math.abs(t - dp);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }


    /** 无障碍服务直接找它（同进程），避免从后台 startService 被系统拦。 */
    static volatile PetService instance;

    private WindowManager wm;
    private PetSurface pet;
    private View petView;
    private WindowManager.LayoutParams petLp;
    private Anim anim;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** 长轮询专用线程：避免长挂起占住 io（余额/账户也在用 io）。 */
    private final ExecutorService net = Executors.newFixedThreadPool(2);
    private StatusClient statusClient;
    private final Random rnd = new Random();

    private int petH, petW;
    private int screenW, screenH;
    /** 当前在播的动作（锚点/窗口宽度都以它为准）。 */
    private Anim.Act curAct;
    /** 「开始工作」：进入 working 时的一次性过渡动作（不进动作点播）。 */
    private Anim.Act startWork;
    /** 人物脚底在屏幕上的锚点：切动作/改尺寸时钉住它，人物不横移。 */
    private float anchorX, anchorY;
    /** 点击掩码（透明区域不接收触摸）。 */
    private HitMask hitMask;
    private final android.graphics.Region hitRegion = new android.graphics.Region();
    /** 系统禁止反射 touchableRegion 时置位：退回整窗可点，避免窗口彻底点不到。 */
    private boolean hitRegionBlocked;

    /** 窗口左上角的物理坐标（px）。 */
    private float px, py;
    private float vx, vy;
    /** 按下时手指相对窗口左上角的偏移（拖动期间用绝对坐标定位）。 */
    private float grabDX, grabDY;
    private boolean flying;
    private long lastStepAt;
    /** 这次抛掷已飞多久：极端用力甩出去时横向速度衰到阈值要十几秒，12 秒后让它落地收工。 */
    private long flightStartAt;

    private boolean dragging;
    /** 无障碍状态签名：只在变化时写日志，避免 tick 刷屏。 */
    private String lastA11ySig = "";
    /** 正在播"移动"本身的动作，此时不要被上面的规则打断。 */
    private boolean inMoveAct;
    /** 上一次"干活态"名字，用来识别"被打断"。 */
    private String lastBusyName = "";
    /** 被"全屏自动收起"藏起来了（≠ 用户收起，服务还活着）。 */
    private boolean petHidden;

    private PlaybackController playback;

    private PanelWindow panelWin;
    private WalletPanel walletPanel;
    private boolean panelShown;
    private String panelKind = "";

    private MenuWindow menuWin;

    private BubbleWindow bubbleWin;

    /** 熄屏时暂停一切：帧循环、状态轮询、待机链。桌宠不该在屏幕关着的时候烧电。 */
    private BroadcastReceiver screenRx;
    private boolean screenOn = true;
    /** onDestroy 之后所有异步回调一律丢弃（SSE、io、net 都可能在此之后回来）。 */
    private volatile boolean destroyed;

    private String lastStatusKey = "";
    private String lastStatusName = "";
    private long lastStatusAt;
    private String greetedDay = "";

    // ==========================================================================
    // 生命周期
    // ==========================================================================

    @Override
    public void onCreate() {
        Txt.init(this);
        LocaleState.sync(this);
        Crash.install(this);
        Log.setSink((where, msg) -> Bridge.diag("ignored", where + " " + msg));
        super.onCreate();
        instance = this;
        Prefs.pet(this).edit().putBoolean("enabled", true).apply();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Bridge.setBase(Prefs.pet(this).getString(KEY_BASE_URL, ""));
        migrateSizeTier(this);
        anim = Anim.load(this);
        hitMask = HitMask.load(this);
        for (Anim.Act a : anim.all) {
            if ("开始工作".equals(a.title)) startWork = a;
        }
        if (startWork == null) Bridge.diag("anim", "startWork missing");
        playback = new PlaybackController(new PlaybackController.Host() {
            @Override public Anim anim() { return anim; }
            @Override public boolean petReady() { return pet != null; }
            @Override public void play(Anim.Act a, boolean loop, Runnable end) {
                showAct(a, loop, end);
            }
            @Override public long durationMs(String dir) { return pet == null ? 0L : pet.durationMs(dir); }
            @Override public void bubble(String text) { PetService.this.bubble(text); }
            @Override public String str(int id) { return getString(id); }
            @Override public boolean agentBusy() { return agentBusyNow(); }
            @Override public String statusName() { return lastStatusName; }
            @Override public void scheduleIdle(long ms) { PetService.this.scheduleIdle(ms); }
            @Override public void diag(String tag, String msg) { Bridge.diag(tag, msg); }
        });
        main.postDelayed(whisperTick, 4 * 60_000L);
        main.postDelayed(balanceTick, 30_000L);
        Bridge.diag("anim", "idle=" + anim.idlePool.size() + " click=" + anim.clickPool.size()
                + " sleep=" + anim.sleepPool.size() + " error=" + anim.errorPool.size()
                + " feed=" + anim.feedPool.size() + " work=" + anim.workingPool.size());
        buildPetWindow();
        // 进程存活标记：下次启动时比对，就能看出"被系统杀掉后隔了多久重启"
        try {
            long last = Prefs.pet(this).getLong("lastAlive", 0L);
            long now = SystemClock.uptimeMillis();
            Prefs.pet(this).edit().putLong("lastAlive", now).apply();
            String ver;
            try {
                ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Throwable t) {
                ver = "?";
            }
            Bridge.diag("process", "service create v" + ver + ", gap=" + (now - last) + "ms");
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 标记失败不影响启动
        }
        registerScreenReceiver();
        scheduleIdle(1500L);
        approval = new ApprovalPanel(this, main, io, new ApprovalPanel.Host() {
            @Override
            public String str(int id) {
                return getString(id);
            }

            @Override
            public void bubble(String text) {
                PetService.this.bubble(text);
            }

            @Override
            public void hidePanel() {
                PetService.this.hidePanel();
            }

            @Override
            public void present(View content) {
                panelKind = "approval";
                presentPanel(content, false);
            }

            @Override
            public boolean petReady() {
                return pet != null;
            }

            @Override
            public boolean foregroundInDsh() {
                String pkg = PetAccessibility.foregroundPkg();
                return pkg != null && (pkg.equals(DSH_PKG) || pkg.equals(getPackageName()));
            }
        });

        actionsPanel = new ActionsPanel(this, anim, new ActionsPanel.Host() {
            @Override
            public String str(int id) {
                return getString(id);
            }

            @Override
            public void present(View content) {
                presentPanel(content);
            }

            @Override
            public void hidePanel() {
                PetService.this.hidePanel();
            }

            @Override
            public void playOnce(Anim.Act a) {
                PetService.this.playOnce(a);
            }
        });

        statusClient = new StatusClient(new StatusClient.Host() {
            @Override
            public boolean active() {
                return !destroyed && screenOn && !petHidden;
            }

            @Override
            public void onStatus(final Bridge.Status s) {
                main.post(() -> {
                    if (destroyed) return;
                    lastStatusAt = System.currentTimeMillis();
                    PetService.this.onStatus(s);
                });
            }

            @Override
            public void onPending(final Bridge.Approval a) {
                main.post(() -> {
                    if (destroyed) return;
                    approval.handlePending(a);
                });
            }

            @Override
            public void onStale() {
                main.post(() -> {
                    if (destroyed) return;
                    if (!lastStatusName.isEmpty()
                            && System.currentTimeMillis() - lastStatusAt > 15000L) {
                        lastStatusName = "";
                        lastStatusKey = "";
                        playback.unlock();
                        playIdle();
                        scheduleIdle(nextIdleDelay());
                    }
                });
            }
        }, main, net);
        // 服务在熄屏时被拉起就等亮屏再连；其余心跳照常排（它们自己会判断 active）
        if (screenOn) statusClient.startSse();
        main.postDelayed(statusPoll, 3000L);
        main.postDelayed(envTick, 3000L);
        main.postDelayed(approvalWatch, 5000L);
        main.postDelayed(greet, 2500L);
    }

    private void registerScreenReceiver() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            screenOn = pm == null || pm.isInteractive();
            screenRx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String a = i == null ? null : i.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(a)) {
                        onScreenOn();
                    } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                        onScreenOff();
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenRx, f);
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 注册失败就退化成"一直跑"，不影响功能
            screenRx = null;
        }
    }

    /** 熄屏：停帧、停轮询、停待机链，并把面板/菜单/气泡收掉（留着也没人看）。 */
    private void onScreenOff() {
        screenOn = false;
        flying = false;
        vx = 0f;
        vy = 0f;
        main.removeCallbacks(physics);
        main.removeCallbacks(idleTick);
        main.removeCallbacks(statusPoll);
        main.removeCallbacks(greet);
        main.removeCallbacks(moveStep);
        main.removeCallbacks(dragAct);
        main.removeCallbacks(envTick);
        main.removeCallbacks(approvalWatch);
        main.removeCallbacks(whisperTick);
        main.removeCallbacks(balanceTick);
        hideBubble();
        hideMenu();
        hidePanel();
        if (pet != null) pet.stop();
    }

    /** 亮屏：回到待机、恢复轮询。 */
    private void onScreenOn() {
        if (screenOn) return;
        screenOn = true;
        playBase();
        scheduleIdle(800L);
        if (statusClient != null) statusClient.ensureSse();
        main.removeCallbacks(statusPoll);
        main.post(statusPoll);
        main.removeCallbacks(envTick);
        main.postDelayed(envTick, 3000L);
        main.removeCallbacks(approvalWatch);
        main.postDelayed(approvalWatch, 5000L);
        main.removeCallbacks(balanceTick);
        main.postDelayed(balanceTick, 30_000L);
    }

    /**
     * 横屏/分屏/折叠屏展开后必须重算屏幕边界：缓存里还是竖屏的 screenW/H，
     * 小人会被夹在"半个屏幕"里（真机反馈：横屏游戏里活动范围只有半屏）。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        String lang = newConfig.getLocales().get(0).toLanguageTag();
        Txt.init(this);
        LocaleState.sync(this);
        if (anim != null) anim.relocalize(this);
        hidePanel();
        hideBubble();
        // 菜单里的文案是 show() 那一刻取的，必须一起重建，否则切语言后挂着旧文案
        hideMenu();
        Bridge.diag("locale", "changed to " + lang);
        main.post(this::refitToScreen);
    }

    private void refitToScreen() {
        try {
            Rect b = bounds();
            screenW = b.width();
            screenH = b.height();
            // 用“当前窗口位置”反算锚点：拖动/移动只改了 px/py，anchorX 字段可能是旧的
            anchorX = anchorScreenX();
            anchorY = anchorScreenY();
            // 旋转后屏幕尺寸变了：锚点夹回屏内，人物仍然“钉”在脚底，不会因为窗口越界消失
            anchorX = clampf(anchorX, 0f, screenW);
            anchorY = clampf(anchorY, 0f, screenH);
            setAnchor(anchorX, anchorY);
            applyPos();
            savePos();
            if (panelShown) placePanel();
            placeMenu();
            if (bubbleWin.isShown()) placeBubble();
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 旋转只是重排，失败不影响功能
        }
    }

    private void buildPetWindow() {
        SharedPreferences sp = Prefs.pet(this);
        petH = dp(snapSize(sp.getInt(KEY_SIZE, SIZE_DEFAULT)));
        // 窗口宽度 = 当前动作的真实宽高比（不是固定 1.45）：悬浮窗的透明区会吃掉
        // 下面应用的点击，窗口贴紧小人才能把"挡手"降到最小。
        petW = anim.idle == null ? Math.round(petH * 0.9f) : widthFor(anim.idle);

        PetAccessibility.onChange = () -> main.post(this::a11yCheck);
        panelWin = new PanelWindow(this, wm, main);
        bubbleWin = new BubbleWindow(this, wm, main);
        VideoPetView v = new VideoPetView(this);
        v.setListener(this);
        pet = v;
        petView = v;

        Rect b = bounds();
        screenW = b.width();
        screenH = b.height();

        petLp = new WindowManager.LayoutParams(
                petW, petH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        petLp.gravity = Gravity.TOP | Gravity.START;
        petLp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        int maxX = Math.max(0, screenW - petW);
        int maxY = Math.max(0, screenH - petH);

        // 坐标迁移：4.x 的 x/y 是「距右边缘 / 距底边」的边距（窗口 gravity=END|BOTTOM），
        // 5.x 改成了绝对左上角。不迁移的话，老用户升级后小人会跳到左上角去。
        int sx = sp.getInt(KEY_X, maxX - dp(6));
        int sy = sp.getInt(KEY_Y, maxY - dp(90));
        if (sp.getInt(KEY_COORD_VERSION, 0) < 1) {
            if (sp.contains(KEY_X)) sx = maxX - sx;
            if (sp.contains(KEY_Y)) sy = maxY - sy;
            sp.edit().putInt(KEY_COORD_VERSION, 1).apply();
        }
        // 1.2.0 起 KEY_X/KEY_Y 存的是「人物脚底锚点」而不是窗口左上角
        Anim.Act home = anim.idle;
        if (sp.getInt(KEY_ANCHOR_VERSION, 0) < 1) {
            float s = scaleFor(home);
            anchorX = sx + (home == null ? petW / 2f : home.ax * s);
            anchorY = sy + (home == null ? petH : home.ay * s);
            sp.edit().putInt(KEY_ANCHOR_VERSION, 1).apply();
        } else {
            anchorX = sx;
            anchorY = sy;
        }
        curAct = home;
        // 首次放置保证整窗在屏内：锚点只夹 [0,screen] 时，大尺寸会有一截跑到屏幕上方
        float hs = scaleFor(home);
        float offX = home == null ? petW / 2f : home.ax * hs;
        float offY = home == null ? petH : home.ay * hs;
        float minX = offX;
        float limitX = screenW - (petW - offX);
        float minY = offY;
        float limitY = screenH - (petH - offY);
        anchorX = clampf(anchorX, minX, Math.max(minX, limitX));
        anchorY = clampf(anchorY, minY, Math.max(minY, limitY));
        setAnchor(anchorX, anchorY);
        petLp.x = Math.round(px);
        petLp.y = Math.round(py);

        playIdle();

        try {
            wm.addView(petView, petLp);
        } catch (Throwable e) {
            toast(getString(R.string.pet_toast_overlay_failed));
            stopSelf();
            return;
        }
        installHitRegion(v);
        updateHitRegion();
    }

    /** 别的应用进全屏时把小人收起来（保留服务），退出全屏再放回来。 */
    private void setPetVisible(boolean visible) {
        Bridge.diag("pet", (visible ? "show" : "hide") + " petHiddenBefore=" + petHidden);
        if (pet == null) return;
        boolean attached = petView.getWindowToken() != null;
        if (!visible) {
            petHidden = true;
            if (!attached) return;
            hideBubble();
            hideMenu();
            hidePanel();
            pet.stop();
            remove(petView);
            main.removeCallbacks(physics);
            flying = false;
            return;
        }
        petHidden = false;
        if (statusClient != null) statusClient.ensureSse();
        if (attached) {
            // 窗口还在不代表画面还在（GL surface 可能刚被重建）——重新喂一次底态
            playBase();
            petView.invalidate();
            scheduleIdle(600L);
            return;
        }
        try {
            wm.addView(petView, petLp);
        } catch (Throwable t) { Log.ignored("PetService", t);
            return;
        }
        installHitRegion((VideoPetView) petView);
        updateHitRegion();
        playBase();
        scheduleIdle(800L);
        main.removeCallbacks(statusPoll);
        main.post(statusPoll);
    }

    private void rebuildSize() {
        int newH = dp(snapSize(Prefs.pet(this)
                .getInt(KEY_SIZE, SIZE_DEFAULT)));
        if (newH == petH || pet == null || petView == null) return;
        // 记住人物脚底：改尺寸只换像素高度，人物不能跟着上下/左右跳
        float keepX = anchorScreenX();
        float keepY = anchorScreenY();

        // 正确顺序（GLSurfaceView 生命周期）：
        //   onPause() 释放 EGL surface/渲染线程 → 移除窗口 → 改尺寸 → 重新挂上 → onResume()
        // 少了 onPause/onResume，旧 surface 会残留；反复切尺寸后 buffer 尺寸对不上，
        // 画面被裁、限位也跟着错。
        try {
            pet.pauseSurface();
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 未初始化
        }
        try {
            wm.removeView(petView);
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 没挂上
        }

        petH = newH;
        // 宽度必须跟着「当前动作」走：以前按 idle 算，正在播 640 宽的动作时会被横向压扁
        petW = widthFor(shownAct());
        hideBubble();
        if (panelShown) hidePanel();
        hideMenu();

        Rect b = bounds();
        screenW = b.width();
        screenH = b.height();
        petLp.width = petW;
        petLp.height = petH;
        setAnchor(keepX, keepY);
        petLp.x = Math.round(px);
        petLp.y = Math.round(py);

        try {
            // 关键：窗口尺寸变了，surface buffer 也必须跟着变；
            // 否则画面按旧尺寸渲染，再被新窗口裁掉（120dp 只露左半边就是这个现象）。
            wm.addView(petView, petLp);
        } catch (Throwable e) {
            toast(getString(R.string.pet_toast_resize_failed, e.getMessage()));
            return;
        }
        installHitRegion((VideoPetView) petView);
        try {
            pet.resumeSurface();
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 恢复失败下次动作会重试
        }
        petView.post(() -> Bridge.diag("size", "resized view="
                + petView.getWidth() + "x" + petView.getHeight()
                + " buffer=" + petW + "x" + petH));
        playBase();
        updateHitRegion();
        Bridge.diag("size", "resize " + petW + "x" + petH);
        savePos();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_START.equals(a)) {
                // 可能正被"全屏收起/先躲起来"藏着；用户主动点启动就是要它回来
                petHidden = false;
                tempHidePkg = null;
                setPetVisible(true);
                scheduleIdle(600L);
                main.removeCallbacks(statusPoll);
                main.post(statusPoll);
                return START_STICKY;
            }
            if (ACTION_STOP.equals(a)) {
                Prefs.pet(this).edit().putBoolean("enabled", false).apply();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_PANEL.equals(a)) {
                showPanel("wallet");
                return START_STICKY;
            }
            if (ACTION_PLAY.equals(a) && intent.hasExtra(EXTRA_DIR)) {
                String dir = intent.getStringExtra(EXTRA_DIR);
                // 待机不在 anim.all 里，单独处理：以前菜单点「待机呼吸休闲」是空操作
                if (anim.idle != null && anim.idle.dir.equals(dir)) {
                    playIdle();
                    return START_STICKY;
                }
                for (Anim.Act act : anim.all) {
                    if (act.dir.equals(dir)) {
                        playOnce(act);
                        break;
                    }
                }
                return START_STICKY;
            }
            if (ACTION_RESIZE.equals(a)) {
                rebuildSize();
                return START_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (instance == this) instance = null;
        main.removeCallbacksAndMessages(null);
        if (statusClient != null) statusClient.stop();
        if (screenRx != null) {
            try {
                unregisterReceiver(screenRx);
            } catch (Throwable t) { Log.ignored("PetService", t);
                // 没注册成功
            }
            screenRx = null;
        }
        PetAccessibility.onChange = null;
        // 面板自己不会收到销毁通知：先 dispose，否则在途请求回来会往已关闭的 io 池里提交任务
        if (walletPanel != null) {
            walletPanel.dispose();
            walletPanel = null;
        }
        io.shutdownNow();
        net.shutdownNow();
        if (pet != null) {
            pet.stop();
            remove(petView);
        }
        if (panelWin != null) panelWin.hide();
        if (menuWin != null) menuWin.hide();
        bubbleWin.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==========================================================================
    // 手势
    // ==========================================================================

    @Override
    public void onSingleTap() {
        if (menuWin != null && menuWin.isShown()) {
            hideMenu();
            return;
        }
        if (panelShown) {
            hidePanel();
            return;
        }
        playClickResponse();
    }


    /** 先躲起来：记住当前应用，等它被暂停（或 60 秒）后回来。 */
    private void doTempHide() {
        hideMenu();
        String pkg = Foreground.current(this);
        // 自己/桌面/输入法都不算"当前应用"，否则一躲就被自己的 pause 事件叫回来
        if (pkg != null && (pkg.equals(getPackageName()) || pkg.contains("input"))) {
            pkg = "";
        }
        tempHidePkg = pkg == null ? "" : pkg;
        tempHideSince = SystemClock.uptimeMillis();
        setPetVisible(false);
        Bridge.diag("tempHide", "hidden by gesture/menu, pkg=" + pkg);
    }

    @Override
    public void onDoubleTap() {
        hideMenu();
        Anim.Act dbl = anim.nextDoubleTap();                 // 看天气 / 翻钱包 轮换
        if (dbl != null) playOnce(dbl);
        showPanel("wallet");
    }

    @Override
    public void onTripleTap() {
        hideMenu();
        hidePanel();
        launchDsha();
        bubble(getString(R.string.pet_bubble_calling, targetLabel()));
    }

    @Override
    public void onLongPress() {
        hidePanel();
        if (menuWin != null && menuWin.isShown()) {
            hideMenu();
        } else {
            showMenu();
        }
    }

    @Override
    public void onDragStart(float rawX, float rawY) {
        if (moveKind != null) endMove();
        playback.toDrag();
        dragging = true;
        flying = false;
        main.removeCallbacks(physics);
        hideBubble();
        hideMenu();
        hidePanel();
        // 记住"手指在小人身上的哪个位置"，之后用绝对坐标算，避免逐帧增量累积误差
        grabDX = rawX - px;
        grabDY = rawY - py;
        // 起拖立刻切"被拎着"（之前延迟 120ms，手感上就是"不播"）
        main.removeCallbacks(dragAct);
        main.post(dragAct);
        // 拖动期间保持当前帧（setAnimating(false)），松手后再切动作。
        if (pet != null) pet.setAnimating(false);
    }

    @Override
    public void onDragMove(float rawX, float rawY) {
        px = clampf(rawX - grabDX, 0, Math.max(0, screenW - petW));
        py = clampf(rawY - grabDY, 0, Math.max(0, screenH - petH));
        applyPos();
    }

    @Override
    public void onDragEnd(float vxPxPerSec, float vyPxPerSec) {
        dragging = false;
        main.removeCallbacks(dragAct);
        if (pet != null) pet.setAnimating(true);
        savePos();
        float speed = (float) Math.hypot(vxPxPerSec, vyPxPerSec);
        if (speed >= DEAD_SPEED) {
            // 只有真的被甩出去才切"被拎着"姿势；轻轻放下就直接回待机，省一次解码
            if (anim.drag != null) playAct(anim.drag, true, null);
            playback.toFly();
            vx = vxPxPerSec;
            vy = vyPxPerSec;
            flying = true;
            flightStartAt = System.currentTimeMillis();
            playback.lockFor(4000L);
            main.removeCallbacks(physics);
            lastStepAt = SystemClock.uptimeMillis();
            main.post(physics);
        } else {
            playBase();
            scheduleIdle(nextIdleDelay());
        }
    }

    // ==========================================================================
    // 抛掷物理（与 dsh-pet 同参数）
    // ==========================================================================

    private final Physics.State flight = new Physics.State(0f, 0f, 0f, 0f);
    private final Physics.Step stepOut = new Physics.Step();

    /** 起拖 120ms 后切"被拎着"；松手时取消。 */
    private final Runnable dragAct = () -> {
        if (dragging && !flying && anim != null && anim.drag != null) {
            if (pet != null) pet.setAnimating(true);   // 起拖时被暂停了，不恢复会"在播但不动"
            playAct(anim.drag, true, null);
        }
    };

    private final Runnable physics = new Runnable() {
        @Override
        public void run() {
            if (!flying) return;
            long now = SystemClock.uptimeMillis();
            float dt = (now - lastStepAt) / 1000f;
            lastStepAt = now;

            float maxX = Math.max(0, screenW - petW);
            float maxY = Math.max(0, screenH - petH);

            // 物理本身在 Physics（纯函数、有 JVM 单测），这里只负责搬到窗口上
            flight.x = px;
            flight.y = py;
            flight.vx = vx;
            flight.vy = vy;
            Physics.Step st = Physics.step(flight, dt, maxX, maxY, stepOut);
            px = flight.x;
            py = flight.y;
            vx = flight.vx;
            vy = flight.vy;

            // 安全阀：极端用力甩出去时横向速度衰到静止阈值可能要 15 秒以上（dsh-pet 同参数
            // 也是这个表现）。12 秒后把横向速度清掉，让它自然掉到地面收工，别一直弹。
            if (System.currentTimeMillis() - flightStartAt > 6000L && Math.abs(vx) > 1f) {
                vx = 0f;
                flight.vx = 0f;
            }

            applyPos();
            if (st.landed && pet != null) pet.squashLanding(st.impact);

            if (!st.alive) {
                flying = false;
                vx = 0f;
                vy = 0f;
                savePos();
                playBase();
                scheduleIdle(nextIdleDelay());
                return;
            }
            main.postDelayed(this, STEP_MS);
        }
    };

    private void applyPos() {
        if (petLp == null || pet == null) return;
        petLp.x = Math.round(px);
        petLp.y = Math.round(py);
        try {
            wm.updateViewLayout(petView, petLp);
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 窗口可能已被系统回收
        }
        if (panelShown) placePanel();
        placeMenu();
    }

    private void savePos() {
        // 存锚点而不是窗口左上角：下次启动先播 idle，按锚点反算窗口位置，人物不会跳
        Prefs.pet(this).edit()
                .putInt(KEY_X, Math.round(anchorScreenX()))
                .putInt(KEY_Y, Math.round(anchorScreenY()))
                .apply();
    }

    // ==========================================================================
    // 动作播放 + 待机随机链
    // ==========================================================================

    private void playAct(Anim.Act a, boolean loop, Runnable end) {
        if (moveKind != null && !inMoveAct) endMove();   // 播别的动作就停下，别边播边平移
        if (a == null || pet == null) return;
        showAct(a, loop, end);
    }

    private int widthFor(Anim.Act a) {
        int h = a == null || a.h <= 0 ? 360 : a.h;
        return Math.max(1, Math.round(petH * (a.w / (float) h)));
    }

    private float scaleFor(Anim.Act a) {
        int h = a == null || a.h <= 0 ? 360 : a.h;
        return petH / (float) h;
    }

    private Anim.Act shownAct() {
        return curAct != null ? curAct : anim.idle;
    }

    /** 人物脚底锚点（屏幕坐标）。 */
    private float anchorScreenX() {
        Anim.Act a = shownAct();
        return a == null ? px + petW / 2f : px + a.ax * scaleFor(a);
    }

    private float anchorScreenY() {
        Anim.Act a = shownAct();
        return a == null ? py + petH : py + a.ay * scaleFor(a);
    }

    /** 反算窗口位置：让当前动作的锚点落在 (ax, ay)。 */
    private void setAnchor(float ax, float ay) {
        anchorX = ax;
        anchorY = ay;
        Anim.Act a = shownAct();
        if (a == null) {
            px = ax - petW / 2f;
            py = ay - petH;
            return;
        }
        float s = scaleFor(a);
        px = ax - a.ax * s;
        py = ay - a.ay * s;
    }

    /**
     * 统一入口：切动作、按锚点钉住人物、同步窗口宽度、刷新点击掩码。
     * 移动/拖拽/抛掷期间不补偿（窗口本来就在动）。
     */
    private void showAct(Anim.Act a, boolean loop, Runnable end) {
        if (a == null || pet == null) return;
        boolean pin = moveKind == null && !dragging && !flying;
        float ax = anchorScreenX();
        float ay = anchorScreenY();
        curAct = a;
        int target = widthFor(a);
        if (target != petW) {
            petW = target;
            if (petLp != null) petLp.width = petW;
        }
        if (pin) setAnchor(ax, ay);
        applyPos();
        updateHitRegion();
        pet.play(a.dir, loop, end);
    }

    /** 点击区域：只保留人物 alpha 覆盖的格子，其余触摸透传给下面的应用。 */
    private void updateHitRegion() {
        Anim.Act a = shownAct();
        if (hitMask == null || a == null) {
            hitRegion.set(0, 0, Math.max(1, petW), Math.max(1, petH));
        } else {
            hitMask.regionInto(hitRegion, a.dir, Math.max(1, petW), Math.max(1, petH));
        }
        if (petView != null) petView.requestLayout();
    }

    /**
     * 把窗口的可触摸区域收缩到人物轮廓。
     *
     * 官方 SDK 没有公开「按区域接收触摸」的接口，只能走 ViewTreeObserver 的内部 insets 回调
     * （OnComputeInternalInsetsListener + TOUCHABLE_INSETS_REGION）。反射失败时静默退回整窗可点，
     * 不影响其他功能。
     */
    private void installHitRegion(final VideoPetView v) {
        try {
            final Class<?> listenerCls =
                    Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerCls.getClassLoader(), new Class<?>[]{listenerCls},
                    (p, method, args) -> {
                        if (args != null && args.length == 1 && method.getName().contains("Insets")) {
                            applyTouchableRegion(args[0]);
                        }
                        return null;
                    });
            java.lang.reflect.Method add = android.view.ViewTreeObserver.class
                    .getMethod("addOnComputeInternalInsetsListener", listenerCls);
            add.invoke(v.getViewTreeObserver(), proxy);
            Bridge.diag("hit", "region installed");
        } catch (Throwable t) {
            Bridge.diag("hit", "region unavailable: " + t.getClass().getSimpleName());
            Log.ignored("PetService.hit", t);
        }
    }

    /** info.touchableRegion.set(region) 之后才能把 insets 切成 REGION(=3)。 */
    private void applyTouchableRegion(Object info) {
        if (hitRegionBlocked) return;
        try {
            Class<?> c = info.getClass();
            java.lang.reflect.Field f = c.getField("touchableRegion");
            android.graphics.Region r = (android.graphics.Region) f.get(info);
            if (r == null) return;
            r.set(hitRegion);
            // 顺序很重要：字段拿不到时绝不能设置 REGION，否则窗口会变成完全不可点
            c.getMethod("setTouchableInsets", int.class).invoke(info, 3);
        } catch (Throwable t) {
            hitRegionBlocked = true;
            Bridge.diag("hit", "region blocked: " + t.getClass().getSimpleName());
            Log.ignored("PetService.hit.apply", t);
        }
    }

    /** 状态"还算新鲜"的窗口（桥自己 5 分钟才转 idle，90 秒容忍偶发跳过）。 */
    private static final long STATUS_FRESH_MS = 90_000L;

    private static boolean isBusyName(String name) {
        return "thinking".equals(name) || "working".equals(name) || "result".equals(name);
    }

    private boolean agentBusyNow() {
        return ("thinking".equals(lastStatusName) || "working".equals(lastStatusName))
                && System.currentTimeMillis() - lastStatusAt < STATUS_FRESH_MS;
    }

    private void playIdle() {
        if (destroyed || !screenOn || petHidden) return;
        playback.playIdle();
    }

    private void playBase() {
        if (destroyed || !screenOn || petHidden) return;   // 熄屏/收起/销毁后不再起播
        playback.playBase();
    }

    private void playOnce(Anim.Act a) {
        if (a == null || destroyed || !screenOn || petHidden) return;
        if (moveKind != null) endMove();
        playback.playOnce(a);
    }

    private void playChain(Anim.Act a) {
        playback.playChain(a);
    }

    private void playClickResponse() {
        // 单击只播"点击回应"池里的动作（曾经有 15% 概率抽任意日常动作，容易被当成 bug）
        if (anim.clickPool.isEmpty()) {
            playIdle();
            return;
        }
        playOnce(anim.clickPool.get(rnd.nextInt(anim.clickPool.size())));
    }

    private long nextIdleDelay() {
        return 6000L + rnd.nextInt(8000);
    }

    /** 服务销毁后 io 池已关闭：在途回调再提交任务时不要炸到主线程。 */
    private void ioRun(Runnable r) {
        try {
            io.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.ignored("PetService.io", e);
        }
    }
    private void scheduleIdle(long delay) {
        if (destroyed) return;
        main.removeCallbacks(idleTick);
        main.postDelayed(idleTick, delay);
    }

    /** 下一次自主移动的最早时间（毫秒时间戳）。 */
    private long nextMoveAt;
    private String moveKind;
    /** 移动方向向量，取值 -1/0/1；横向 (dx,0)、纵向 (0,dy)、对角 (dx,dy)。 */
    private int moveDx;
    private int moveDy;
    /** 当前移动速度（px/s）：慢速走纵向漂浮素材，快速走横向走路素材。 */
    private float moveSpeedPx;
    private long moveLastTick;
    /** 自律移动：允许中途随机换向。 */
    private boolean moveAutoTurn;
    private long moveTurnAt;
    private long moveTurnGap;
    private static final float SPEED_SLOW_DP = 15f;
    private static final float SPEED_FAST_DP = 45f;
    private long moveStartAt;
    private long moveDuration;

    /** 自主移动：横向（螃蟹走路）/ 纵向（漂浮踏步）；斜向等素材。 */
    /** 调试入口：slow=纵向漂浮、fast=横向走路、diagonal=对角（复用走路素材）。 */
    /**
     * 进入一种移动：设状态、播素材、开位置循环。
     *
     * @param kind    horizontal / vertical / diagonal
     * @param dx      -1/0/1，横向分量
     * @param dy      -1/0/1，纵向分量
     * @param speedPx 像素/秒（慢速 20dp/s、快速 55dp/s）
     */
    private void beginMove(String kind, int dx, int dy, float speedPx, long durationMs,
                           boolean floatAsset, boolean autoTurn) {
        Anim.Act a = floatAsset ? anim.floatAct : anim.walk;
        if (a == null) {
            bubble(getString(R.string.pet_bubble_no_asset));
            return;
        }
        moveKind = kind;
        moveDx = Integer.signum(dx);
        moveDy = Integer.signum(dy);
        moveSpeedPx = speedPx;
        moveStartAt = SystemClock.uptimeMillis();
        moveLastTick = moveStartAt;
        moveDuration = durationMs;
        moveAutoTurn = autoTurn;
        moveTurnAt = SystemClock.uptimeMillis();
        moveTurnGap = 2500L + rnd.nextInt(2500);
        playback.toMoving();
        Bridge.diag("move", "start " + kind + " d=" + moveDx + "," + moveDy
                + " asset=" + (floatAsset ? "float" : "walk"));
        // 先摆朝向再起播：反了的话第一帧还是正像，"镜像时闪一下"就是这么来的
        applyFacing(moveDx);
        inMoveAct = true;
        playAct(a, true, null);
        inMoveAct = false;
        main.removeCallbacks(moveStep);
        main.post(moveStep);
    }

    /** 素材朝左：dirX>0（向右）时水平镜像。横向与斜向都适用。 */
    private void applyFacing(int dx) {
        if (petView == null) return;
        boolean horizontalish = "horizontal".equals(moveKind) || "diagonal".equals(moveKind);
        setFlip(horizontalish && dx > 0);
    }

    /**
     * 水平镜像用着色器翻转，不用 View.setScaleX。
     *
     * SurfaceView 的 surface 是独立图层，旧实现 setScaleX(-1) 在真机上表现成
     * "内容没翻 + 被裁掉一截"（1.0.59 修）。着色器翻的是采样坐标，窗口和图层都不动。
     */
    private boolean lastFlip;

    private void setFlip(boolean flip) {
        if (!(petView instanceof VideoPetView)) return;
        ((VideoPetView) petView).setFlipX(flip);
        // 只在朝向真的变化时记一条：每帧都写会把 200 行日志环刷满，别的标签全被挤掉
        if (lastFlip != flip) {
            lastFlip = flip;
            Bridge.diag("flip", (flip ? "mirror" : "normal") + " px=" + Math.round(px)
                    + " w=" + petW + " sw=" + screenW);
        }
    }

    private void startMove() {
        if (!playback.isIdle()) return;
        boolean canH = anim.walk != null && petW < screenW - 20;
        boolean canV = anim.floatAct != null && petH < screenH - 20;
        boolean canD = anim.diagonal != null && petW < screenW - 20 && petH < screenH - 20;
        if (!canH && !canV) return;

        int dx = 0, dy = 0;
        boolean fast;
        boolean floatAsset;
        String kind;
        int roll = rnd.nextInt(100);
        if (canD && roll < 40) {                      // 对角 40%（四个方向，均分后每个 10%）
            kind = "diagonal";
            fast = rnd.nextBoolean();
            floatAsset = !fast;                       // 慢=漂浮素材，快=走路素材
            dx = rnd.nextBoolean() ? 1 : -1;
            dy = rnd.nextBoolean() ? 1 : -1;
        } else if (canH && (!canV || roll >= 70)) {   // 横向 30%
            kind = "horizontal";
            fast = true;
            floatAsset = false;
            dx = rnd.nextBoolean() ? 1 : -1;
        } else {                                      // 纵向 30%
            kind = "vertical";
            fast = false;
            floatAsset = true;
            dy = rnd.nextBoolean() ? 1 : -1;
        }
        // 走久一点：原来 5~10 秒太短
        long dur = fast ? 12000L + rnd.nextInt(8000) : 10000L + rnd.nextInt(6000);
        beginMove(kind, dx, dy, dp(fast ? SPEED_FAST_DP : SPEED_SLOW_DP), dur, floatAsset, true);
    }

    private final Runnable moveStep = new Runnable() {
        @Override
        public void run() {
            if (moveKind == null) {
                main.removeCallbacks(this);
                return;
            }
            if (dragging || flying || petHidden || !screenOn || pet == null) {
                endMove();
                return;
            }
            long t = SystemClock.uptimeMillis() - moveStartAt;
            if (t > moveDuration) {
                endMove();
                return;
            }
            // 自律移动允许中途换向：每 2.5~5 秒 60% 概率翻一次
            long nowTurn = SystemClock.uptimeMillis();
            if (moveAutoTurn && nowTurn - moveTurnAt > moveTurnGap) {
                moveTurnAt = nowTurn;
                moveTurnGap = 2500L + rnd.nextInt(2500);
                if (rnd.nextInt(100) < 60) {
                    if (moveDx != 0) moveDx = -moveDx;
                    if (moveDy != 0) moveDy = -moveDy;
                    if (moveDx != 0) setFlip(moveDx > 0);
                }
            }
            boolean hitX = px <= 0 || px >= screenW - petW;
            boolean hitY = py <= 0 || py >= screenH - petH;
            if (moveDx != 0 && hitX) moveDx = -moveDx;
            if (moveDy != 0 && hitY) moveDy = -moveDy;
            if (moveDx != 0) setFlip(moveDx > 0);      // 素材朝左：向右走镜像
            long nowTick = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (nowTick - moveLastTick) / 1000f);
            moveLastTick = nowTick;
            float step = moveSpeedPx * dt;
            if (moveDx != 0) px = clampf(px + moveDx * step, 0, Math.max(0, screenW - petW));
            if (moveDy != 0) py = clampf(py + moveDy * step, 0, Math.max(0, screenH - petH));
            applyPos();
            if (moveTickLog++ % 60 == 0) {
                Bridge.diag("move", moveKind + " d=" + moveDx + "," + moveDy + " pos=" + Math.round(px) + "," + Math.round(py));
            }
            main.postDelayed(this, 16L);
        }
    };

    private int moveTickLog;

    /**
     * 切换移动时用：清掉移动状态但**不播待机、不重置朝向**。
     *
     * 原来的切换流程是 endMove() → playIdle() → beginMove()，中间那一帧正面待机就是
     * 用户看到的"正像素材闪回"。
     */
    private void stopMoveSilently() {
        if (moveKind == null) return;
        moveKind = null;
        moveAutoTurn = false;
        main.removeCallbacks(moveStep);
        playback.toIdle("switch");
    }

    private void endMove() {
        if (moveKind == null) return;
        moveKind = null;              // 先清状态，否则 playIdle→playAct→endMove 递归爆栈
        moveAutoTurn = false;
        main.removeCallbacks(moveStep);
        setFlip(false);

        savePos();
        playback.toIdle("endMove");
        playBase();
        // 移动不要过于频繁：至少隔 60 秒，最多 150 秒
        nextMoveAt = System.currentTimeMillis() + 90000L + rnd.nextInt(90000);   // 静止 90~180 秒
        scheduleIdle(nextIdleDelay());
    }

    private final Runnable idleTick = new Runnable() {
        @Override
        public void run() {
            if (!screenOn || petHidden) return;   // 熄屏/被收起时不排下一次
            // 一次性动作的完成回调可能丢（surface 重建 / 服务暂停）：超时兜底回底态
            if (playback.isOneShot() && System.currentTimeMillis() > playback.busyUntil()) {
                playback.toIdle("oneshot-timeout");
                playBase();
                scheduleIdle(600L);
                return;
            }
            if (!playback.isIdle()) {             // 有别的状态在跑，别抢
                scheduleIdle(1000L);
                return;
            }
            if (dragging || flying) {
                scheduleIdle(1500L);
                return;
            }
            long now = System.currentTimeMillis();
            // DSH 正在思考/干活：动画归状态联动管，随机链别去抢
            boolean agentBusy = agentBusyNow();
            if (agentBusy) {
                scheduleIdle(2000L);
                return;
            }
            if (now < playback.busyUntil()) {
                scheduleIdle(1200L);
                return;
            }
            if (!isNight() && System.currentTimeMillis() >= nextMoveAt) {
                startMove();
                if (moveKind != null) return;    // 进入移动状态，由 moveStep 接管
            }
            if (isNight()) {
                // 深夜从「睡觉/打盹」池里随机（含抱着枕头的三段），整段循环 15~25 秒再换，
                // 而不是只反复播第一段 —— 那是"只会犯困"的观感来源。
                java.util.List<Anim.Act> bed = anim.sleepPool.isEmpty()
                        ? anim.idlePool : anim.sleepPool;
                if (!bed.isEmpty()) {
                    Anim.Act sleep = bed.get(rnd.nextInt(bed.size()));
                    playAct(sleep, true, null);
                    playback.lockFor(15000L + rnd.nextInt(10000));
                }
                scheduleIdle(9000L + rnd.nextInt(8000));
                return;
            }
            if (anim.idlePool.size() > 1 && rnd.nextInt(100) < 60) {
                List<Anim.Act> pool = new ArrayList<>(anim.idlePool);
                playChain(pool.get(rnd.nextInt(pool.size())));
                return;   // playChain 自己安排下一次
            }
            playIdle();
            scheduleIdle(nextIdleDelay());
        }
    };

    private boolean isNight() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 23 || h < 6;
    }

    private final Runnable greet = new Runnable() {
        @Override
        public void run() {
            if (!screenOn) {          // 熄屏时不冒气泡，等亮屏再说
                main.postDelayed(this, 60000L);
                return;
            }
            String today = Bridge.day();
            if (today.equals(greetedDay)) return;
            greetedDay = today;
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (h < 5) {
                bubble(getString(R.string.pet_bubble_night));
            } else if (h < 11) {
                bubble(getString(R.string.pet_bubble_morning));
            } else if (h >= 22) {
                bubble(getString(R.string.pet_bubble_late_night));
            }
        }
    };

    // ==========================================================================
    // 状态联动（读 DSH 的 whalepet-bridge）
    // ==========================================================================


    // ---------------- 审批：把 DSH 的"要执行某个操作"变成桌宠里的允许/拒绝 ----------------

    /** 审批面板（展示/决定/30 秒提醒都在里面）。 */
    private ApprovalPanel approval;
    private ActionsPanel actionsPanel;
    /** DSH 正在等用户回应（提问/计划），用来做气泡提醒与状态联动。 */
    private boolean needUserActive;
    private String needUserAsk = "";
    private long needUserAt;
    private long needUserRemindAt;



    /** 气泡放不下长问题：压成一行并截断。 */
    private static String clipAsk(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').trim();
        return one.length() <= 60 ? one : one.substring(0, 60) + "…";
    }

    /**
     * 临时隐藏（先躲起来）：记下"在哪个应用里躲的"，前台换成另一个真实应用就回来。
     * 没有超时——60 秒那个版本会让它在用户还在看的时候自己冒出来（1.0.54 已删）。
     *
     * 不再做"按应用自动收起"——那套需要维护名单、容易误判，已被这个手动功能取代。
     */
    private String tempHidePkg;
    private long tempHideSince;
    /** 是否正被"无障碍自动收起"藏着（和手动临时隐藏分开记账）。 */
    private boolean a11yHidden;
    /** 连续"轻踢"次数：踢两次还不出帧才重建窗口。 */
    private int nudgeCount;

    /** 无障碍判定：前台是游戏或全屏就收起；离开后自动回来。 */
    private void a11yCheck() {
            try {
                boolean on = Prefs.pet(this)
                        .getBoolean(KEY_A11Y_HIDE, true) && PetAccessibility.isConnected();
                if (on) {
                    boolean game = PetAccessibility.foregroundIsGame(PetService.this);
                    boolean full = PetAccessibility.isFullscreen();
                    // 通用规则：横屏 + 有媒体在播 = 大概率全屏视频（不看包名）
                    boolean videoFull = PetAccessibility.isLandscape()
                            && PetAccessibility.isMediaActive();
                    boolean hide = game || full || videoFull;
                    long now = SystemClock.uptimeMillis();
                    String sig = PetAccessibility.isConnected() + "|" + PetAccessibility.foregroundPkg()
                            + "|" + game + "|" + full + "|" + videoFull + "|" + hide + "|" + a11yHidden;
                    if (!sig.equals(lastA11ySig)) {
                        lastA11ySig = sig;
                        Bridge.diag("a11y", "change conn=" + PetAccessibility.isConnected()
                                + " pkg=" + PetAccessibility.foregroundPkg()
                                + " game=" + game + " full=" + full
                                + " video=" + videoFull + " land=" + PetAccessibility.isLandscape()
                                + " hide=" + hide
                                + " hidden=" + a11yHidden);
                    }
                    if (hide && !a11yHidden && tempHidePkg == null) {
                        a11yHidden = true;
                        setPetVisible(false);
                        Bridge.diag("a11y", "hide " + PetAccessibility.foregroundPkg());
                    } else if (!hide && a11yHidden) {
                        a11yHidden = false;
                        setPetVisible(true);
                        Bridge.diag("a11y", "show");
                    }
                } else if (a11yHidden) {
                    a11yHidden = false;
                    setPetVisible(true);
                }
            } catch (Throwable t) { Log.ignored("PetService", t);
                // 无障碍只是加减分项
            }
    }


    /** 临时隐藏的恢复判定。 */
    private void tempHideCheck() {
        final String pkg = tempHidePkg;
        if (pkg == null || !screenOn) return;
        if (SystemClock.uptimeMillis() - tempHideSince < 1200L) return;
        ioRun(() -> {
            String fg = PetAccessibility.foregroundPkg();
            if (fg == null || fg.isEmpty()) fg = Foreground.current(PetService.this);
            boolean valid = fg != null && !fg.isEmpty()
                    && !fg.equals(getPackageName())
                    && !fg.equals("com.android.systemui");
            boolean changed = valid && !fg.equals(pkg);
            final String f = fg;
            if (!changed) return;
            main.post(() -> {
                if (tempHidePkg != null) {
                    tempHidePkg = null;
                    setPetVisible(true);
                    Bridge.diag("tempHide", "restored fg=" + f + " from=" + pkg);
                }
            });
        });
    }

    /** 环境巡检：把"无障碍判定"和"临时隐藏恢复"合并到同一个 3 秒节拍。 */
    /** 交互请求（审批）30 秒未被回应且用户不在 DSH 界面 → 气泡提醒并重新弹面板。 */
    private final Runnable approvalWatch = new Runnable() {
        @Override
        public void run() {
            try {
                if (needUserActive
                        && System.currentTimeMillis() - needUserAt > 30000L
                        && System.currentTimeMillis() - needUserRemindAt > 30000L) {
                    needUserRemindAt = System.currentTimeMillis();
                    bubble(needUserAsk.isEmpty() ? getString(R.string.pet_bubble_need_user_short)
                            : getString(R.string.pet_bubble_need_user, clipAsk(needUserAsk)));
                    Bridge.diag("needUser", "reminded after 30s");
                }
                if (approval != null) approval.tick();
            } catch (Throwable t) { Log.ignored("PetService", t);
                // 提醒失败不影响主流程
            }
            main.postDelayed(this, 5000L);
        }
    };

    private final Runnable envTick = new Runnable() {
        @Override
        public void run() {
            try {
                a11yCheck();
            } catch (Throwable t) { Log.ignored("PetService", t);
                // 忽略
            }
            try {
                tempHideCheck();
            } catch (Throwable t) { Log.ignored("PetService", t);
                // 忽略
            }
            main.postDelayed(this, 6000L);   // 事件驱动为主，这里只做兜底修正
        }
    };

    private long lastMemLog;

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            // 状态通道自己决定走 SSE 还是兜底查询
            if (statusClient != null) statusClient.poll();
            ensurePetAttached();
            // 画面停滞看护：窗口还在但 3.5 秒没有新帧（输入法/横幅/看图器插入后偶发）
            // → 直接重建一次窗口（只重播片段治不好"图层被摘"）
            if (!petHidden && !dragging && !panelShown && pet != null
                    && pet.sinceLastFrame() > 6000L) {
                if (nudgeCount++ < 2 && pet.nudge()) {
                    Bridge.diag("watch", "nudged #" + nudgeCount);
                } else {
                    Bridge.diag("watch", "nudge failed, reattach");
                    nudgeCount = 0;
                    reattachPet();
                }
            } else {
                nudgeCount = 0;
            }
            // 自适应：空闲时没必要 3 秒一次（本机回环很便宜，但省一点是一点）
            // 每分钟报一次内存：没有设备 shell，只能靠 App 自己汇报
            if (System.currentTimeMillis() - lastMemLog > 60000L) {
                lastMemLog = System.currentTimeMillis();
                try {
                    long pssKb = android.os.Debug.getPss();
                    Runtime rt = Runtime.getRuntime();
                    long javaMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    Bridge.diag("mem", "pss=" + (pssKb / 1024) + "MB java=" + javaMb + "MB");
                } catch (Throwable t) {
                    Log.ignored("PetService.mem", t);
                }
            }
            boolean idle = lastStatusName.isEmpty() || "idle".equals(lastStatusName);
            main.postDelayed(this, idle ? 12000L : 4000L);
        }
    };

    /**
     * 窗口看护：服务活着、也没被"全屏收起"，但悬浮窗已经不在了（被系统回收、
     * 或者上次 addView 失败）→ 重新挂上。真机反馈的"偶尔消失、要重启小人才回来"
     * 就是这种情况：服务没死，窗口没了。
     */
    /** 重建窗口（比"重播片段"更彻底：SurfaceView 的图层被摘掉时只能重挂）。 */
    private long lastReattachAt;

    private void reattachPet() {
        long now = SystemClock.uptimeMillis();
        if (now - lastReattachAt < 8000L) return;   // 冷却，避免每轮都抖
        lastReattachAt = now;
        Bridge.diag("reattach", "no frame for " + pet.sinceLastFrame() + "ms");
        try {
            remove(petView);
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 已经不在窗口上
        }
        main.postDelayed(() -> {
            try {
                wm.addView(petView, petLp);
                playBase();
                scheduleIdle(800L);
            } catch (Throwable t) { Log.ignored("PetService", t);
                // 下轮再试
            }
        }, 180L);
    }

    private void ensurePetAttached() {
        if (pet == null || petHidden || petView.getWindowToken() != null) return;
        try {
            wm.addView(petView, petLp);
            playBase();
            scheduleIdle(1200L);
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 还挂不上就下一轮再试
        }
    }

    /** 当前正在播的状态键（thinking/working/result），用于六档切换。 */
    private String statusPlaying = "";

    private boolean balanceChecking = false;

    /** 碎碎念：每 4~8 分钟一句 + 说话动画。 */
    private final Runnable whisperTick = new Runnable() {
        @Override public void run() {
            try { doWhisper(false); } catch (Throwable t) { Log.ignored("whisper", t); }
            main.postDelayed(this, 4 * 60_000L + rnd.nextInt(4 * 60_000));
        }
    };

    private void doWhisper(boolean manual) {
        if (anim == null) return;
        if (!manual && (pet == null || petHidden || !screenOn
                || playback.isDrag() || playback.isFly() || playback.isOneShot())) return;
        String[] lines = getResources().getStringArray(R.array.whisper_lines);
        if (lines.length == 0) return;
        String line = lines[rnd.nextInt(lines.length)];
        bubbleLong(line, manual ? 10000L : 8000L);
        Anim.Act a = anim.randomWhisper();
        if (a != null) playOnce(a);
        Bridge.diag("whisper", (manual ? "manual " : "") + line);
    }

    /** 余额事件：每 15 分钟查一次，分档变化时播动画 + 气泡。 */
    private final Runnable balanceTick = new Runnable() {
        @Override public void run() {
            try { checkBalance(); } catch (Throwable t) { Log.ignored("balanceEvent", t); }
            main.postDelayed(this, 15 * 60_000L);
        }
    };

    private void checkBalance() {
        if (balanceChecking) return;
        balanceChecking = true;
        final String key = AccountSession.apiKey(this);
        final String acct = Prefs.pet(this).getString("acctBal", "");
        ioRun(() -> {
            double bal = Double.NaN;
            try {
                if (!key.isEmpty()) {
                    BalanceClient.Result r = BalanceClient.fetch(key, 6000);
                    if (r.ok && !r.items.isEmpty()) {
                        BalanceClient.Item it = r.items.get(0);
                        for (BalanceClient.Item x : r.items) {
                            if ("CNY".equalsIgnoreCase(x.currency)) { it = x; break; }
                        }
                        bal = Double.parseDouble(it.total);
                    } else if (r.ok) {
                        bal = Double.NaN;
                    }
                } else if (!acct.isEmpty()) {
                    bal = Double.parseDouble(acct);
                }
            } catch (Throwable t) { Log.ignored("balanceEvent", t); }
            final double v = bal;
            main.post(() -> {
                balanceChecking = false;
                onBalance(v);
            });
        });
    }

    private void onBalance(double bal) {
        if (Double.isNaN(bal)) return;
        // 按 ¥20 满额折算已用比例，分 6 档（与上游一致）
        double used = 1 - bal / 20.0;
        if (used < 0) used = 0;
        if (used > 1) used = 1;
        int tier = (int) Math.floor(used * 6);
        if (tier > 5) tier = 5;
        int last = Prefs.pet(this).getInt("balTier", -1);
        boolean changed = tier != last;
        if (changed) Prefs.pet(this).edit().putInt("balTier", tier).apply();
        if (changed) {
            Anim.Act a = anim.balanceAct(tier);
            if (a != null) playOnce(a);
            bubbleLong(getString(R.string.pet_bubble_balance, Bridge.fmt(bal)), 10000L);
        }
        Bridge.diag("balanceEvent", "bal=" + Bridge.fmt(bal) + " tier=" + tier + " last=" + last);
    }

    private void onStatus(Bridge.Status s) {
        // 一次性动作优先播完；拖动/抛掷期间不切动作（松手后由 playBase 收尾）
        if ((playback.isOneShot() && !s.needUser)
                || playback.isDrag() || playback.isFly()) return;
        String key = s.key();
        if (key.equals(lastStatusKey)) return;   // 状态没变：不写日志（否则 0.3 秒一条，日志环 200 行只够 1 分钟）
        lastStatusKey = key;
        Bridge.diag("status", "key=" + key + " mode=" + playback.mode() + " move=" + moveKind);
        lastStatusName = s.status;

        // 走路不受无关状态打扰（以前每个 tick 都 endMove，一走就断）；
        // 只有进入干活、或等待/完成/出错这种有意义的变化才让它停下来。
        if (playback.isMoving()) {
            boolean busying = "thinking".equals(s.status) || "working".equals(s.status)
                    || "result".equals(s.status);
            boolean meaningful = busying || "waiting".equals(s.status)
                    || "done".equals(s.status) || "error".equals(s.status);
            if (!meaningful) return;
            endMove();
        }

        if (needUserActive && !s.needUser) {
            needUserActive = false;
            needUserAsk = "";
            hideBubble();
            Bridge.diag("needUser", "cleared status=" + s.status);
            if (playback.isStatus()) {
                Anim.Act a = anim.statusAct(s.status);
                if (a != null) playback.playStatusLoop(a);
            }
        }

        boolean busy = "thinking".equals(s.status) || "working".equals(s.status)
                || "result".equals(s.status);
        if (busy) {
            String prevBusy = lastBusyName;
            lastBusyName = s.status;
            // 「开始工作」：一轮任务从非忙进入忙时先播一次，再交给状态循环
            if (!isBusyName(prevBusy) && playback.isIdle() && startWork != null) {
                statusPlaying = s.status;
                playOnce(startWork);
                bubble(getString(R.string.pet_bubble_working));
                return;
            }
            // 「思考」与「干活」在真实事件流里会频繁互切（每次工具调用都变一次）。
            // 以前每变一次就重播动作 —— 这就是"动作播一半被打断"的根因。
            // 现在只在"从非忙进入忙"、且当前没有一次性动作在播时，才切状态动作。
            // 六档里 result 与 working 会频繁互切：状态循环之间也允许切换，
            // 但只在真的换了档时重播，避免每次事件都打断。
            if (playback.isIdle() || (playback.isStatus() && !s.status.equals(statusPlaying))) {
                Anim.Act a = anim.statusAct(s.status);
                if (a != null) {
                    playback.playStatusLoop(a);
                    statusPlaying = s.status;
                    if ("thinking".equals(s.status)) {
                        bubble(getString(R.string.pet_bubble_thinking));
                    } else if ("working".equals(s.status)) {
                        bubble(getString(R.string.pet_bubble_working));
                    }
                }
            }
            return;
        }

        boolean wasBusy = isBusyName(lastBusyName);
        lastBusyName = s.status;
        statusPlaying = "";
        if (!"waiting".equals(s.status) && !"done".equals(s.status) && !"error".equals(s.status)) {
            playback.unlock();                    // 离开干活态就解锁，否则 idleTick 永远被挡住
        }
        if (wasBusy && !"waiting".equals(s.status) && !"done".equals(s.status)
                && !"error".equals(s.status)) {
            Anim.Act a = anim.interrupt;              // 被打断：工作被打扰
            if (a != null) playOnce(a);
        }
        switch (s.status) {
            case "waiting": {
                Anim.Act a = anim.statusAct("waiting");
                if (s.needUser) {
                    // 人在等你回应：一直播等待动作，别被待机链接管
                    if (a != null) playback.playStatusLoop(a);
                    needUserActive = true;
                    needUserAsk = s.ask == null ? "" : s.ask;
                    needUserAt = System.currentTimeMillis();
                    needUserRemindAt = 0L;
                    // 常驻：不自动消失，回应后由 clear 分支收起；否则用户切走再回来就看不到了
                    bubbleLong(needUserAsk.isEmpty() ? getString(R.string.pet_bubble_need_user_short)
                            : getString(R.string.pet_bubble_need_user, clipAsk(needUserAsk)), 0L);
                    Bridge.diag("needUser", "show ask=" + needUserAsk);
                } else {
                    // 瞬态循环，8 秒后由待机链自然接管
                    playback.playTransient(a, 8000L);
                    bubble(getString(R.string.pet_bubble_waiting));
                }
                break;
            }
            case "error": {
                playback.unlock();
                Anim.Act a = anim.statusAct("error");
                if (a != null) playOnce(a);
                bubble(getString(R.string.pet_bubble_error));
                break;
            }
            case "done": {
                playback.unlock();
                Anim.Act a = anim.statusAct("done");
                if (a != null) playOnce(a);
                final String line = s.lastDone == null || s.lastDone.isEmpty()
                        ? getString(R.string.pet_bubble_done)
                        : getString(R.string.pet_bubble_done_with_time) + s.lastDone;
                double today = s.todayCost;          // poll 里已带今日花费
                java.util.function.DoubleConsumer show = v ->
                        bubble(line + (Double.isNaN(v) ? ""
                                : getString(R.string.pet_bubble_today_cost, Bridge.fmt(v))));
                if (!Double.isNaN(today)) {
                    show.accept(today);
                } else {
                    ioRun(() -> {
                        final Bridge.Usage u = Bridge.usage(2000);
                        main.post(() -> show.accept(u.ok ? u.todayCost : Double.NaN));
                    });
                }
                scheduleIdle(2500L);
                break;
            }
            default: {
                playback.unlock();
                playBase();
                scheduleIdle(1200L);
                break;
            }
        }
    }

    private LinearLayout card(String title) {
        return PanelUi.card(this, title, true);
    }

    /** 面板宽度：小屏时按屏宽收窄，绝不让面板溢出去。 */
    private int panelWidth() {
        return PanelUi.panelWidth(this, screenW, "actions".equals(panelKind));
    }

    /** 菜单宽度同理。 */

    /** 面板中段的小标题（余额 / 今日消耗 之间那条分割）。 */

    private TextView body(LinearLayout parent, String text, float sp, int color) {
        return PanelUi.body(this, parent, text, sp, color);
    }

    /** 卡片内的细分割线（比分节标题更省地方）。 */
    private void thinLine(LinearLayout parent) {
        PanelUi.thinLine(this, parent);
    }

    /** 双击面板：交给 WalletPanel 组装与取数。 */
    private void showPanel(String kind) {
        if (panelShown && "wallet".equals(panelKind)) {
            hidePanel();
            return;
        }
        hideMenu();
        hideBubble();
        panelKind = "wallet";
        walletPanel = new WalletPanel(this, main, io,
                () -> { if (panelShown) placePanel(); });
        presentPanel(walletPanel.build());
    }

    /** 面板/动作列表共用的窗口装配（独立窗口，绝不与小人重叠）。 */
    private void presentPanel(View content) {
        presentPanel(content, true);
    }

    /** @param dismissOutside false = 点面板外不收起（审批面板用，必须点了才算数） */
    private void presentPanel(View content, boolean dismissOutside) {
        Bridge.diag("panel", "show kind=" + panelKind + " dismissOutside=" + dismissOutside);
        panelWin.show(content, panelWidth(), dismissOutside ? this::hidePanel : null);
        panelShown = panelWin.isShown();
        if (panelShown) placePanel();
    }

    /** 动作点播：列出全部动作，点一个就播一次（插件右键菜单里的「动作」）。 */
    private void showActions() {
        if (panelShown && "actions".equals(panelKind)) {
            hidePanel();
            return;
        }
        hideMenu();
        hideBubble();
        panelKind = "actions";
        actionsPanel.show();
    }

    /** 放上方，放不下就翻到下方 —— 保证与小人矩形零重叠。 */
    private void placePanel() {
        if (!panelShown) return;
        panelWin.place(px, py, petW, petH, screenW, screenH, panelWidth());
    }

    private void hidePanel() {
        if (panelShown) {
            StackTraceElement[] st = new Throwable().getStackTrace();
            String from = st.length > 1 ? st[1].getMethodName() : "?";
            if (st.length > 2 && !"PetService".equals(st[2].getClassName())) {
                from += "<-" + st[2].getClassName() + "." + st[2].getMethodName();
            }
            Bridge.diag("panel", "hide kind=" + panelKind + " from=" + from);
        }
        if (walletPanel != null) {
            walletPanel.dispose();
            walletPanel = null;
        }
        if (!panelShown) return;
        panelShown = false;
        panelKind = "";
        panelWin.hide();
        // 审批挂起时，任何面板被关掉都把它弹回来（用户不能把审批"关掉不处理"）
        if (approval != null && approval.pending() != null && !petHidden) {
            main.postDelayed(() -> {
                if (!panelShown && !petHidden && approval.pending() != null) approval.reshow();
            }, 400L);
        }
    }

    private void showMenu() {
        if (menuWin == null) {
            menuWin = new MenuWindow(this, wm, main, getString(R.string.pet_menu_title));
        }
        String[][] items = {
                {getString(R.string.pet_menu_feed), "feed"},
                {getString(R.string.pet_menu_whisper), "whisper"},
                {getString(R.string.pet_menu_actions), "actions"},
                {getString(R.string.pet_menu_settings), "settings"},
                {getString(R.string.pet_menu_temp_hide), "tempHide"},
                {getString(R.string.pet_menu_help), "help"},
                {getString(R.string.pet_menu_rest), "hide"},
                {getString(R.string.pet_menu_close), "close"},
        };
        menuWin.show(items, this::onMenu, px, py, petH, screenW, screenH);
    }

    private void placeMenu() {
        if (menuWin != null && menuWin.isShown()) {
            menuWin.place(px, py, petH, screenW, screenH);
        }
    }

    private void hideMenu() {
        if (menuWin != null) menuWin.hide();
    }

    private void onMenu(String action) {
        hideMenu();
        switch (action) {
            case "whisper":
                doWhisper(true);
                break;
            case "feed": {
                Anim.Act a = anim.randomFeed();
                if (a != null) playOnce(a);
                bubble(getString(R.string.pet_bubble_feed));
                break;
            }
            case "actions":
                showActions();
                break;
            case "settings":
                openApp(null);
                break;
            case "help":
                openApp(HelpActivity.class.getName());
                break;
            case "tempHide":
                doTempHide();
                break;
            case "hide":
                Prefs.pet(this).edit()
                        .putBoolean("enabled", false).apply();
                stopSelf();
                break;
            default:
                break;
        }
    }

    private void bubble(String text) {
        bubbleWin.show(text, px, py, petW, screenW);
    }

    /** lifeMs<=0：不自动消失，等 hideBubble()（"等你回应"用）。 */
    private void bubbleLong(String text, long lifeMs) {
        bubbleWin.show(text, px, py, petW, screenW, lifeMs);
    }


    private void placeBubble() {
        bubbleWin.place(px, py, petW, screenW);
    }

    private void hideBubble() {
        bubbleWin.hide();
    }

    // ==========================================================================
    // 数据
    // ==========================================================================


    /** 余额 + 本机花费 + 分时。 */

    /** 账户今日 / 账户累计（需要登录会话；没有就保持 --）。 */




    /** wttr.in 的 j1 JSON → [今天一行, 明日一行]。解析失败就返回兜底文案。 */



    // ==========================================================================
    // 基础设施
    // ==========================================================================

    /** 三击目标的显示名（未设置时给提示）。 */
    private String targetLabel() {
        String pkg = Prefs.pet(this)
                .getString(KEY_TARGET_PKG, "");
        if (pkg == null || pkg.trim().isEmpty()) return getString(R.string.pet_target_app_fallback);
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg.trim(), 0)));
        } catch (Throwable t) {
            return getString(R.string.pet_target_app_fallback);
        }
    }

    private void launchDsha() {
        String custom = Prefs.pet(this)
                .getString(KEY_TARGET_PKG, "");
        if (custom == null || custom.trim().isEmpty()) {
            bubble(getString(R.string.pet_bubble_no_target));
            openApp(null);
            return;
        }
        String[] order = {custom, DSH_PKG, "com.deepseek.chat"};
        for (String pkg : order) {
            if (pkg == null || pkg.trim().isEmpty()) continue;
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg.trim());
                if (i == null) continue;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
                return;
            } catch (Throwable t) { Log.ignored("PetService", t);
                // 试下一个
            }
        }
        toast(getString(R.string.pet_toast_no_target_app));
    }


    private void openApp(String activity) {
        try {
            Intent i = activity == null
                    ? new Intent(this, MainActivity.class)
                    : new Intent().setClassName(this, activity);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast(getString(R.string.pet_toast_no_settings_page));
        }
    }

    private Rect bounds() {
        try {
            return wm.getCurrentWindowMetrics().getBounds();
        } catch (Throwable t) {
            return new Rect(0, 0, dp(360), dp(780));
        }
    }

    private void remove(View v) {
        if (v == null) return;
        try {
            if (v.getWindowToken() != null) wm.removeView(v);
        } catch (Throwable t) { Log.ignored("PetService", t);
            // 已移除
        }
    }

    private int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private float clampf(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
