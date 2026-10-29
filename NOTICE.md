# 第三方素材与署名

本仓库的**代码**（Android App、DSH 桥插件、构建脚本）采用 [MIT](LICENSE)。

**角色与动画素材不是 MIT**，来自以下两个上游，**禁止商用**。

## 1. dsh-whale-girl-pet（旧版 50 段）

- 仓库：https://github.com/yanzwzz/dsh-whale-girl-pet
- 代码与素材：MIT（Copyright (c) 2026 dsh-whale-girl-pet contributors）
- 本项目用途：旧版 50 段动画（原生 360×360），经裁切、VP9 双区重编码

## 2. dsh-pet（新增 86 段）

- 仓库：https://github.com/PC2005-cloud/dsh-pet
- 代码：MIT（Copyright (c) 2026 PC2005-cloud）
- 素材（动画 / 提示词 / 源视频）：**允许开源使用，禁止商用**
- **二创约定**：基于本项目的衍生 / 改版 / 换皮作品，在 GitHub 仓库、产品官网等公开渠道上须附上原作者地址：
  https://github.com/PC2005-cloud/dsh-pet
- 形象出处：社区二创的 DeepSeek「大肥鱼」（鲸鱼娘），**非 DeepSeek 官方形象**
  - 原型「溟月」：B 站 [@上善无形](https://space.bilibili.com/4456176)
  - DeepSeek 版（女仆鲸鱼娘）设计：B 站 [@ZipZipPipe](https://space.bilibili.com/4168597)
- 形象授权：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)（署名 · 非商业 · 相同方式共享）
- 本项目用途：新增 86 段动画（原生 640×360），其中 18 段保留 640 宽；经裁切、逐帧稳定、VP9 双区重编码

## 修改说明

本仓库中的 `app/assets/video/*.webm` 不是上游原片，而是衍生版本：

- 统一转成双区 VP9（上半 RGB / 下半 alpha），360×720 或 640×720
- 中心裁切、缩放、部分片段逐帧水平稳定
- 由单次 VP9 编码生成（CRF 44）

依 CC BY-NC-SA 4.0 的「相同方式共享」，上述衍生素材同样按 **CC BY-NC-SA 4.0（非商业）** 提供。

## 使用限制

- **不得用于商业用途**：包括付费分发、广告变现、赞助换权益等
- 转载、分发、二次创作必须保留本文件中的全部署名、链接与许可说明
- 不得暗示本项目或形象与 DeepSeek 官方有关

上游许可原文：
- https://github.com/PC2005-cloud/dsh-pet#许可
- https://github.com/yanzwzz/dsh-whale-girl-pet/blob/main/LICENSE
