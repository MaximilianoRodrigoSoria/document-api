# Script: Inicializar repositorio y pushear a GitHub
# Ejecutar desde PowerShell en el directorio del proyecto

Set-Location "$PSScriptRoot"

Write-Host ">> Limpiando .git anterior si existe..." -ForegroundColor Yellow
if (Test-Path ".git") {
    Remove-Item -Recurse -Force ".git"
    Write-Host "   .git eliminado." -ForegroundColor Gray
}

Write-Host ">> Inicializando repositorio..." -ForegroundColor Yellow
git init -b master

Write-Host ">> Configurando remote origin..." -ForegroundColor Yellow
git remote add origin https://github.com/MaximilianoRodrigoSoria/document-api.git

Write-Host ">> Agregando todos los archivos..." -ForegroundColor Yellow
git add .

Write-Host ">> Creando commit inicial..." -ForegroundColor Yellow
git commit -m "initial commit"

Write-Host ">> Pusheando a master..." -ForegroundColor Yellow
git push -u origin master

Write-Host ""
Write-Host "✔ Listo. Repositorio inicializado y pusheado a GitHub." -ForegroundColor Green
