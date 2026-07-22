@echo off
chcp 65001 >nul
title WeStart 微信机器人登录

if "%WESTART_BASE_URL%"=="" set "WESTART_BASE_URL=http://localhost:8080"
set "WESTART_LOGIN_SCRIPT=%~f0"
set "WESTART_REQUESTED_ACCOUNT=%~1"

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$content = [IO.File]::ReadAllText($env:WESTART_LOGIN_SCRIPT, [Text.UTF8Encoding]::new($false)); $marker = '### POWERSHELL START ###'; $script = $content.Substring($content.LastIndexOf($marker) + $marker.Length); Invoke-Expression $script"
exit /b %errorlevel%

### POWERSHELL START ###
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

try {
    $baseUrl = $env:WESTART_BASE_URL
    $requestedAccount = $env:WESTART_REQUESTED_ACCOUNT
    $accounts = @(Invoke-RestMethod ($baseUrl + '/api/wechat/accounts') -TimeoutSec 10 | ForEach-Object { $_ })

    if ($requestedAccount) {
        $accountId = $requestedAccount
    } else {
        Write-Host ''
        Write-Host '=== WeStart 微信机器人账号 ===' -ForegroundColor Cyan

        for ($index = 0; $index -lt $accounts.Count; $index++) {
            $account = $accounts[$index]
            $loginText = if ($account.status.loggedIn) { '已登录' } else { '未登录' }
            Write-Host ('{0}. {1} [{2}] - {3}' -f ($index + 1), $account.displayName, $account.accountId, $loginText)
        }

        Write-Host 'N. 创建新的微信机器人账号' -ForegroundColor Green
        Write-Host ''
        $choice = (Read-Host '请输入账号编号，或输入 N 创建新账号').Trim()

        if ($choice -match '^[Nn]$') {
            $number = 2
            while ($accounts.accountId -contains ('bot-' + $number)) {
                $number++
            }

            $accountId = 'bot-' + $number
            $defaultName = '微信机器人' + $number + '号'
            $displayName = (Read-Host ('请输入机器人名称，直接回车使用“' + $defaultName + '”')).Trim()
            if (-not $displayName) {
                $displayName = $defaultName
            }

            $body = @{
                accountId = $accountId
                displayName = $displayName
            } | ConvertTo-Json

            $created = Invoke-RestMethod `
                -Method Post `
                -ContentType 'application/json; charset=utf-8' `
                -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
                ($baseUrl + '/api/wechat/accounts') `
                -TimeoutSec 10

            Write-Host ('已创建：' + $created.displayName + ' [' + $created.accountId + ']') -ForegroundColor Green
        } else {
            $selectedIndex = 0
            $validNumber = [int]::TryParse($choice, [ref]$selectedIndex)
            if (-not $validNumber -or $selectedIndex -lt 1 -or $selectedIndex -gt $accounts.Count) {
                throw '输入的账号编号无效。'
            }

            $accountId = $accounts[$selectedIndex - 1].accountId
        }
    }

    $result = Invoke-RestMethod `
        -Method Post `
        ($baseUrl + '/api/wechat/accounts/' + $accountId + '/login') `
        -TimeoutSec 30

    if ($result.qrCodePath) {
        Start-Process ($baseUrl + $result.qrCodePath)
        Write-Host ('已打开账号 ' + $accountId + ' 的登录二维码，请立即扫码。') -ForegroundColor Green
    } elseif ($result.alreadyLoggedIn) {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show(('账号 ' + $accountId + ' 已经登录。'), 'WeStart') | Out-Null
    } else {
        throw '登录接口没有返回二维码。'
    }
} catch {
    Write-Host ''
    Write-Host ('操作失败：' + $_.Exception.Message) -ForegroundColor Red
    Write-Host '请确认 WestartApplication 已经启动。' -ForegroundColor Yellow
    Read-Host '按回车键关闭窗口' | Out-Null
    exit 1
}
