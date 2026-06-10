@echo off
chcp 65001 >nul 2>&1
echo 启动 SSL 证书监控（开发模式）...
echo.

:: 创建虚拟环境（首次）
if not exist .venv\Scripts\activate.bat (
    python -m venv .venv
)

call .venv\Scripts\activate.bat
pip install -r backend\requirements.txt >nul 2>&1

python backend\launcher.py