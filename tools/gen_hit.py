#!/usr/bin/env python3
"""为每段素材生成锚点（ax/ay）与点击掩码（hit.json）。

- 锚点 = alpha 并集包围盒的底边中点（顶部画面坐标，单位像素）。运行时用它把
  “人物脚底”钉在同一个屏幕点上，切动作/改尺寸时不会横移。
- 点击掩码 = 64×64 占用位图（多帧并集），运行时拼成 Region 作为悬浮窗的
  touchable region，透明区域不再吃掉下面应用的点击。
- clip22「开始工作」标记 menu=false（只做状态联动，不进动作点播）。

依赖 ffmpeg（libvpx-vp9 解码，必须显式指定，否则拿不到 alpha）。
"""
import base64
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
VIDEO = ROOT / 'app/assets/video'
GRID = 64
FRACTIONS = (0.12, 0.3, 0.5, 0.7, 0.88)


def ffmpeg_bin():
    import shutil
    exe = shutil.which('ffmpeg')
    if not exe:
        sys.exit('需要 ffmpeg（并确保在 PATH 中）')
    return exe


def alpha_grid(ffmpeg, path, w, h, t, grid=GRID):
    cmd = [ffmpeg, '-v', 'error', '-c:v', 'libvpx-vp9', '-ss', f'{t:.3f}', '-i', str(path),
           '-vf', f'crop={w}:{h}:0:{h},scale={grid}:{grid}', '-frames:v', '1',
           '-f', 'rawvideo', '-pix_fmt', 'gray', '-']
    out = subprocess.run(cmd, capture_output=True).stdout
    return out if len(out) == grid * grid else None


def main():
    ffmpeg = ffmpeg_bin()
    anim_path = VIDEO / 'anim.json'
    data = json.loads(anim_path.read_text(encoding='utf-8'))
    entries = list(data['actions'].items()) + list(data['states'].items())
    hit = {}
    missing = []
    for key, a in entries:
        d = a['dir']
        w, h = int(a.get('w', 360)), int(a.get('h', 360))
        path = VIDEO / f'{d}.webm'
        probe = subprocess.run(
            [str(pathlib.Path(ffmpeg).with_name('ffprobe.exe' if sys.platform.startswith('win') else 'ffprobe')), '-v', 'error', '-show_entries', 'format=duration',
             '-of', 'default=nw=1:nk=1', str(path)], capture_output=True, text=True)
        try:
            dur = float(probe.stdout.strip())
        except ValueError:
            dur = 10.0
        union = bytearray(GRID * GRID)
        for f in FRACTIONS:
            g = alpha_grid(ffmpeg, path, w, h, max(0.0, min(dur - 0.05, dur * f)))
            if not g:
                continue
            for i, v in enumerate(g):
                if v > 16:
                    union[i] = 1
        xs = [i % GRID for i, v in enumerate(union) if v]
        ys = [i // GRID for i, v in enumerate(union) if v]
        if not xs:
            missing.append(d)
            continue
        x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
        # 锚点：包围盒底边中点 → 顶部画面像素
        ax = round((x0 + x1 + 1) / 2 / GRID * w)
        ay = round((y1 + 1) / GRID * h)
        a['ax'] = ax
        a['ay'] = ay
        bits = bytearray((GRID * GRID + 7) // 8)
        for i, v in enumerate(union):
            if v:
                bits[i >> 3] |= 1 << (i & 7)
        hit[d] = {
            'b': [x0, y0, x1, y1],
            'p': base64.b64encode(bytes(bits)).decode('ascii'),
        }
    if missing:
        sys.exit(f'这些素材没有 alpha 内容: {missing}')

    # clip22 只做状态联动：从动作点播里下线
    for key, a in data['actions'].items():
        if a.get('title') == '开始工作':
            a['menu'] = False

    anim_path.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    (VIDEO / 'hit.json').write_text(json.dumps(
        {'grid': GRID, 'clips': hit}, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    print(f'ok: {len(hit)} clips, grid={GRID}, hit.json {(VIDEO / "hit.json").stat().st_size} bytes')


if __name__ == '__main__':
    main()
