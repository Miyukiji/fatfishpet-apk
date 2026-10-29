package com.dsha.whalepet;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 素材清单：读 assets/anim/anim.json，把 50 段动作按**语义**分池。
 *
 * 为什么要分池：v4 的单击是「从 49 个里随便抽一个」，抽到"看表叹气"也当点击回应，
 * 所以看着不像"有反应"，像"在乱动"。插件的灵气来自「点击有回应、干活有状态、
 * 闲着有自己的小日子」这三条线，池子分开是这三条线的地基。
 */
public final class Anim {

    public static final class Act {
        public final String dir;
        /** 中文标题：用于分池与日志，不随语言变。 */
        public final String title;
        /** 展示标题：按系统语言取 anim-i18n.json，缺省等于 title。 */
        public String display;
        /** 旧素材（1.0.97 及以前）：沿用旧分类规则；新追加素材不参与旧池。 */
        public boolean legacy = true;
        /** 是否出现在「动作点播」菜单（clip22 开始工作只做状态联动）。 */
        public boolean menu = true;
        public final int w;
        public final int h;
        /** 锚点：alpha 包围盒底边中点（顶部画面像素），用于切换动作时钉住脚底。 */
        public int ax;
        public int ay;

        Act(String dir, String title, int w, int h) {
            this.dir = dir;
            this.title = title;
            this.display = title;
            this.w = w;
            this.h = h;
        }
    }

    /** 待机呼吸（循环）。 */
    public Act idle;
    /** 被拖拽悬空。 */
    public Act drag;
    /** 睡觉主段（深夜循环）。 */

    /** 双击轮换池：看天气 / 翻钱包。 */
    public final List<Act> doubleTapPool = new ArrayList<>();
    private int doubleTapIdx = 0;
    /** 喂食动作池（吃盒饭 / 吃小鱼干 / 喝奶茶）。 */
    public final List<Act> feedPool = new ArrayList<>();

    public final List<Act> all = new ArrayList<>();
    /** 待机随机链：闲着的时候自己干的事。 */
    public final List<Act> idlePool = new ArrayList<>();
    /** 点击回应：开心 / 害羞 / 傲娇 … */
    public final List<Act> clickPool = new ArrayList<>();
    /** 深夜用的困倦动作。 */
    public final List<Act> sleepPool = new ArrayList<>();
    /** 干活状态 → 动作。 */
    public final Map<String, Act> statusAct = new LinkedHashMap<>();
    /** 出错/炸毛类动作（随机播）。 */
    public final List<Act> errorPool = new ArrayList<>();
    private final java.util.Random rnd = new java.util.Random();
    /** 干活被打断时的动作。 */
    public Act interrupt;
    /** 走路的横向位移动作。 */
    public Act walk;
    /** 漂浮踏步（原地悬浮漂移）。 */
    public Act floatAct;
    /** 斜向移动素材（尚未提供，null 时不启用）。 */
    public Act diagonal;
    /** 等你确认时的动作池（看表叹气）。 */
    public final List<Act> waitingPool = new ArrayList<>();
    /** 干活动作池（认真工作 / 长时间工作看表 轮换）。 */
    public final List<Act> workingPool = new ArrayList<>();
    /** 思考动作池（旧工作思考 + 新思考冒泡/深度思考碎碎念）。 */
    public final List<Act> thinkingPool = new ArrayList<>();
    /** 完成动作池（旧工作结束 + 新雀跃庆祝）。 */
    public final List<Act> donePool = new ArrayList<>();
    /** 碎碎念动画池（新素材 3 段）。 */
    public final List<Act> whisperPool = new ArrayList<>();
    /** 余额分档动画：0 满 → 5 空。 */
    public final Act[] balanceTiers = new Act[6];
    private int thinkingIdx = 0;
    private int doneIdx = 0;
    private int workingIdx = 0;
    private int waitingIdx = 0;


    /** 双击动作（看天气 / 翻钱包 轮换）。 */
    public Act nextDoubleTap() {
        if (doubleTapPool.isEmpty()) return idle;
        Act a = doubleTapPool.get(doubleTapIdx % doubleTapPool.size());
        doubleTapIdx++;
        return a;
    }

    /** 碎碎念动画（没有素材时回退待机）。 */
    public Act randomWhisper() {
        return whisperPool.isEmpty() ? idle : whisperPool.get(rnd.nextInt(whisperPool.size()));
    }

    /** 余额分档动画；没有素材返回 null。 */
    public Act balanceAct(int tier) {
        int i = Math.max(0, Math.min(balanceTiers.length - 1, tier));
        return balanceTiers[i];
    }

    /** 随机一个喂食动作。 */
    public Act randomFeed() {
        return feedPool.isEmpty() ? null : feedPool.get(new java.util.Random().nextInt(feedPool.size()));
    }

    public Act statusAct(String key) {
        if ("error".equals(key) && !errorPool.isEmpty()) {
            return errorPool.get(rnd.nextInt(errorPool.size()));
        }
        if ("waiting".equals(key) && !waitingPool.isEmpty()) {
            // 轮换：以前只取 get(0)，排在后面的「原地踱步张望」永远播不到
            Act a = waitingPool.get(waitingIdx % waitingPool.size());
            waitingIdx++;
            return a;
        }
        if ("working".equals(key) && !workingPool.isEmpty()) {
            Act a = workingPool.get(workingIdx % workingPool.size());
            workingIdx++;
            return a;
        }
        if ("thinking".equals(key) && !thinkingPool.isEmpty()) {
            Act a = thinkingPool.get(thinkingIdx % thinkingPool.size());
            thinkingIdx++;
            return a;
        }
        if ("done".equals(key) && !donePool.isEmpty()) {
            Act a = donePool.get(doneIdx % donePool.size());
            doneIdx++;
            return a;
        }
        return statusAct.get(key);
    }

    private static boolean has(String title, String... keys) {
        for (String k : keys) {
            if (title.contains(k)) return true;
        }
        return false;
    }

    public static Anim load(Context c) {
        Anim out = new Anim();
        JSONObject root;
        try (InputStream in = c.getAssets().open("video/anim.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            return out;
        }

        JSONObject states = root.optJSONObject("states");
        if (states != null) {
            JSONObject it = states.optJSONObject("idle");
            if (it != null) out.idle = parse(it, "待机呼吸休闲");
        }

        JSONObject acts = root.optJSONObject("actions");
        if (acts != null) {
            java.util.Iterator<String> keys = acts.keys();
            while (keys.hasNext()) {
                JSONObject o = acts.optJSONObject(keys.next());
                if (o == null) continue;
                Act a = parse(o, "");
                if (a != null) out.all.add(a);
            }
        }
        out.classify();
        out.applyDisplayTitles(c);
        return out;
    }

    /** 语言变化后重新套展示标题（不动分池）。 */
    void relocalize(Context c) {
        for (Act a : all) a.display = a.title;
        if (idle != null) idle.display = idle.title;
        applyDisplayTitles(c);
    }

    /** 读 assets/video/anim-i18n.json，按系统语言替换展示标题（分池仍用中文 title）。 */
    private void applyDisplayTitles(Context c) {
        try {
            String lang = c.getResources().getConfiguration()
                    .getLocales().get(0).getLanguage();
            if (!"en".equals(lang) && !"it".equals(lang) && !"ja".equals(lang)) return;
            java.io.InputStream in = c.getAssets().open("video/anim-i18n.json");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONObject map = root.optJSONObject(lang);
            if (map == null) return;
            for (Act a : all) {
                String t = map.optString(a.dir, "");
                if (!t.isEmpty()) a.display = t;
            }
            if (idle != null) {
                String t = map.optString(idle.dir, "");
                if (!t.isEmpty()) idle.display = t;
            }
        } catch (Throwable t) {
            // 没有语言包就保持中文
        }
    }

    private static Act parse(JSONObject o, String dfltTitle) {
        try {
            String dir = o.optString("dir", "");
            if (dir.isEmpty()) return null;
            String title = o.optString("title", dfltTitle);
            int w = o.optInt("w", 300);
            int h = o.optInt("h", 300);
            Act a = new Act(dir, title, w, h);
            a.legacy = o.optBoolean("legacy", true);
            a.menu = o.optBoolean("menu", true);
            a.ax = o.optInt("ax", w / 2);
            a.ay = o.optInt("ay", h);
            return a;
        } catch (Throwable t) {
            return null;
        }
    }

    private void classify() {
        for (Act a : all) {
            if (!a.legacy) continue;      // 新追加素材不参与旧池，只在动作点播里出现
            String t = a.title;
            if (has(t, "拖拽")) {
                drag = a;
            } else if (has(t, "点击回应")) {
                clickPool.add(a);
            } else if (has(t, "睡觉第一段")) {
                sleepPool.add(a);
            } else if (has(t, "睡觉", "迷糊犯困", "打瞌睡被惊醒", "原地小憩沉眠", "睡眼惺忪")) {
                sleepPool.add(a);
            } else if (has(t, "螃蟹走路")) {
                walk = a;
            } else if (has(t, "原地漂浮踏步")) {
                floatAct = a;
            } else if (has(t, "斜向")) {
                diagonal = a;
            } else if (has(t, "看天气", "翻钱包")) {
                doubleTapPool.add(a);     // 只在双击时轮换，不进待机
            } else if (has(t, "吃盒饭", "吃小鱼干", "喝奶茶", "偷吃零食", "偷吃Token")) {
                feedPool.add(a);
            } else if (has(t, "工作思考")) {
                put("thinking", a);
            } else if (has(t, "认真工作", "长时间工作看表", "工作摸鱼", "摸鱼被抓")) {
                // "摸鱼被抓"实际是电脑前越打越困：放在干活池，摸着摸着睡着
                workingPool.add(a);
            } else if (has(t, "工作被打扰")) {
                interrupt = a;               // 干活被打断
            } else if (has(t, "看表叹气")) {
                waitingPool.add(a);          // 等待确认（在等你点允许）
            } else if (has(t, "玩游戏气急败坏", "被吓一跳")) {
                errorPool.add(a);
                put("error", a);
            } else if (has(t, "工作结束")) {
                put("done", a);
            } else if (has(t, "举牌不是大肥鱼")) {
                // 这套素材里"点击回应"只有 3 段（傲娇/害羞/开心），第 4 个用举牌补
                // （另一段「空白举牌」留给动作点播，想让牌子空着就把这两行换一下）
                clickPool.add(a);
            } else if (has(t, "悠闲哼歌", "原地专心玩魔方", "哈欠连天", "超大伸懒腰",
                    "东张西望", "鲸鱼吐泡泡特效",
                    "女仆扫除", "闲得无聊打游戏",
                    "原地跳跃抓碎头顶物品", "原地蹲下玩玩具汽车", "小幅度原地 360 度旋转展示", "打喷嚏")) {
                idlePool.add(a);
            }
        }
        // 点击回应：这里先去掉傲娇/东张西望/被吓一跳（傲娇与举牌由下面的专项规则加回，
        // 东张西望留给待机链、被吓一跳归 error）。「举牌不是大肥鱼」保留在点击池，
        // 下面的 contains("举牌") 规则负责把它（和空白举牌）补回来。
        clickPool.removeIf(a -> a.title != null
                && (a.title.contains("举牌不是大肥鱼") || a.title.contains("傲娇生气")
                    || a.title.contains("东张西望") || a.title.contains("被吓一跳")));

        for (Act a : all) {
            if (!a.legacy) continue;
            if (a.title != null && (a.title.contains("原地敲击桌面互动")
                    || a.title.contains("原地重力下蹲压缩")) && !clickPool.contains(a)) {
                clickPool.add(a);      // 敲桌子、被压扁：都像"戳一下的反应"
            }
        }
        for (Act a : all) {
            if (!a.legacy) continue;
            if (a.title != null && (a.title.contains("傲娇生气") || a.title.contains("女仆屈膝礼仪")
                    || a.title.contains("举牌") || a.title.contains("鲸鱼尾巴拍打地面"))
                    && !clickPool.contains(a)) {
                clickPool.add(a);
            }
        }
        if (idle != null && !idlePool.contains(idle)) idlePool.add(0, idle);
        // 对角没有独立素材：复用横向走路（窗口走斜线，人物保持迈步）
        if (diagonal == null) diagonal = walk;
        // 六档新增：整理（result）用上游新素材，其余旧素材分类完全不动
        for (Act a : all) {
            if (a.title != null && a.title.contains("工作状态-清点归档")) put("result", a);
        }
        if (statusAct.get("result") == null) statusAct.put("result", statusAct.get("working"));

        // 新追加素材接入现有事件（用户要求）：余额/碎碎念没有对应事件，保持仅菜单
        for (Act a : all) {
            if (a.legacy || a.title == null) continue;
            String t = a.title;
            if (t.startsWith("余额-")) {
                int tier = -1;
                if (t.contains("钱袋满溢")) tier = 0;
                else if (t.contains("金袋叮当")) tier = 1;
                else if (t.contains("钱袋如常")) tier = 2;
                else if (t.contains("数金皱眉")) tier = 3;
                else if (t.contains("袋空如洗")) tier = 4;
                else if (t.contains("分文不剩")) tier = 5;
                if (tier >= 0) balanceTiers[tier] = a;
                continue;
            }
            if (t.startsWith("碎碎念-")) { whisperPool.add(a); continue; }
            if (t.contains("工作状态-清点归档")) continue;   // 已映射 result
            if (t.startsWith("工作状态-思考") || t.contains("深度思考碎碎念")) {
                thinkingPool.add(a);
            } else if (t.startsWith("工作状态-忙碌")) {
                workingPool.add(a);
            } else if (t.startsWith("工作状态-原地踱步")) {
                waitingPool.add(a);
            } else if (t.startsWith("工作状态-垂头") || t.contains("被吓一跳")) {
                errorPool.add(a);
            } else if (t.startsWith("工作状态-雀跃")) {
                donePool.add(a);
            } else if (t.startsWith("点击回应-")) {
                clickPool.add(a);
            } else if (t.startsWith("吃") || t.contains("涮火锅") || t.contains("大口吃零食")) {
                feedPool.add(a);
            } else {
                idlePool.add(a);
            }
        }

        // 旧素材的 thinking/done 以前只写进 statusAct 映射：新素材一进池子，映射就被完全盖住，
        // clip18 工作思考 / clip20 工作结束 永远播不到。补进池子首轮轮换。
        Act oldThinking = statusAct.get("thinking");
        if (oldThinking != null && !thinkingPool.contains(oldThinking)) {
            thinkingPool.add(0, oldThinking);
        }
        Act oldDone = statusAct.get("done");
        if (oldDone != null && !donePool.contains(oldDone)) {
            donePool.add(0, oldDone);
        }
    }

    private void put(String key, Act a) {
        if (statusAct.get(key) == null) statusAct.put(key, a);
    }
}
