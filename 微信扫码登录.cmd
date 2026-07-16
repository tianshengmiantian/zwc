@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "try { $r = Invoke-RestMethod -Method Post http://localhost:8080/api/wechat/login -TimeoutSec 30; if ($r.qrCodePath) { Start-Process ('http://localhost:8080' + $r.qrCodePath) } elseif ($r.alreadyLoggedIn) { Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.MessageBox]::Show('WeChat bot is already logged in.', 'WeStart') | Out-Null } else { throw 'The login API did not return a QR code path.' } } catch { Write-Host $_.Exception.Message -ForegroundColor Red; exit 1 }"
if errorlevel 1 (
    echo.
    pause
)
