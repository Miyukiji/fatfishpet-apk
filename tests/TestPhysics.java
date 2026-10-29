package com.dsha.whalepet;

/**
 * 抛掷物理与挤压曲线的纯 JVM 单测（无 Android 运行时）。
 *
 * 数值口径来自 dsh-pet（MIT）：重力 1400、弹性 0.78、摩擦 2.5、落地归零 260，
 * 这些是「手感」，改了就会跟上游不一致，所以在这里钉死。
 */
public final class TestPhysics {

    private static int checks;
    private static int fails;

    private static void eq(String what, float actual, float expected, float eps) {
        checks++;
        if (Math.abs(actual - expected) > eps) {
            fails++;
            System.out.println("FAIL " + what + ": " + actual + " != " + expected);
        }
    }

    private static void yes(String what, boolean v) {
        checks++;
        if (!v) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) {
        eq("GRAVITY", Physics.GRAVITY, 1400f, 1e-6f);
        eq("RESTITUTION", Physics.RESTITUTION, 0.78f, 1e-6f);
        eq("FRICTION", Physics.FRICTION, 2.5f, 1e-6f);
        eq("LAND_ZERO_VY", Physics.LAND_ZERO_VY, 260f, 1e-6f);

        // 单帧自由落体：vy += g·dt，y += vy·dt
        Physics.State s = new Physics.State(100, 100, 0, 0);
        Physics.Step r = Physics.step(s, 0.016f, 1000, 1000, null);
        eq("自由落体 vy", s.vy, 22.4f, 1e-3f);
        eq("自由落体 y", s.y, 100.3584f, 1e-3f);
        eq("自由落体 x 不动", s.x, 100f, 1e-6f);
        yes("空中不落地", !r.landed);

        // 卡顿保护：dt 夹到 MAX_DT=0.05
        s = new Physics.State(100, 100, 0, 0);
        Physics.step(s, 10f, 1000, 1000, null);
        eq("dt 夹到 0.05 后的 vy", s.vy, 70f, 1e-3f);
        eq("dt 夹到 0.05 后的 y", s.y, 103.5f, 1e-3f);

        // 水平移动
        s = new Physics.State(100, 100, 50, 0);
        Physics.step(s, 0.02f, 1000, 1000, null);
        eq("水平位移", s.x, 101f, 1e-3f);
        eq("水平帧的 vy", s.vy, 28f, 1e-3f);

        // 左右撞边：位置夹住，速度反向并乘弹性系数
        s = new Physics.State(-5, 100, -100, 0);
        Physics.step(s, 0.01f, 1000, 1000, null);
        eq("左边界 x", s.x, 0f, 1e-6f);
        eq("左边界反弹 vx", s.vx, 78f, 1e-3f);

        s = new Physics.State(1005, 100, 100, 0);
        Physics.step(s, 0.01f, 1000, 1000, null);
        eq("右边界 x", s.x, 1000f, 1e-6f);
        eq("右边界反弹 vx", s.vx, -78f, 1e-3f);

        // 天花板
        s = new Physics.State(100, -5, 0, -100);
        Physics.step(s, 0.01f, 1000, 1000, null);
        eq("天花板 y", s.y, 0f, 1e-6f);
        eq("天花板反弹 vy", s.vy, 67.08f, 1e-2f);

        // 触地：记录冲击速度，再按弹性反弹
        s = new Physics.State(100, 99, 0, 500);
        r = Physics.step(s, 0.01f, 1000, 100, null);
        eq("地面 y", s.y, 100f, 1e-6f);
        yes("落地标记", r.landed);
        eq("落地冲击速度", r.impact, 514f, 1e-2f);
        eq("落地反弹 vy", s.vy, -400.92f, 1e-2f);

        // 摩擦：落地帧横向速度衰减 (1 - 2.5·dt)
        s = new Physics.State(100, 99, 100, 500);
        Physics.step(s, 0.02f, 1000, 100, null);
        eq("落地摩擦 vx", s.vx, 95f, 1e-2f);

        // 触地速度低于 LAND_ZERO_VY 直接归零，避免无限微弹
        s = new Physics.State(100, 100, 0, 0);
        r = Physics.step(s, 0.01f, 1000, 100, null);
        eq("微弹归零", s.vy, 0f, 1e-6f);
        yes("微弹不算落地", !r.landed);

        // 静止判定：贴地 + 速度低于阈值 → 停止循环并清零
        s = new Physics.State(100, 100, 10, 0);
        r = Physics.step(s, 0.01f, 1000, 100, null);
        yes("贴地低速判定为静止", !r.alive);
        eq("静止后 vx", s.vx, 0f, 1e-6f);
        eq("静止后 vy", s.vy, 0f, 1e-6f);

        // 离地时不判静止
        s = new Physics.State(100, 50, 0, 0);
        r = Physics.step(s, 0.01f, 1000, 1000, null);
        yes("空中仍存活", r.alive);

        // 挤压曲线：0 → 1，中段最扁，回到 1，末段过冲 ≤ 1.12
        eq("挤压进度 0", Physics.squashScale(0f, 0.55f), 1f, 1e-5f);
        eq("挤压中点", Physics.squashScale(0.45f, 0.55f), 0.55f, 1e-3f);
        eq("挤压结束", Physics.squashScale(1f, 0.55f), 1f, 1e-5f);
        float over = Physics.squashScale(0.8f, 0.55f);
        yes("回弹有轻微过冲", over > 1f);
        yes("过冲不超过 1.12", over <= Physics.SQ_OVERSHOOT + 1e-6f);

        // 落地按压：300~1500 px/s 映射 0.8~0.55，越界夹住
        eq("落地按压下限", Physics.landingSquash(0f), 0.8f, 1e-5f);
        eq("落地按压 300", Physics.landingSquash(300f), 0.8f, 1e-5f);
        eq("落地按压 900", Physics.landingSquash(900f), 0.775f, 1e-4f);
        eq("落地按压 1500", Physics.landingSquash(1500f), 0.55f, 1e-5f);
        eq("落地按压上限", Physics.landingSquash(5000f), 0.55f, 1e-5f);

        System.out.println("TestPhysics: " + checks + " checks, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
