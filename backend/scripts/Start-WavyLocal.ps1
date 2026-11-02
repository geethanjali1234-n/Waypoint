param([switch]$SetupOnly)

$ErrorActionPreference = 'Stop'
$wavyModel = 'qwen3:4b-instruct-2507-q4_K_M'
$wavyOllama = Get-Command ollama -ErrorAction SilentlyContinue
if (-not $wavyOllama) { throw 'Install Ollama from https://ollama.com/download, then rerun this script.' }

$env:OLLAMA_NO_CLOUD = '1'
try { $null = Invoke-RestMethod 'http://127.0.0.1:11434/api/tags' -TimeoutSec 3 }
catch {
    Start-Process -FilePath $wavyOllama.Source -ArgumentList 'serve' -WindowStyle Hidden
    $wavyReady = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Milliseconds 250
        try { $null = Invoke-RestMethod 'http://127.0.0.1:11434/api/tags' -TimeoutSec 1; $wavyReady = $true; break } catch { }
    }
    if (-not $wavyReady) { throw 'Ollama did not start. Open the Ollama application and retry.' }
}

try {
    $wavyDetails = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:11434/api/show' -ContentType 'application/json' -Body (@{model=$wavyModel} | ConvertTo-Json) -TimeoutSec 10
} catch {
    & $wavyOllama.Source pull $wavyModel
    if ($LASTEXITCODE -ne 0) { throw 'Model download failed. Workflow guidance remains available.' }
    $wavyDetails = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:11434/api/show' -ContentType 'application/json' -Body (@{model=$wavyModel} | ConvertTo-Json) -TimeoutSec 10
}
if ($wavyDetails.remote_host -or $wavyDetails.remote_model -or 'tools' -notin $wavyDetails.capabilities) {
    throw 'Wavy requires a local model with tool support.'
}

Write-Host 'Wavy local model is ready. No paid API key is required.'
if ($SetupOnly) { return }
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    throw 'Load the existing backend environment (including JWT_SECRET) before starting. Wavy does not replace application authentication settings.'
}
$env:WAVY_AI_ENABLED = 'true'
$env:WAVY_AI_PROVIDER = 'ollama'
$env:WAVY_AI_MODEL = $wavyModel
$env:WAVY_OLLAMA_URL = 'http://127.0.0.1:11434'
$env:WAVY_AI_API_KEY = ''
if (-not $env:JAVA_HOME) {
    $wavyJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($wavyJavac) { $env:JAVA_HOME = Split-Path (Split-Path $wavyJavac.Source -Parent) -Parent }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation before starting the backend.' }
Push-Location (Join-Path $PSScriptRoot '..')
try {
    & .\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false'
    if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed. Check whether its port is already in use.' }
} finally { Pop-Location }
