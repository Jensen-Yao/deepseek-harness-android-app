# Build script for DeepSeek Harness app (app2, gradle-free, multi-class)
$ErrorActionPreference = 'Continue'
$sdk = 'C:\Users\18052\AppData\Local\Android\Sdk'
$bt = "$sdk\build-tools\36.0.0"
$androidJar = "$sdk\platforms\android-36\android.jar"
$root = 'F:\dh talk\dsh-control'
$app = "$root\app2"
$out = "$root\build2"
$JAVA17 = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\java.exe'
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Step($label, $cmd, $argList) {
    Write-Output $label
    & $cmd @argList 2>&1 | ForEach-Object { Write-Output $_ }
    if ($LASTEXITCODE -ne 0) { throw "FAILED: $cmd (exit $LASTEXITCODE)" }
}

Step '[1/6] aapt2 compile + link' "$bt\aapt2.exe" @('compile','--dir',"$app\res",'-o',"$out\res.zip")
Step '[1b/6] aapt2 link' "$bt\aapt2.exe" @('link','-o',"$out\app.unsigned.apk",'-I',$androidJar,'--manifest',"$app\AndroidManifest.xml","$out\res.zip")

Write-Output '[2/6] javac compile'
New-Item -ItemType Directory -Force -Path "$out\classes" | Out-Null
$javaFiles = Get-ChildItem "$app\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $javaFiles) { throw 'no java files found' }
& javac --release 8 -encoding UTF-8 -classpath $androidJar -d "$out\classes" @javaFiles 2>&1 | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) { throw "FAILED: javac (exit $LASTEXITCODE)" }

Write-Output '[3/6] d8 dex'
New-Item -ItemType Directory -Force -Path "$out\dex" | Out-Null
$classFiles = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
if (-not $classFiles) { throw 'no class files found' }
& $JAVA17 -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 --lib $androidJar --release --min-api 24 --output "$out\dex" @classFiles 2>&1 | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) { throw "FAILED: d8 (exit $LASTEXITCODE)" }

Write-Output '[4/6] pack classes.dex'
Push-Location $out
& jar uf app.unsigned.apk -C dex classes.dex 2>&1 | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "FAILED: jar (exit $LASTEXITCODE)" }
Pop-Location

Step '[5/6] zipalign' "$bt\zipalign.exe" @('-f','4',"$out\app.unsigned.apk","$out\app.aligned.apk")

Write-Output '[6/6] sign'
if (-not (Test-Path "$out\dsh.keystore")) {
    Copy-Item "$root\build\dsh.keystore" "$out\dsh.keystore" -ErrorAction SilentlyContinue
    if (-not (Test-Path "$out\dsh.keystore")) {
        & keytool -genkeypair -keystore "$out\dsh.keystore" -alias dsh -keyalg RSA -keysize 2048 -validity 10000 -storepass dshctrl123 -keypass dshctrl123 -dname "CN=DSH Harness,O=DSH,C=CN" 2>&1 | Out-Null
    }
}
& $JAVA17 -cp "$bt\lib\apksigner.jar" com.android.apksigner.ApkSignerTool sign --ks "$out\dsh.keystore" --ks-pass pass:dshctrl123 --key-pass pass:dshctrl123 --out "$out\dsh-harness.apk" "$out\app.aligned.apk" 2>&1 | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) { throw "FAILED: apksigner (exit $LASTEXITCODE)" }

Write-Output "BUILD OK: $out\dsh-harness.apk"
Get-Item "$out\dsh-harness.apk" | Select-Object FullName, Length
