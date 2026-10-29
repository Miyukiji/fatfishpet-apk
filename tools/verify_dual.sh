#!/bin/bash
DIR=${1:-/root/work/petapk/assets-hd-final}
bad=0; n=0
for f in "$DIR"/*.webm; do
  n=$((n+1))
  v=$(ffmpeg -v error -y -i "$f" -frames:v 1 -vf "crop=iw:ih/2:0:ih/2" -f rawvideo -pix_fmt gray - 2>/dev/null | head -c 4 | od -An -tu1 | awk '{print $1}')
  if [ "${v:-255}" -gt 32 ]; then echo "BAD $f corner=$v"; bad=$((bad+1)); fi
done
echo "检查 $n 段，异常 $bad 段"
