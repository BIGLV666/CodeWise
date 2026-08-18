# CodeWise 共享模块安装脚本
# 解决 IDE 每次加载时缺少依赖的问题
# 使用方法: .\install-shared-modules.ps1

Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "CodeWise 共享模块安装脚本" -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

# 1. 安装父 POM
Write-Host "[1/3] 安装父 POM (CodeWise)..." -ForegroundColor Yellow
.\mvnw.cmd -N install
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ 父 POM 安装失败！" -ForegroundColor Red
    exit 1
}
Write-Host "✅ 父 POM 安装成功" -ForegroundColor Green
Write-Host ""

# 2. 安装 service-api
Write-Host "[2/3] 安装 service-api..." -ForegroundColor Yellow
.\mvnw.cmd -f service-api\pom.xml -DskipTests clean install
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ service-api 安装失败！" -ForegroundColor Red
    exit 1
}
Write-Host "✅ service-api 安装成功" -ForegroundColor Green
Write-Host ""

# 3. 安装 service-common
Write-Host "[3/3] 安装 service-common..." -ForegroundColor Yellow
.\mvnw.cmd -f service-common\pom.xml -DskipTests clean install
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ service-common 安装失败！" -ForegroundColor Red
    exit 1
}
Write-Host "✅ service-common 安装成功" -ForegroundColor Green
Write-Host ""

Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "✅ 所有共享模块安装完成！" -ForegroundColor Green
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "提示：" -ForegroundColor Yellow
Write-Host "- 现在可以在 IDE 中重新加载 Maven 项目了" -ForegroundColor White
Write-Host "- 如果修改了 service-api 或 service-common，请重新运行此脚本" -ForegroundColor White
Write-Host ""
Write-Host "已知问题：" -ForegroundColor Yellow
Write-Host "- service-user 中的 api-governance-spring-boot-starter 依赖缺失已被注释" -ForegroundColor Gray
Write-Host "- service-question 中的 WebSocketPushService 类缺失，需要单独修复" -ForegroundColor Gray
