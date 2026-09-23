# Launch AutoHeal services natively for local development
Write-Host "Starting AutoHeal Platform in Local Mode..." -ForegroundColor Cyan

$root = $PSScriptRoot

Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\service-a'; Write-Host '--- Starting Service A (8081) ---' -ForegroundColor Green; .\mvnw.cmd spring-boot:run"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\service-b'; Write-Host '--- Starting Service B (8082) ---' -ForegroundColor Green; .\mvnw.cmd spring-boot:run"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\service-c'; Write-Host '--- Starting Service C (8083) ---' -ForegroundColor Green; .\mvnw.cmd spring-boot:run"

Start-Sleep -Seconds 3

Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\monitor-service'; Write-Host '--- Starting Monitor Service & Dashboard (8085) ---' -ForegroundColor Cyan; .\mvnw.cmd spring-boot:run"

Write-Host "Services are starting in separate windows." -ForegroundColor Green
Write-Host "Once initialized, access the dashboard at: http://localhost:8085/" -ForegroundColor Yellow
Start-Process "http://localhost:8085/"
