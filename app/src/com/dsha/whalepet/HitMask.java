package com.dsha.whalepet;

import android.content.Context;
import android.graphics.Region;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 每段素材的点击掩码。
 *
 * 素材的 alpha 区域只占画面 20%~56%，但悬浮窗是整块矩形：以前透明区也会吃掉点击，
 * 下面的游戏点不到。这里用离线生成的 64×64 占用位图，把窗口的 touchable region
 * 收缩到人物轮廓（见 tools/gen_hit.py）。
 */
final class HitMask {

    private final int grid;
    private final Map<String, byte[]> bits = new HashMap<>();

    private HitMask(int grid) {
        this.grid = grid;
    }

    static HitMask load(Context c) {
        HitMask out = new HitMask(64);
        try (InputStream in = c.getAssets().open("video/hit.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONObject clips = root.optJSONObject("clips");
            if (clips == null) return out;
            Iterator<String> it = clips.keys();
            while (it.hasNext()) {
                String dir = it.next();
                JSONObject o = clips.optJSONObject(dir);
                if (o == null) continue;
                String p = o.optString("p", "");
                if (p.isEmpty()) continue;
                out.bits.put(dir, Base64.decode(p, Base64.NO_WRAP));
            }
        } catch (Throwable t) {
            Log.ignored("HitMask.load", t);
        }
        return out;
    }

    /** 把 dir 的掩码展开成窗口坐标的 Region；没有掩码就整窗可点。 */
    void regionInto(Region out, String dir, int w, int h) {
        out.setEmpty();
        byte[] map = dir == null ? null : bits.get(dir);
        if (map == null || map.length * 8 < grid * grid) {
            out.set(0, 0, w, h);
            return;
        }
        for (int y = 0; y < grid; y++) {
            int x = 0;
            while (x < grid) {
                if (!bit(map, x, y)) {
                    x++;
                    continue;
                }
                int end = x + 1;
                while (end < grid && bit(map, end, y)) end++;
                int left = x * w / grid;
                int right = Math.max(left + 1, (end * w + grid - 1) / grid);
                int top = y * h / grid;
                int bottom = Math.max(top + 1, ((y + 1) * h + grid - 1) / grid);
                out.op(left, top, right, bottom, Region.Op.UNION);
                x = end;
            }
        }
        if (out.isEmpty()) out.set(0, 0, w, h);
    }

    private boolean bit(byte[] map, int x, int y) {
        int i = y * grid + x;
        return (map[i >> 3] & (1 << (i & 7))) != 0;
    }
}
