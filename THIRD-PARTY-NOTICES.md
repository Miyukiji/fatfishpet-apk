# 第三方工具许可

本仓库不包含 Android SDK、JDK、ffmpeg 的二进制；构建时使用本机安装的版本。

## tools/r8.jar（D8 编译器）

- 项目：R8 — https://r8.googlesource.com/r8
- 版权：Copyright (c) 2016, the R8 project authors
- 许可：BSD 3-Clause（R8 自身代码）；其内置依赖另按 Apache-2.0 / MIT / BSD 分发
- 完整清单与文本：https://android.googlesource.com/platform/prebuilts/r8/+/refs/heads/android11-qpr3-s1-release/LICENSE

```
Copyright (c) 2016, the R8 project authors.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of Google Inc. nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## 其他

- Android SDK / build-tools（aapt2、zipalign、apksigner）：按 Android SDK 许可在本机使用，不随仓库分发
- ffmpeg（仅素材管线使用）：按本机安装版本许可，不随仓库分发
