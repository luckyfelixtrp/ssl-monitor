"""路径与运行环境工具：兼容源码运行与 PyInstaller 打包后的 exe 运行"""
import os
import sys
from pathlib import Path


def is_frozen() -> bool:
    """是否运行在 PyInstaller 打包后的 exe 中"""
    return getattr(sys, "frozen", False)


def app_root() -> Path:
    """
    应用根目录:
    - 源码运行: 项目根目录 (ssl-monitor/)
    - PyInstaller onefile: exe 所在目录
    - PyInstaller onedir : exe 所在目录
    """
    if is_frozen():
        return Path(sys.executable).parent
    # backend/utils.py -> backend -> project root
    return Path(__file__).resolve().parent.parent


def resource_dir() -> Path:
    """
    打包后的资源目录（前端 HTML 等）:
    - 源码运行: 项目根目录
    - PyInstaller onefile: 临时解压目录 sys._MEIPASS
    - PyInstaller onedir : exe 所在目录
    """
    if is_frozen():
        meipass = getattr(sys, "_MEIPASS", None)
        if meipass:
            return Path(meipass)
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


def data_dir() -> Path:
    """
    数据目录（SQLite 存放位置）。优先级：
    1. 环境变量 SSL_MONITOR_DATA_DIR
    2. 默认: exe 同目录 / 项目根目录 下的 data/
    """
    env = os.environ.get("SSL_MONITOR_DATA_DIR")
    if env:
        p = Path(env)
    else:
        p = app_root() / "data"
    p.mkdir(parents=True, exist_ok=True)
    return p


def frontend_dir() -> Path:
    """前端 HTML 所在目录"""
    return resource_dir() / "frontend"
