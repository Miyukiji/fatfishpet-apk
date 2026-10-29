#!/usr/bin/env python3
"""把 bridge/dsha-whalepet-bridge 的可分发文件重新打进 app/assets/plugin/whalepet-bridge.zip。

App 里「导出插件」就是把这个 zip 写到下载目录，所以它必须与插件源码保持一致
（package.json 的 version 决定导出文件名，见 MainActivity.PLUGIN_ZIP）。
"""
import pathlib
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
src = root / 'bridge/dsha-whalepet-bridge'
out = root / 'app/assets/plugin/whalepet-bridge.zip'

files = ['package.json', 'README.md', 'cordis.patch.yml']
files += [f'lib/{p.name}' for p in sorted((src / 'lib').glob('*.js'))]

out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for rel in files:
        z.write(src / rel, 'whalepet-bridge/' + rel)

print(f'{out.relative_to(root)}: {len(files)} entries')
