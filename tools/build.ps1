#!/usr/bin/env pwsh
# 纯手工 Android 构建（Windows / PowerShell）：aapt2 编资源 -> javac -> d8 -> 打包 -> zipalign -> apksigner
# 与 tools/build.sh 等价，供电脑上没有 bash 时使用。
#   $env:ANDROID_SDK='C:\...\sdk'; $env:VER_CODE=112; $env:VER_NAME='1.1.8'; ./tools/build.ps1
param(
  [string]$VerCode = $(if ($env:VER_CODE) { $env:VER_CODE } else { '1' }),
  [string]$VerName = $(if ($env:VER_NAME) { $env:VER_NAME } else { '1.0.0' })
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ROOT = Split-Path -Parent $PSScriptRoot
$SDK = if ($env:ANDROID_SDK) { $env:ANDROID_SDK } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Users\rmtrk\Android\sdk' }
$BT = if ($env:ANDROID_BUILD_TOOLS) { $env:ANDROID_BUILD_TOOLS } else { Join-Path $SDK 'build-tools\34.0.0' }
if ($env:JAVA_HOME) { $env:Path = "$($env:JAVA_HOME)\bin;" + $env:Path }
$JAVAC = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$JAVA = Join-Path $env:JAVA_HOME 'bin\java.exe'

$AAPT2 = Join-Path $BT 'aapt2.exe'
$ZIPALIGN = Join-Path $BT 'zipalign.exe'
$APKSIGNER = Join-Path $BT 'apksigner.bat'
$PLATFORM = Join-Path $SDK 'platforms\android-34\android.jar'
$APP = Join-Path $ROOT 'app'
$BUILD = Join-Path $ROOT 'build'
$OUT = if ($env:OUT) { $env:OUT } else { Join-Path $ROOT 'dist' }
$MIN_SDK = 29
$TARGET_SDK = if ($env:TARGET_SDK) { [int]$env:TARGET_SDK } else { 30 }
$KS = Join-Path $ROOT 'keystore.jks'
$KS_PASS = if ($env:WHALEPET_KS_PASS) { $env:WHALEPET_KS_PASS } elseif ($env:KS_PASS) { $env:KS_PASS } elseif (Test-Path (Join-Path $ROOT '.kspass')) { (Get-Content (Join-Path $ROOT '.kspass') -Raw).Trim() } else { '' }
if ([string]::IsNullOrWhiteSpace($KS_PASS)) { throw '缺少 keystore 口令：设置 KS_PASS 或本地 .kspass' }
$KS_ALIAS = 'whalepet'
$PY = if (Get-Command python -ErrorAction SilentlyContinue) { 'python' } else { 'py' }

if (-not (Test-Path $JAVAC)) { throw "找不到 javac：请设置 JAVA_HOME（当前 '$env:JAVA_HOME'）" }
if (-not (Test-Path $AAPT2)) { throw "找不到 aapt2：请设置 ANDROID_SDK（当前 '$SDK'）" }

if (Test-Path $BUILD) { Remove-Item $BUILD -Recurse -Force }
New-Item -ItemType Directory -Force (Join-Path $BUILD 'compiled'), (Join-Path $BUILD 'gen'), (Join-Path $BUILD 'classes'), (Join-Path $BUILD 'dex'), $OUT | Out-Null

Write-Host '== 1/6 aapt2 compile'
& $AAPT2 compile --dir (Join-Path $APP 'res') -o (Join-Path $BUILD 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

# 版本号写进临时清单
$manifest = Get-Content (Join-Path $APP 'AndroidManifest.xml') -Raw -Encoding UTF8
$manifest = $manifest -replace 'android:versionCode="[0-9]*"', "android:versionCode=`"$VerCode`""
$manifest = $manifest -replace 'android:versionName="[^"]*"', "android:versionName=`"$VerName`""
$manifest = $manifest -replace 'android:targetSdkVersion="[0-9]*"', "android:targetSdkVersion=`"$TARGET_SDK`""
[System.IO.File]::WriteAllText((Join-Path $BUILD 'AndroidManifest.xml'), $manifest, (New-Object System.Text.UTF8Encoding($false)))

Write-Host '== 2/6 aapt2 link'
& $AAPT2 link `
  -o (Join-Path $BUILD 'base.apk') `
  -I $PLATFORM `
  --manifest (Join-Path $BUILD 'AndroidManifest.xml') `
  -R (Join-Path $BUILD 'res.zip') `
  --java (Join-Path $BUILD 'gen') `
  --min-sdk-version $MIN_SDK `
  --target-sdk-version $TARGET_SDK `
  --version-code $VerCode `
  --version-name $VerName `
  --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '== 3/6 javac'
$sources = @(Get-ChildItem (Join-Path $APP 'src'), (Join-Path $BUILD 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
[System.IO.File]::WriteAllLines((Join-Path $BUILD 'sources.txt'), $sources, (New-Object System.Text.UTF8Encoding($false)))
& $JAVAC --release 11 -encoding UTF-8 -classpath $PLATFORM -d (Join-Path $BUILD 'classes') "@$(Join-Path $BUILD 'sources.txt')"
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '== 4/6 d8'
$classes = @(Get-ChildItem (Join-Path $BUILD 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName })
[System.IO.File]::WriteAllLines((Join-Path $BUILD 'classes.txt'), $classes, (New-Object System.Text.UTF8Encoding($false)))
& $JAVA -cp (Join-Path $ROOT 'tools\r8.jar') com.android.tools.r8.D8 --lib $PLATFORM --min-api $MIN_SDK --release --output (Join-Path $BUILD 'dex') "@$(Join-Path $BUILD 'classes.txt')"
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '== 5/6 组装（assets + classes.dex）'
$pyCode = @'
import os, sys, zipfile
base, dex, assets, out = sys.argv[1:5]
src = zipfile.ZipFile(base)
dst = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for it in src.infolist():
    dst.writestr(it, src.read(it.filename))
dst.write(dex, 'classes.dex')
n = 0
for dirpath, _dirs, files in os.walk(assets):
    for f in sorted(files):
        full = os.path.join(dirpath, f)
        rel = os.path.relpath(full, assets)
        st = zipfile.ZIP_STORED if f.endswith('.webm') else zipfile.ZIP_DEFLATED
        dst.write(full, 'assets/' + rel.replace(os.sep, '/'), compress_type=st)
        n += 1
dst.close()
print('assets entries:', n)
'@
$pyFile = Join-Path $BUILD 'assemble.py'
[System.IO.File]::WriteAllText($pyFile, $pyCode, (New-Object System.Text.UTF8Encoding($false)))
& $PY $pyFile (Join-Path $BUILD 'base.apk') (Join-Path $BUILD 'dex\classes.dex') (Join-Path $APP 'assets') (Join-Path $BUILD 'unsigned.apk')
if ($LASTEXITCODE -ne 0) { throw '组装失败' }

Write-Host '== 6/6 zipalign + apksigner'
& $ZIPALIGN -f -p 4 (Join-Path $BUILD 'unsigned.apk') (Join-Path $BUILD 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

if (-not (Test-Path $KS)) {
  & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $KS -alias $KS_ALIAS `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $KS_PASS -keypass $KS_PASS `
    -dname "CN=WhalePet, OU=DSHA, O=dsha, L=Local, S=Local, C=CN"
}

$APK = Join-Path $OUT "whalepet-$VerName.apk"
& $APKSIGNER sign --ks $KS --ks-key-alias $KS_ALIAS `
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" `
  --min-sdk-version $MIN_SDK `
  --out $APK (Join-Path $BUILD 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign 失败' }

& $APKSIGNER verify --min-sdk-version $MIN_SDK $APK
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify 失败' }
Write-Host ("APK: {0}  ({1} bytes)" -f $APK, (Get-Item $APK).Length)
