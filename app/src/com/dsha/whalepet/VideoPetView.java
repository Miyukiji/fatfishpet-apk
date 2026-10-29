package com.dsha.whalepet;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 视频版桌宠画面：一帧 360x720，上半彩色、下半 alpha 遮罩，GL 合成出透明画面。
 *
 * 说明：TextureView + 自建 EGL 的版本在真机上完全画不出来（黑/空白），
 * 所以回到 GLSurfaceView；图层被系统摘掉的情况由 PetService 的
 * 「停滞 -> 重建窗口」兜底。
 */
public class VideoPetView extends GLSurfaceView implements PetSurface,
        SurfaceTexture.OnFrameAvailableListener {

    private static final String VERT =
            "uniform mat4 uMvp;\n" +
            "attribute vec4 aPos;\n" +
            "attribute vec2 aUv;\n" +
            "varying vec2 vUv;\n" +
            "void main(){ gl_Position = uMvp * aPos; vUv = aUv; }\n";

    private static final String FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform float uFlip;\n" +
            "void main(){\n" +
            "  vec4 t = uTexMatrix * vec4(vUv, 0.0, 1.0);\n" +
            "  float y = clamp(t.y, 0.0015, 0.9985);\n" +   // 避开上下半区交界，防止采样串色/黑边
            "  float x = mix(t.x, 1.0 - t.x, uFlip);\n" +   // 镜像在着色器里做：SurfaceView 的 setScaleX 不保证生效
            "  vec3 rgb = texture2D(uTex, vec2(x, y * 0.5)).rgb;\n" +
            "  float a = texture2D(uTex, vec2(x, y * 0.5 + 0.5)).r;\n" +
            // 视频是有限范围 YUV（16~235）：黑色≈0.06 而不是 0，直接当 alpha 会有整片灰黑底。
            "  a = clamp((a - 0.10) / 0.75, 0.0, 1.0);\n" +   // 更保守：把 16~235 的黑彻底压成 0
            "  a = smoothstep(0.18, 0.55, a);\n" +
            "  gl_FragColor = vec4(rgb * a, a);\n" +
            "}\n";

    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f, 1f,
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int slop;
    private final float[] mvp = new float[16];
    private final float[] texMatrix = new float[16];

    private PetSurface.Listener listener;
    private Renderer renderer;
    private volatile SurfaceTexture surfaceTexture;
    private volatile MediaPlayer player;
    /** SurfaceTexture 的消费端；重建 GL surface 时必须一起换，否则播放器绑在已死的纹理上。 */
    private android.view.Surface videoSurface;
    /** 兜底切到 CONTINUOUSLY 后，出帧了要自动切回 WHEN_DIRTY，否则一直 60fps 渲染。 */
    private volatile boolean continuousFallback;

    /** 已量到的素材时长（毫秒）。替代以前从 anim.json 猜的 200ms。 */
    private final java.util.Map<String, Long> durations = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String currentDir;
    private boolean currentLoop;
    private String pendingDir;
    private boolean pendingLoop;
    private Runnable currentEnd;
    private boolean animating = true;
    /** 水平镜像（素材朝向统一朝左，向右走时翻转）。在着色器里翻 UV，不动 View 的 transform。 */
    private volatile boolean flipX;
    private volatile long lastFrameAt = SystemClock.uptimeMillis();

    private float downRawX, downRawY, lastRawX, lastRawY;
    private boolean dragging, longPressFired;
    private final float[] sx = new float[6];
    private final float[] sy = new float[6];
    private final long[] st = new long[6];
    private int samples;
    private int taps;
    private long lastTapAt;
    /** 双指单击判定：第二根手指按下且双双快速抬起、没有拖动。 */
    private boolean twoFinger;
    private long downAt;
    private int maxMove;
    /** 主手指 id：多指时只跟这一根，避免第二根手指把小人拖飞。 */
    private int activePointer = -1;

    /** 长按唤出菜单的门槛：按满 2 秒且手指没动。 */
    private static final long LONG_PRESS_MS = 600L;
    private final Runnable longPress = () -> {
        if (dragging || twoFinger) return;
        longPressFired = true;
        if (listener != null) listener.onLongPress();   // 到点当场唤出，不等松手
    };
    private final Runnable resolveTap = new Runnable() {
        @Override
        public void run() {
            int n = taps;
            taps = 0;
            if (listener == null) return;
            if (n >= 3) listener.onTripleTap();
            else if (n == 2) listener.onDoubleTap();
            else if (n == 1) listener.onSingleTap();
        }
    };

    public VideoPetView(Context c) {
        this(c, null);
    }

    public VideoPetView(Context c, AttributeSet a) {
        super(c, a);
        slop = Math.max(6, ViewConfiguration.get(c).getScaledTouchSlop() / 2);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new EGLConfigChooser() {
            @Override
            public EGLConfig chooseConfig(javax.microedition.khronos.egl.EGL10 egl,
                                          javax.microedition.khronos.egl.EGLDisplay display) {
                int[] attr = {
                        javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                        javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                        javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                        javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                        javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16,
                        javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4,
                        javax.microedition.khronos.egl.EGL10.EGL_NONE
                };
                javax.microedition.khronos.egl.EGLConfig[] configs =
                        new javax.microedition.khronos.egl.EGLConfig[1];
                int[] num = new int[1];
                if (!egl.eglChooseConfig(display, attr, configs, 1, num) || num[0] == 0) {
                    Bridge.diag("egl", "no RGBA8888 config");
                    return null;
                }
                int[] alpha = new int[1];
                egl.eglGetConfigAttrib(display, configs[0], javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, alpha);
                Bridge.diag("egl", "chosen config alpha=" + alpha[0]);
                return configs[0];
            }
        });
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(android.view.SurfaceHolder h) {
                Bridge.diag("surface", "created");
            }

            @Override
            public void surfaceChanged(android.view.SurfaceHolder h, int fmt, int w, int ht) {
                Bridge.diag("surface", "changed " + w + "x" + ht);
            }

            @Override
            public void surfaceDestroyed(android.view.SurfaceHolder h) {
                Bridge.diag("surface", "DESTROYED");
            }
        });
        renderer = new Renderer();
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        Matrix.setIdentityM(mvp, 0);
    }

    @Override
    public void setListener(PetSurface.Listener l) {
        this.listener = l;
    }

    @Override
    public View view() {
        return this;
    }

    /** 尝试把停住的播放器重新拉起来（不重建窗口）。 */
    @Override
    public boolean nudge() {
        MediaPlayer mp = player;
        if (mp == null) return false;
        try {
            Bridge.diag("player", "nudge playing=" + mp.isPlaying()
                    + " animating=" + animating + " pos=" + mp.getCurrentPosition());
            if (!animating) {
                mp.start();          // animating 卡住时强行恢复
                animating = true;
            } else if (!mp.isPlaying()) {
                mp.start();
            } else {
                mp.pause();
                mp.start();
            }
            return true;
        } catch (Throwable t) {
            Bridge.diag("player", "nudge failed " + t);
            return false;
        }
    }

    @Override
    public long sinceLastFrame() {
        return SystemClock.uptimeMillis() - lastFrameAt;
    }

    @Override
    public void play(String dir, boolean loop, Runnable end) {
        if (dir == null || dir.isEmpty()) return;
        // 新请求就是要看动画：把"暂停"状态清掉，否则 onPrepared 里会立刻 pause（点了没反应）
        animating = true;
        if (surfaceTexture == null) {
            pendingDir = dir;
            pendingLoop = loop;
            requestRender();
            return;
        }
        startClip(dir, loop, end);
        requestRender();
    }

    @Override
    public long durationMs(String dir) {
        Long d = durations.get(dir);
        return d == null ? 0L : d;
    }

    public void pauseSurface() {
        try {
            onPause();
        } catch (Throwable t) { Log.ignored("VideoPetView", t);
            // 未初始化
        }
    }

    @Override
    public void resumeSurface() {
        try {
            // 重新挂回窗口后，部分机型会把 surface 重置成不透明；这里每次强制一次
            getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            setZOrderOnTop(true);
            Bridge.diag("gl", "translucent set, valid=" + getHolder().getSurface().isValid());
            onResume();
        } catch (Throwable t) { Log.ignored("VideoPetView", t);
            // 无 surface
        }
    }

    @Override
    public void stop() {
        handler.removeCallbacksAndMessages(null);
        currentDir = null;
        currentEnd = null;
        releasePlayer();
        // 只放 Surface，不释放 SurfaceTexture：view 还挂着时拿不到新的 GL 纹理，
        // 释放了就没法再播（真正的释放放在 onSurfaceCreated / onDetachedFromWindow）。
        releaseVideoSurface();
    }

    private void releaseVideoSurface() {
        android.view.Surface s = videoSurface;
        videoSurface = null;
        if (s != null) {
            try {
                s.release();
            } catch (Throwable t) {
                Log.ignored("VideoPetView.surface", t);
            }
        }
    }

    /** 释放 SurfaceTextre + Surface（GL 上下文重建 / view 被移除时）。 */
    private void releaseSurface() {
        releaseVideoSurface();
        SurfaceTexture st = surfaceTexture;
        surfaceTexture = null;
        if (st != null) {
            try {
                st.release();
            } catch (Throwable t) {
                Log.ignored("VideoPetView.surface", t);
            }
        }
    }

    @Override
    public void setAnimating(boolean on) {
        Bridge.diag("player", "setAnimating=" + on);
        animating = on;
        try {
            if (player != null) {
                if (on) player.start();
                else player.pause();
            }
        } catch (Throwable t) { Log.ignored("VideoPetView", t);
            // 播放器已释放
        }
    }

    @Override
    public void squashPress() {
        startSquash(Physics.SQ_SQUASH);
    }

    @Override
    public void squashLanding(float impactSpeed) {
        if (impactSpeed < 260f) return;
        startSquash(Physics.landingSquash(impactSpeed));
    }


    private long squashT0;
    private boolean squashRunning;

    private void startSquash(float depth) {
        final float d = Math.max(0.3f, Math.min(0.95f, depth));
        squashT0 = SystemClock.uptimeMillis();
        if (squashRunning) return;
        squashRunning = true;
        handler.post(new Runnable() {
            @Override
            public void run() {
                float u = Math.min((SystemClock.uptimeMillis() - squashT0) / 220f, 1f);
                setPivotY(getHeight());
                setScaleY(Physics.squashScale(u, d));
                if (u < 1f) handler.postDelayed(this, 16L);
                else {
                    setScaleY(1f);
                    squashRunning = false;
                }
            }
        });
    }

    private void startClip(String dir, boolean loop, Runnable end) {
        try {
            currentDir = dir;
            currentLoop = loop;
            currentEnd = end;
            final MediaPlayer mp;
            if (player == null) {
                mp = new MediaPlayer();
                player = mp;
            } else {
                mp = player;
                mp.reset();          // 复用实例，省一次 codec 申请
            }
            // asset 里 webm 是不压缩存储的（build.sh 里 ZIP_STORED），可以直接按 fd 播，
            // 不需要再复制 38MB 到应用缓存。个别机型/打包方式下 openFd 会抛，退回缓存拷贝。
            boolean direct = false;
            try {
                android.content.res.AssetFileDescriptor afd =
                        getContext().getAssets().openFd("video/" + dir + ".webm");
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
                direct = true;
                Bridge.diag("asset", "direct " + dir);
            } catch (Throwable err) {
                Log.ignored("VideoPetView.openFd", err);
            }
            if (!direct) {
                java.io.File f = copyToCache(dir);
                if (f == null) return;
                mp.setDataSource(f.getAbsolutePath());
                Bridge.diag("asset", "fallback-cache " + dir);
            }
            if (videoSurface == null && surfaceTexture != null) {
                videoSurface = new android.view.Surface(surfaceTexture);
            }
            if (videoSurface == null) {
                pendingDir = dir;
                pendingLoop = loop;
                requestRender();
                return;
            }
            mp.setSurface(videoSurface);
            mp.setLooping(loop);
            mp.setOnPreparedListener(m -> {
                Bridge.diag("player", "decoded=" + m.getVideoWidth() + "x" + m.getVideoHeight()
                        + " view=" + getWidth() + "x" + getHeight());
                try {
                    durations.put(dir, (long) m.getDuration());
                } catch (Throwable t) {
                    // 拿不到时长就按默认值算
                }

                try {
                    m.start();

                    if (!animating) m.pause();
                } catch (Throwable t) { Log.ignored("VideoPetView", t);
                    // 起播失败
                }
            });
            mp.setOnCompletionListener(m -> {

                if (loop) return;
                Runnable cb = currentEnd;
                currentEnd = null;
                if (cb != null) handler.post(cb);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Bridge.diag("player", "ERROR " + dir + " what=" + what + " extra=" + extra);
                releasePlayer();
                return true;
            });
            mp.setOnInfoListener((m, what, extra) -> {
                return false;
            });
            mp.prepareAsync();
            player = mp;
            handler.postDelayed(() -> {
                if (player == mp && sinceLastFrame() > 2000L && animating) {
                    setRenderMode(RENDERMODE_CONTINUOUSLY);
                    continuousFallback = true;
                    Bridge.diag("gl", "no frames, fallback continuous");
                }
            }, 2200L);
        } catch (Throwable t) { Log.ignored("VideoPetView", t);
            releasePlayer();
        }
    }

    /** 兜底：asset 不能按 fd 读时，复制到缓存再播（旧路径）。 */
    private java.io.File copyToCache(String dir) {
        try {
            java.io.File d = new java.io.File(getContext().getCacheDir(), "vid");
            if (!d.isDirectory() && !d.mkdirs()) return null;
            java.io.File f = new java.io.File(d, dir + ".webm");
            if (f.isFile() && f.length() > 1024) return f;
            trimCache(d);
            try (java.io.InputStream in = getContext().getAssets().open("video/" + dir + ".webm");
                 java.io.OutputStream out = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return f;
        } catch (Throwable err) {
            Log.ignored("VideoPetView.cache", err);
            return null;
        }
    }

    /** 缓存兜底时最多留 12 段，别把 38MB 全塞进用户存储。 */
    private void trimCache(java.io.File dir) {
        try {
            java.io.File[] files = dir.listFiles();
            if (files == null || files.length <= 11) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int remove = files.length - 11;
            for (java.io.File f : files) {
                if (remove-- <= 0) break;
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable err) {
            Log.ignored("VideoPetView.trim", err);
        }
    }

    private void releasePlayer() {
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (Throwable t) { Log.ignored("VideoPetView", t);
                // 还没 prepared
            }
            try {
                mp.release();
            } catch (Throwable t) { Log.ignored("VideoPetView", t);
                // 已释放
            }
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        Bridge.diag("gl", "windowVisibility=" + visibility);
        super.onWindowVisibilityChanged(visibility);
    }

    @Override
    protected void onDetachedFromWindow() {
        Bridge.diag("gl", "viewDetached");
        releasePlayer();
        releaseSurface();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Bridge.diag("gl", "viewAttached");
    }

    /** 水平镜像：true = 左右翻转。改的是着色器采样坐标，不是 View 的 scaleX。 */
    public void setFlipX(boolean flip) {
        if (flipX != flip) {
            flipX = flip;
            requestRender();
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        requestRender();
    }

    private final class Renderer implements GLSurfaceView.Renderer {
        private int program, texId, aPos, aUv, uTex, uTexMatrix, uMvp, uFlipLoc;
        private FloatBuffer quad;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            program = build();
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aUv = GLES20.glGetAttribLocation(program, "aUv");
            uTex = GLES20.glGetUniformLocation(program, "uTex");
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uMvp = GLES20.glGetUniformLocation(program, "uMvp");
            uFlipLoc = GLES20.glGetUniformLocation(program, "uFlip");

            ByteBuffer bb = ByteBuffer.allocateDirect(QUAD.length * 4);
            bb.order(ByteOrder.nativeOrder());
            quad = bb.asFloatBuffer();
            quad.put(QUAD).position(0);

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            texId = tex[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            Bridge.diag("gl", "surfaceCreated");
            // GL 上下文重建：旧的播放器/ SurfaceTexture / Surface 必须释放，否则每重建一次漏一份
            releasePlayer();
            releaseSurface();
            SurfaceTexture st = new SurfaceTexture(texId);
            st.setOnFrameAvailableListener(VideoPetView.this);
            surfaceTexture = st;
            videoSurface = new android.view.Surface(st);

            requestRender();
            String dir = currentDir != null ? currentDir : pendingDir;
            boolean loop = currentDir != null ? currentLoop : pendingLoop;
            final Runnable again = currentDir != null ? currentEnd : null;
            pendingDir = null;
            if (dir != null) post(() -> startClip(dir, loop, again));
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            Bridge.diag("gl", "surface " + w + "x" + h + " view=" + getWidth() + "x" + getHeight());
            GLES20.glViewport(0, 0, w, h);
        }

        private boolean hasFrame;

        @Override
        public void onDrawFrame(GL10 gl) {
            SurfaceTexture st = surfaceTexture;
            if (st != null && player != null) {
                try {
                    st.updateTexImage();
                    st.getTransformMatrix(texMatrix);
                    lastFrameAt = SystemClock.uptimeMillis();
                    hasFrame = true;
                    if (continuousFallback) {
                        // 已经出帧了，切回按需渲染，别一直 60fps 唤醒 GPU
                        continuousFallback = false;
                        setRenderMode(RENDERMODE_WHEN_DIRTY);
                    }
                } catch (Throwable t) { Log.ignored("VideoPetView", t);
                    // 这一帧没拿到就继续画上一帧
                }
            }
            if (!hasFrame) {
                // 还没有任何一帧（刚建好 surface）：清成透明
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                return;
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glUniform1i(uTex, 0);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
            GLES20.glUniform1f(uFlipLoc, flipX ? 1f : 0f);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);

            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(3);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, quad);
            GLES20.glEnableVertexAttribArray(aUv);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private int build() {
            int vs = compile(GLES20.GL_VERTEX_SHADER, VERT);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            GLES20.glLinkProgram(p);
            return p;
        }

        private int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }
    }

    private void sample(float x, float y, long t) {
        if (samples < sx.length) {
            sx[samples] = x; sy[samples] = y; st[samples] = t; samples++;
        } else {
            System.arraycopy(sx, 1, sx, 0, sx.length - 1);
            System.arraycopy(sy, 1, sy, 0, sy.length - 1);
            System.arraycopy(st, 1, st, 0, st.length - 1);
            sx[sx.length - 1] = x; sy[sy.length - 1] = y; st[st.length - 1] = t;
        }
    }

    private float[] velocity() {
        if (samples < 2) return new float[]{0f, 0f};
        long now = st[samples - 1];
        int from = 0;
        for (int i = samples - 1; i >= 0; i--) {
            if (now - st[i] > 130L) { from = Math.min(i + 1, samples - 1); break; }
        }
        float dt = (now - st[from]) / 1000f;
        if (dt <= 0.001f) return new float[]{0f, 0f};
        return new float[]{(sx[samples - 1] - sx[from]) / dt, (sy[samples - 1] - sy[from]) / dt};
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = e.getPointerId(0);
                twoFinger = false;
                downAt = SystemClock.uptimeMillis();
                maxMove = 0;
                downRawX = e.getRawX(); downRawY = e.getRawY();
                lastRawX = downRawX; lastRawY = downRawY;
                dragging = false; longPressFired = false; samples = 0;
                sample(downRawX, downRawY, SystemClock.uptimeMillis());
                handler.removeCallbacks(longPress);
                handler.postDelayed(longPress, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                twoFinger = true;
                handler.removeCallbacks(longPress);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            case MotionEvent.ACTION_MOVE: {
                // 多指时只跟最早按下的那根；坐标必须用 raw（窗口绝对坐标）
                if (e.getPointerId(0) != activePointer) return true;
                float x = e.getRawX(), y = e.getRawY();
                maxMove = Math.max(maxMove, (int) Math.hypot(x - downRawX, y - downRawY));
                sample(x, y, SystemClock.uptimeMillis());
                if (!dragging && (Math.abs(x - downRawX) > slop || Math.abs(y - downRawY) > slop)) {
                    dragging = true;
                    handler.removeCallbacks(longPress);
                    if (listener != null) listener.onDragStart(x, y);
                }
                if (dragging && listener != null) listener.onDragMove(x, y);
                lastRawX = x; lastRawY = y;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPress);
                if (dragging) {
                    float[] v = velocity();
                    dragging = false;
                    if (listener != null) listener.onDragEnd(v[0], v[1]);
                    return true;
                }
                if (longPressFired) {          // 菜单已经在按住 2 秒时弹出过
                    longPressFired = false;
                    return true;
                }
                squashPress();
                long now = SystemClock.uptimeMillis();
                if (now - lastTapAt > 360L) taps = 0;
                taps++; lastTapAt = now;
                handler.removeCallbacks(resolveTap);
                if (taps >= 3) handler.post(resolveTap);
                else handler.postDelayed(resolveTap, 300L);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (dragging && listener != null) listener.onDragEnd(0f, 0f);
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
