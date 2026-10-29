#!/usr/bin/env python3
"""校验 app/assets/video：文件与 anim.json 一一对应、尺寸为 w×(2h)、alpha 区域非空、i18n 覆盖。

素材尺寸不再写死：旧 50 段是 360×360（成片 360×720），新 86 段里有 18 段宽内容
是 640×360（成片 640×720），以 anim.json 的 w/h 为准。
"""
import json
import pathlib
import shutil
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parent.parent / 'app/assets/video'
data = json.loads((root / 'anim.json').read_text(encoding='utf-8'))
clips = dict(data.get('states', {}))
clips.update(data.get('actions', {}))
dirs = set(clips)
files = {p.stem for p in root.glob('*.webm')}

errors = []
if dirs != files:
    errors.append(f'anim.json 与文件不一致: 缺 {sorted(dirs - files)[:5]} 多 {sorted(files - dirs)[:5]}')

if shutil.which('ffprobe') is None or shutil.which('ffmpeg') is None:
    print('需要 ffmpeg/ffprobe 才能校验尺寸与 alpha；请先安装并加入 PATH', file=sys.stderr)
    sys.exit(2)

for name in sorted(files & dirs):
    clip = clips[name]
    w, h = int(clip.get('w', 360)), int(clip.get('h', 360))
    path = root / f'{name}.webm'

    probe = subprocess.run(
        ['ffprobe', '-v', 'error', '-show_entries', 'stream=width,height', '-of', 'csv=p=0', str(path)],
        capture_output=True, text=True)
    got = probe.stdout.strip().replace(' ', '')
    if got != f'{w},{h * 2}':
        errors.append(f'{path.name} 尺寸 {got}，应为 {w},{h * 2}')

    alpha = subprocess.run(
        ['ffmpeg', '-v', 'error', '-i', str(path), '-vf', f'crop={w}:{h}:0:{h}', '-frames:v', '1',
         '-f', 'rawvideo', '-pix_fmt', 'gray', '-'], capture_output=True)
    if sum(1 for x in alpha.stdout if x > 16) < 1000:
        errors.append(f'{path.name} alpha 区域为空（{len(alpha.stdout)} 字节）')

i18n = json.loads((root / 'anim-i18n.json').read_text(encoding='utf-8'))
for lang, m in i18n.items():
    if set(m) != dirs:
        errors.append(f'{lang} 标题覆盖 {len(m)}/{len(dirs)}')

# 锚点与点击掩码（1.2.0 起）：缺一个，切动作会跳 / 透明区会吃掉点击
hit_path = root / 'hit.json'
if not hit_path.exists():
    errors.append('缺 hit.json（跑 tools/gen_hit.py）')
else:
    hit = json.loads(hit_path.read_text(encoding='utf-8'))
    if set(hit.get('clips', {})) != dirs:
        errors.append(f'hit.json 覆盖 {len(hit.get("clips", {}))}/{len(dirs)}')
    if int(hit.get('grid', 0)) < 32:
        errors.append(f'hit.json grid 太小: {hit.get("grid")}')
for name in sorted(dirs):
    clip = clips[name]
    ax, ay = clip.get('ax'), clip.get('ay')
    if not isinstance(ax, int) or not isinstance(ay, int):
        errors.append(f'{name} 缺锚点 ax/ay')
    elif not (0 <= ax <= int(clip.get('w', 360)) and 0 <= ay <= int(clip.get('h', 360))):
        errors.append(f'{name} 锚点越界 ax={ax} ay={ay}')

if errors:
    print('\n'.join(errors))
    sys.exit(1)
wide = sum(1 for c in clips.values() if int(c.get('w', 360)) > 360)
print(f'OK: {len(dirs)} clips (宽内容 {wide} 段), 尺寸按 anim.json, alpha ok, i18n covered')
