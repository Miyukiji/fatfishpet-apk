package com.dsha.whalepet;

import android.view.View;

/** 桌宠画面的两种实现（逐帧 WebP / 视频双区）共用的接口。 */
public interface PetSurface {

    /** 手势回调（唯一一份，PetService 实现）。 */
    interface Listener {
        void onSingleTap();

        void onDoubleTap();

        void onTripleTap();


        void onLongPress();

        void onDragStart(float rawX, float rawY);

        void onDragMove(float rawX, float rawY);

        void onDragEnd(float vxPxPerSec, float vyPxPerSec);
    }

    void setListener(Listener l);

    /** 播放一段素材；时长由实现自己向上汇报（见 durationMs）。 */
    void play(String dir, boolean loop, Runnable end);

    /** 这段素材播完需要多久（毫秒）；还没量到就先按 10 秒估。 */
    long durationMs(String dir);

    void stop();

    /** 拖动期间暂停刷新，松手恢复。 */
    void setAnimating(boolean on);

    void squashPress();

    void squashLanding(float impactSpeed);

    /** 窗口被移出前调用：释放 EGL surface / 渲染线程。 */
    void pauseSurface();

    /** 窗口重新挂上后调用：恢复渲染。 */
    void resumeSurface();

    /** 拿到真正的 View（窗口 token / 布局用）。 */
    View view();

    /** 距上一帧过了多久（毫秒）；用于检测"窗口在、画面死"。 */
    long sinceLastFrame();

    /** 尝试让停住的播放器重新出帧（比重建窗口轻）。 */
    boolean nudge();
}
