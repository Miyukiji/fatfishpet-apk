#!/usr/bin/env bash
# 上游 webm → 本 App 的双区 VP9（上 RGB / 下 alpha）。
#
# 用法：
#   tools/build_assets.sh <上游 webm 目录> <输出目录>
# 输出文件名与上游一致（待机呼吸休闲 → idle.webm），再按 anim.json 改成 clipNN。
#
# 关键点（1.1.7 踩过的坑）：**输入也必须显式指定 `-c:v libvpx-vp9`**，
# 否则 ffmpeg 走默认 VP9 解码时拿不到 alpha 平面，出来的片子是不透明黑底。
set -euo pipefail
SRC=${1:?上游 webm 目录}
OUT=${2:?输出目录}
CRF=${CRF:-44}
mkdir -p "$OUT"

# 这些标题是横向构图（烟花/落叶/文字牌等），保留 640 宽；其余统一中心裁 360×360。
WIDE="鲸鱼吐泡泡特效 中秋赏月吃月饼 余额-金袋叮当 余额-钱袋满溢 动物环绕 吃晚餐 吃长寿面 吹气球 吹笛子 大口吃零食 放烟花 是啊，吃什么 深度思考碎碎念 玩水枪 穿针乞巧 蝴蝶蜜蜂环绕头顶开花 被落叶淹没 装点圣诞树"

is_wide() {
  for w in $WIDE; do
    [ "$1" = "$w" ] && return 0
  done
  return 1
}

for f in "$SRC"/*.webm; do
  b=$(basename "$f" .webm)
  if [ "$b" = "待机呼吸休闲" ]; then name="idle.webm"; else name="$b.webm"; fi
  if is_wide "$b"; then
    W=640
    CROP="crop=640:360:0:0"
  else
    W=360
    CROP="crop=360:360:(iw-360)/2:0"
  fi
  ffmpeg -y -v error -c:v libvpx-vp9 -i "$f" -filter_complex \
    "[0:v]${CROP},scale=${W}:360,format=yuva420p,split=2[a][b];[a]format=yuv420p[top];[b]alphaextract,format=gray[alpha];[top][alpha]vstack=inputs=2[out]" \
    -map "[out]" -an -c:v libvpx-vp9 -crf "$CRF" -b:v 0 -row-mt 1 -cpu-used 3 "$OUT/$name"
done
echo "done: $(ls "$OUT"/*.webm | wc -l) clips -> $OUT (crf=$CRF)"
