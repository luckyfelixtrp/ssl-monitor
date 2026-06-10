"""
SSL 证书监控 - Windows 桌面启动器
启动一个本地 FastAPI 服务，并在 pywebview 原生窗口中打开页面。
"""
import logging
import socket
import sys
import threading
import time
from contextlib import closing

import uvicorn
import webview

# 让打包后的 exe 也能正确 import 同目录下的 backend 模块
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app import app  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("launcher")


def find_free_port(preferred: int = 18443) -> int:
    """优先使用 preferred，被占用则随机分配一个空闲端口"""
    with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as s:
        try:
            s.bind(("127.0.0.1", preferred))
            return preferred
        except OSError:
            s.bind(("127.0.0.1", 0))
            return s.getsockname()[1]


def wait_until_up(host: str, port: int, timeout: float = 15.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with closing(socket.create_connection((host, port), timeout=1.0)):
                return True
        except OSError:
            time.sleep(0.1)
    return False


def run_server(host: str, port: int) -> None:
    config = uvicorn.Config(app, host=host, port=port, log_level="warning", access_log=False)
    server = uvicorn.Server(config)
    server.run()


def main() -> None:
    port = find_free_port(18443)
    host = "127.0.0.1"
    url = f"http://{host}:{port}"
    log.info("starting backend at %s", url)

    server_thread = threading.Thread(target=run_server, args=(host, port), daemon=True)
    server_thread.start()

    if not wait_until_up(host, port, timeout=20):
        log.error("backend failed to start within 20s")
        sys.exit(1)

    log.info("opening webview window -> %s", url)
    webview.create_window(
        title="SSL 证书监控",
        url=url,
        width=1280,
        height=800,
        min_size=(960, 600),
        resizable=True,
        confirm_close=False,
    )
    webview.start()  # 阻塞直到窗口关闭
    log.info("window closed, exiting")


if __name__ == "__main__":
    main()
