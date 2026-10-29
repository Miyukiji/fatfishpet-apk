# 大肥鱼桌宠

Android 悬浮桌宠，包含 136 段透明动画。

装到手机上，授予「显示在其他应用上层」权限后，会以悬浮窗显示在桌面。点击、双击、长按、拖动、抛掷都有反应；空闲时会自己走动或打盹。

余额、本机用量、账户数据、天气、DSH 状态联动都是可选功能，不配置也能正常使用。

## 安装

从 [Releases](https://github.com/Miyukiji/fatfishpet-apk/releases) 下载 APK。首次启动时授予「显示在其他应用上层」权限。

可选配置：

- 余额：填 DeepSeek API Key，或登录账户
- 本机用量 / DSH 状态：安装桥插件（App 内「数据」页可导出）
- 天气：需要联网，定位权限可选
- 游戏或全屏时自动收起：开启无障碍；「先躲起来」还需要使用情况访问

## 构建

JDK 17 和 Android SDK 34，不用 Gradle。

```sh
VER_CODE=119 VER_NAME=1.2.5 tools/build.sh
tools/test.sh
```

Windows 用 `tools/build.ps1` 和 `tools/test.ps1`。签名密钥不在仓库里，构建前准备 `keystore.jks` 和本地 `.kspass`（或 `KS_PASS`）。

## 许可

代码 MIT。角色与动画素材来自 [dsh-whale-girl-pet](https://github.com/yanzwzz/dsh-whale-girl-pet) 和 [dsh-pet](https://github.com/PC2005-cloud/dsh-pet)，署名与许可见 [NOTICE.md](NOTICE.md)。
