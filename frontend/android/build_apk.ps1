$ErrorActionPreference = "Stop"
$sdkRoot = "C:\Users\ultim\AppData\Local\Android\Sdk"
$jdkPath = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:JAVA_HOME = $jdkPath
$env:ANDROID_HOME = $sdkRoot
$env:PATH = "$jdkPath\bin;$sdkRoot\cmdline-tools\latest\bin;$sdkRoot\platform-tools;$env:PATH"

Write-Host "Configuring local.properties..."
$localProps = "sdk.dir=C:\\Users\\ultim\\AppData\\Local\\Android\\Sdk"
Set-Content -Path "c:\Users\ultim\IdeaProjects\Waypoint\frontend\android\local.properties" -Value $localProps

Write-Host "Building Android Debug APK with JDK 21..."
Set-Location "c:\Users\ultim\IdeaProjects\Waypoint\frontend\android"
cmd /c "gradlew.bat assembleDebug --no-daemon"

$apkSrc = "c:\Users\ultim\IdeaProjects\Waypoint\frontend\android\app\build\outputs\apk\debug\app-debug.apk"
$apkDestDir = "c:\Users\ultim\IdeaProjects\Waypoint\frontend\public\downloads"
New-Item -ItemType Directory -Force -Path $apkDestDir | Out-Null
$apkDest = "$apkDestDir\waypoint.apk"

if (Test-Path $apkSrc) {
    Copy-Item -Path $apkSrc -Destination $apkDest -Force
    Write-Host "SUCCESS: APK copied to $apkDest"
} else {
    Write-Error "APK file was not created at expected location $apkSrc"
}
