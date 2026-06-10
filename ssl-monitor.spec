# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec - SSL 证书监控 Windows 桌面版"""

import sys
from pathlib import Path

block_cipher = None

ROOT = Path(SPECPATH)

# ---- 收集前端文件 ----
# 桌面版直接用 FastAPI 托管 Vue 构建产物
# 如果 frontend/dist/ 不存在，先执行 cd frontend && npm install && npm run build
frontend_dist = ROOT / 'frontend' / 'dist'
if not frontend_dist.exists():
    print(f"ERROR: {frontend_dist} 不存在，请先构建前端：")
    print("  cd frontend && npm install && npm run build")
    sys.exit(1)

frontend_datas = [(str(frontend_dist), 'frontend/dist')]

# ---- 收集后端代码 ----
backend_datas = [
    (str(ROOT / 'backend'), 'backend'),
]

a = Analysis(
    [str(ROOT / 'backend' / 'launcher.py')],
    pathex=[str(ROOT / 'backend')],
    binaries=[],
    datas=frontend_datas + backend_datas,
    hiddenimports=[
        'uvicorn.logging',
        'uvicorn.loops',
        'uvicorn.loops.auto',
        'uvicorn.protocols',
        'uvicorn.protocols.http',
        'uvicorn.protocols.http.auto',
        'uvicorn.protocols.websockets',
        'uvicorn.protocols.websockets.auto',
        'uvicorn.lifespan',
        'uvicorn.lifespan.on',
        'apiclient',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='SSL证书监控',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,           # 不弹出黑色控制台窗口
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=None,               # 如有图标: icon='assets/icon.ico'
)