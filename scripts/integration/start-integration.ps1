#Requires -Version 7.0
param([ValidateSet('all','backend','admin','mall')][string]$Service='all',[switch]$Build)
$ErrorActionPreference='Stop'
$taskBackend=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$taskWorkspace=Split-Path $taskBackend -Parent
$taskRuntime=Join-Path $taskBackend '.hexu-local'
# 管理端、小程序仓库与后端仓库同级；目录名不同时用 HEXU_ADMIN_DIR / HEXU_APP_DIR 指定。
function Find-TaskRepo([string]$Override,[string[]]$Names){if($Override){return $Override};foreach($taskCandidate in $Names){$taskCandidatePath=Join-Path $taskWorkspace $taskCandidate;if(Test-Path -LiteralPath $taskCandidatePath){return $taskCandidatePath}};Join-Path $taskWorkspace $Names[0]}
$taskAdmin=Find-TaskRepo $env:HEXU_ADMIN_DIR @('RuoYi-Vue3','hexu-admin')
$taskMall=Find-TaskRepo $env:HEXU_APP_DIR @('分销批发中台小程序','hexu-app','fenxiao-app')
New-Item -ItemType Directory -Path $taskRuntime -Force | Out-Null
# 本地联调需要开发登录与沙盒支付；未显式设置时在此打开。
if($Service -eq 'backend') {Set-Location -LiteralPath $taskBackend; if(!$env:HEXU_SANDBOX_ENABLED){$env:HEXU_SANDBOX_ENABLED='true'}; & ./scripts/hexu-local.ps1 -Action run;exit}
if($Service -eq 'admin') {Set-Location -LiteralPath $taskAdmin; & node node_modules/vite/bin/vite.js --config vite.hexu.config.js;exit}
if($Service -eq 'mall') {Set-Location -LiteralPath $taskMall; & node scripts/run.mjs -p h5 --host 127.0.0.1 --port 4177;exit}
if($Build) {
    Push-Location -LiteralPath $taskBackend
    try {
        & mvn -q -pl ruoyi-admin -am package '-Dtest=HexuBusinessTest' '-Dsurefire.failIfNoSpecifiedTests=false'
        if($LASTEXITCODE -ne 0){throw 'Backend tests or packaging failed.'}
    } finally {Pop-Location}
}
if(!(Test-Path -LiteralPath (Join-Path $taskBackend 'ruoyi-admin/target/ruoyi-admin.jar'))){throw 'Build backend first, or run with -Build.'}
Push-Location -LiteralPath $taskBackend
try {& ./scripts/hexu-local.ps1 -Action migrate} finally {Pop-Location}
foreach($taskEntry in @(@('backend',8088),@('admin',5180),@('mall',4177))) {
    $taskName=$taskEntry[0];$taskPort=$taskEntry[1]
    if(Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue){Write-Output "Port $taskPort already in use; left unchanged.";continue}
    $taskProcess=Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList @('-NoLogo','-NoProfile','-File',('"'+$PSCommandPath+'"'),'-Service',$taskName) -WindowStyle Hidden -WorkingDirectory $taskWorkspace -RedirectStandardOutput (Join-Path $taskRuntime "$taskName.stdout.log") -RedirectStandardError (Join-Path $taskRuntime "$taskName.stderr.log") -PassThru
    Write-Output "Started $taskName on port $taskPort (launcher PID $($taskProcess.Id))."
}
$taskDeadline=[DateTime]::UtcNow.AddSeconds(40)
$taskReady=$false
while([DateTime]::UtcNow -lt $taskDeadline){
    try {$taskHealth=Invoke-RestMethod -Uri 'http://127.0.0.1:8088/hexu/app/shops' -TimeoutSec 2; if($taskHealth.code -eq 200){$taskReady=$true;break}} catch {}
    Start-Sleep -Milliseconds 500
}
if(!$taskReady){throw 'Backend did not become ready within 40 seconds; inspect .hexu-local/backend logs.'}
Write-Output 'Backend health check passed.'
Write-Output 'Mall: http://127.0.0.1:4177/#/pages/M03/index?member=201&shop=2'
Write-Output 'Merchant: http://127.0.0.1:4177/#/pages/G03/index?member=101&shop=2'
Write-Output 'Admin: http://127.0.0.1:5180/hexu/overview'
