"""FastAPI 主入口"""
import asyncio
import logging
import re
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlparse

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import database as db
from checker import check_certificate
from utils import frontend_dir

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("ssl-monitor")

FRONTEND_DIR = frontend_dir()
scheduler = AsyncIOScheduler()

# 主机名合法字符（不含端口与协议）
_HOSTNAME_RE = re.compile(r"^[a-z0-9]([a-z0-9\-\.]{0,251}[a-z0-9])?$")


def normalize_host_port(raw_host: str, raw_port: int) -> tuple[str, int]:
    """
    清洗用户输入：
    - 去掉前后空格
    - 剥掉 http:// / https:// 协议前缀
    - 剥掉路径、查询串、fragment
    - 如带端口（example.com:8443）则覆盖 raw_port
    - 转小写
    返回 (host, port)，非法时抛 ValueError
    """
    s = (raw_host or "").strip()
    if not s:
        raise ValueError("域名不能为空")

    # 如果包含协议或斜杠，按 URL 解析
    if "://" in s or s.startswith("//"):
        if s.startswith("//"):
            s = "http:" + s
        parsed = urlparse(s)
        host = (parsed.hostname or "").lower()
        port = parsed.port or raw_port
    else:
        # 形如 host 或 host:port 或 host:port/path
        s = s.split("/", 1)[0]  # 去掉路径
        if ":" in s:
            host_part, _, port_part = s.partition(":")
            host = host_part.lower()
            try:
                port = int(port_part)
            except ValueError:
                raise ValueError(f"端口格式错误: {port_part}")
        else:
            host = s.lower()
            port = raw_port

    if not host:
        raise ValueError("解析不出有效的域名")
    if not _HOSTNAME_RE.match(host):
        raise ValueError(f"域名格式不合法: {host}（请只填主机名，例如 example.com）")
    if not (1 <= port <= 65535):
        raise ValueError(f"端口超出范围: {port}")
    return host, port


async def scan_all_domains() -> None:
    """定时任务: 扫描所有域名"""
    domains = db.all_domains_simple()
    log.info("scheduled scan: %d domains", len(domains))
    sem = asyncio.Semaphore(5)

    async def _one(d):
        async with sem:
            try:
                payload = await check_certificate(d["host"], d["port"])
                db.save_check_result(d["id"], payload)
            except Exception as e:
                log.exception("check failed for %s: %s", d["host"], e)

    await asyncio.gather(*[_one(d) for d in domains])


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    # 每天 03:00 自动扫描一次
    scheduler.add_job(scan_all_domains, CronTrigger(hour=3, minute=0), id="daily_scan")
    scheduler.start()
    log.info("scheduler started, daily scan at 03:00 (server local time)")
    yield
    scheduler.shutdown()


app = FastAPI(title="SSL Monitor", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------- Schemas ----------
class DomainIn(BaseModel):
    host: str = Field(..., min_length=1, max_length=253)
    port: int = Field(443, ge=1, le=65535)
    remark: str = Field("", max_length=200)


# ---------- API ----------
@app.get("/api/domains")
def api_list_domains():
    return db.list_domains()


@app.post("/api/domains", status_code=201)
async def api_add_domain(payload: DomainIn):
    try:
        host, port = normalize_host_port(payload.host, payload.port)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    try:
        domain_id = db.add_domain(host, port, payload.remark)
    except Exception as e:
        if "UNIQUE" in str(e):
            raise HTTPException(status_code=409, detail=f"域名 {host}:{port} 已存在，请勿重复添加")
        raise HTTPException(status_code=400, detail=f"添加失败: {e}")
    # 立刻执行一次检测
    try:
        result = await check_certificate(host, port)
        db.save_check_result(domain_id, result)
    except Exception as e:
        log.warning("initial check failed: %s", e)
    return {"id": domain_id, "host": host, "port": port}


@app.put("/api/domains/{domain_id}")
def api_update_domain(domain_id: int, payload: DomainIn):
    if not db.get_domain(domain_id):
        raise HTTPException(status_code=404, detail="域名不存在")
    try:
        host, port = normalize_host_port(payload.host, payload.port)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    db.update_domain(domain_id, host, port, payload.remark)
    return {"ok": True, "host": host, "port": port}


@app.delete("/api/domains/{domain_id}")
def api_delete_domain(domain_id: int):
    db.delete_domain(domain_id)
    return {"ok": True}


@app.post("/api/domains/{domain_id}/check")
async def api_check_one(domain_id: int):
    d = db.get_domain(domain_id)
    if not d:
        raise HTTPException(status_code=404, detail="域名不存在")
    result = await check_certificate(d["host"], d["port"])
    db.save_check_result(domain_id, result)
    return result


@app.post("/api/check-all")
async def api_check_all():
    asyncio.create_task(scan_all_domains())
    return {"ok": True, "message": "扫描已在后台执行"}


@app.get("/api/domains/{domain_id}/detail")
def api_detail(domain_id: int):
    detail = db.get_latest_detail(domain_id)
    if not detail:
        raise HTTPException(status_code=404, detail="尚无检测记录")
    return detail


@app.get("/api/domains/{domain_id}/history")
def api_history(domain_id: int, limit: int = 30):
    return db.get_history(domain_id, limit)


@app.get("/api/health")
def health():
    return {"status": "ok"}


# ---------- Frontend ----------
if FRONTEND_DIR.exists():
    app.mount("/", StaticFiles(directory=FRONTEND_DIR, html=True), name="static")
