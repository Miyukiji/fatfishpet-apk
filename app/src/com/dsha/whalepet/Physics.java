package com.dsha.whalepet;

/**
 * 桌宠的「手感」——抛掷物理与挤压曲线的纯函数版本。
 *
 * 从 PetService / PetView 里抽出来的唯一目的：**能在 JVM 上跑单测**。
 * 参数与 dsh-pet（MIT）逐项一致，一个都没改：
 *
 *   gravity 1400 px/s² · restitution 0.78 · groundFriction 2.5 · ceilingBounce on
 *   挤压 55% / 220ms / easeOutBack 过冲上限 1.12 / 落地按压 0.8~0.55
 *
 * 这个类**不许 import android**：一 import 就只能在手机上跑，等于没有测试。
 */
public final class Physics {

    public static final float GRAVITY = 1400f;
    public static final float RESTITUTION = 0.78f;
    public static final float FRICTION = 2.5f;
    /** 静止判定阈值（dsh-pet: REST_VY / REST_VX）。 */
    public static final float REST_VY = 30f;
    public static final float REST_VX = 60f;
    /** 触地速度低于它会直接归零，避免无限微弹（dsh-pet 同款 260）。 */
    public static final float LAND_ZERO_VY = 260f;
    /** 单帧最大步长：卡顿后不能一步穿墙。 */
    public static final float MAX_DT = 0.05f;

    /** 挤压最深处与时长、过冲上限。 */
    public static final float SQ_SQUASH = 0.55f;
    public static final float SQ_OVERSHOOT = 1.12f;

    private Physics() {
    }

    public static final class State {
        public float x;
        public float y;
        public float vx;
        public float vy;

        public State(float x, float y, float vx, float vy) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
        }
    }

    /** 一帧的结果：是否需要再走一帧 / 这一帧是否发生了落地冲击。 */
    public static final class Step {
        public boolean alive;
        public boolean landed;
        /** 落地冲击速度（px/s），没落地就是 0。 */
        public float impact;
    }

    /**
     * 推进一帧。坐标是窗口左上角，边界 [0,maxX] × [0,maxY]，y 向下为正。
     *
     * @param dt 秒；内部会夹到 {@link #MAX_DT}
     */
    public static Step step(State s, float dt, float maxX, float maxY, Step out) {
        float d = Math.min(Math.max(dt, 0f), MAX_DT);
        Step r = out == null ? new Step() : out;
        r.landed = false;
        r.impact = 0f;

        s.vy += GRAVITY * d;
        s.x += s.vx * d;
        s.y += s.vy * d;

        if (s.x < 0f) {
            s.x = 0f;
            s.vx = Math.abs(s.vx) * RESTITUTION;
        } else if (s.x > maxX) {
            s.x = maxX;
            s.vx = -Math.abs(s.vx) * RESTITUTION;
        }

        if (s.y < 0f) {
            s.y = 0f;
            s.vy = Math.abs(s.vy) * RESTITUTION;
        }

        if (s.y >= maxY) {
            if (s.vy > 60f) {
                r.landed = true;
                r.impact = s.vy;
            }
            s.y = maxY;
            s.vy = -s.vy * RESTITUTION;
            s.vx *= Math.max(0f, 1f - FRICTION * d);
            if (Math.abs(s.vy) < LAND_ZERO_VY) s.vy = 0f;
        }

        boolean rest = s.y >= maxY - 1f
                && Math.abs(s.vy) < REST_VY
                && Math.abs(s.vx) < REST_VX;
        if (rest) {
            s.vx = 0f;
            s.vy = 0f;
        }
        r.alive = !rest;
        return r;
    }

    /**
     * 挤压曲线（dsh-pet 的 squashScale）：前 45% 加速压到 depth，后 55% easeOutBack 弹回并过冲。
     *
     * @param u 0~1 的进度
     */
    public static float squashScale(float u, float depth) {
        if (u < 0.45f) {
            float p = u / 0.45f;
            return 1f - (1f - depth) * p * p;
        }
        float p = (u - 0.45f) / 0.55f;
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float f = 1f + c3 * (p - 1f) * (p - 1f) * (p - 1f) + c1 * (p - 1f) * (p - 1f);
        return Math.min(SQ_OVERSHOOT, depth + (1f - depth) * Math.max(f, 0f));
    }

    /** 落地按压幅度：冲击速度 300~1500 px/s 映射到 0.8~0.55。 */
    public static float landingSquash(float impactSpeed) {
        float t = (impactSpeed - 300f) / (1500f - 300f);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return Math.min(0.8f, 1f - t * (1f - SQ_SQUASH));
    }
}
