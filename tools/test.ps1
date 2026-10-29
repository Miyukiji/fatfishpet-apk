# Pure-JVM unit tests for the whale pet (Windows / PowerShell).
# Mirrors tools/test.sh: generate R.java with aapt2, compile the data-layer classes
# against android.jar (symbols only), then run TestBalance / TestPhysics / TestWeather.
#   $env:ANDROID_SDK='C:\...\sdk'; ./tools/test.ps1
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ROOT = Split-Path -Parent $PSScriptRoot
$SDK = if ($env:ANDROID_SDK) { $env:ANDROID_SDK } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Users\rmtrk\Android\sdk' }
$BT = if ($env:ANDROID_BUILD_TOOLS) { $env:ANDROID_BUILD_TOOLS } else { Join-Path $SDK 'build-tools\34.0.0' }
if ($env:JAVA_HOME) { $env:Path = "$($env:JAVA_HOME)\bin;" + $env:Path }
$JAVAC = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$JAVA = Join-Path $env:JAVA_HOME 'bin\java.exe'
$AAPT2 = Join-Path $BT 'aapt2.exe'
$PLATFORM = Join-Path $SDK 'platforms\android-34\android.jar'
$SRC = Join-Path $ROOT 'app\src\com\dsha\whalepet'
$TESTS = Join-Path $ROOT 'tests'

$OUT = Join-Path $env:TEMP ("whalepet-test-" + [guid]::NewGuid().ToString('N'))
$GEN = Join-Path $OUT 'gen'
New-Item -ItemType Directory -Force $GEN | Out-Null

try {
  & $AAPT2 compile --dir (Join-Path $ROOT 'app\res') -o (Join-Path $OUT 'res.zip')
  if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
  & $AAPT2 link -o (Join-Path $OUT 'test.apk') -I $PLATFORM `
    --manifest (Join-Path $ROOT 'app\AndroidManifest.xml') `
    -R (Join-Path $OUT 'res.zip') --java $GEN --auto-add-overlay | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

  $files = @(
    (Join-Path $GEN 'com\dsha\whalepet\R.java'),
    (Join-Path $SRC 'MiniJson.java'), (Join-Path $SRC 'BalanceFmt.java'), (Join-Path $SRC 'BalanceClient.java'),
    (Join-Path $SRC 'Http.java'), (Join-Path $SRC 'Weather.java'), (Join-Path $SRC 'Physics.java'),
    (Join-Path $SRC 'Log.java'), (Join-Path $SRC 'Txt.java'),
    (Join-Path $TESTS 'TestBalance.java'), (Join-Path $TESTS 'TestPhysics.java'), (Join-Path $TESTS 'TestWeather.java')
  )
  & $JAVAC -encoding UTF-8 -cp "$PLATFORM;$GEN" -d $OUT @files
  if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

  $fail = 0
  foreach ($t in 'TestBalance', 'TestPhysics', 'TestWeather') {
    Write-Host "===== $t"
    & $JAVA '-Dfile.encoding=UTF-8' -cp "$OUT;$PLATFORM" "com.dsha.whalepet.$t"
    if ($LASTEXITCODE -ne 0) { $fail = 1 }
  }
  if ($fail -ne 0) { throw 'unit tests failed' }
} finally {
  Remove-Item $OUT -Recurse -Force -ErrorAction SilentlyContinue
}
