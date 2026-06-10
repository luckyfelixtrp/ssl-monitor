@echo off
chcp 65001 >nul 2>&1
echo ========================================
echo   SSL 证书监控 - 一键打包 Windows 桌面版
echo ========================================
echo.

:: 检查 Python
where python >nul 2>&1 || (echo ERROR: 未找到 Python，请先安装 Python 3.10+ && pause && exit /b 1)

:: 创建虚拟环境
if not exist .venv\Scripts\activate.bat (
    echo [1/4] 创建虚拟环境...
    python -m venv .venv
    if errorlevel 1 (echo ERROR: 创建 venv 失败 && pause && exit /b 1)
)

:: 安装依赖
echo [2/4] 安装 Python 依赖...
call .venv\Scripts\activate.bat
pip install -r backend\requirements.txt
if errorlevel 1 (echo ERROR: pip install 失败 && pause && exit /b 1)

:: 构建前端
echo [3/4] 构建前端...
where npm >nul 2>&1 || (echo ERROR: 未找到 npm，请先安装 Node.js && pause && exit /b 1)
cd frontend
if not exist node_modules (
    call npm install
    if errorlevel 1 (echo ERROR: npm install 失败 && pause && exit /b 1)
)
call npm run build
if errorlevel 1 (echo ERROR: npm run build 失败 && pause && exit /b 1)
cd ..

:: PyInstaller 打包
echo [4/4] PyInstaller 打包...
pyinstaller ssl-monitor.spec --noconfirm
if errorlevel 1 (echo ERROR: 打包失败 && pause && exit /b 1)

echo.
echo ========================================
echo   打包完成！
echo   exe 位置: dist\SSL证书监控.exe
echo   使用: 将 exe 放到空目录运行（会自动创建 data\）
echo ========================================
pause