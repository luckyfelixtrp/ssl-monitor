"""SQLite 数据访问层"""
import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from utils import data_dir

DB_PATH = data_dir() / "ssl_monitor.db"


def init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with get_conn() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS domains (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                host TEXT NOT NULL,
                port INTEGER NOT NULL DEFAULT 443,
                remark TEXT DEFAULT '',
                created_at TEXT NOT NULL,
                UNIQUE(host, port)
            );

            CREATE TABLE IF NOT EXISTS check_results (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                domain_id INTEGER NOT NULL,
                checked_at TEXT NOT NULL,
                ok INTEGER NOT NULL,
                error TEXT,
                days_left INTEGER,
                not_after TEXT,
                grade TEXT,
                score INTEGER,
                tls_version TEXT,
                cipher TEXT,
                reachable INTEGER,
                status_code INTEGER,
                response_ms INTEGER,
                payload TEXT NOT NULL,
                FOREIGN KEY(domain_id) REFERENCES domains(id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_check_domain ON check_results(domain_id, checked_at DESC);
            """
        )


@contextmanager
def get_conn() -> Iterator[sqlite3.Connection]:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def list_domains() -> list[dict[str, Any]]:
    """返回所有域名 + 最新一次检测结果"""
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT d.id, d.host, d.port, d.remark, d.created_at,
                   r.checked_at, r.ok, r.error, r.days_left, r.not_after,
                   r.grade, r.score, r.tls_version, r.cipher,
                   r.reachable, r.status_code, r.response_ms
            FROM domains d
            LEFT JOIN check_results r ON r.id = (
                SELECT id FROM check_results WHERE domain_id = d.id
                ORDER BY checked_at DESC LIMIT 1
            )
            ORDER BY
              CASE WHEN r.days_left IS NULL THEN 1 ELSE 0 END,
              r.days_left ASC,
              d.host ASC
            """
        ).fetchall()
        return [dict(row) for row in rows]


def add_domain(host: str, port: int = 443, remark: str = "") -> int:
    with get_conn() as conn:
        cursor = conn.execute(
            "INSERT INTO domains (host, port, remark, created_at) VALUES (?, ?, ?, ?)",
            (host, port, remark, datetime.now(timezone.utc).isoformat()),
        )
        return cursor.lastrowid


def delete_domain(domain_id: int) -> None:
    with get_conn() as conn:
        conn.execute("DELETE FROM domains WHERE id = ?", (domain_id,))


def update_domain(domain_id: int, host: str, port: int, remark: str) -> None:
    with get_conn() as conn:
        conn.execute(
            "UPDATE domains SET host=?, port=?, remark=? WHERE id=?",
            (host, port, remark, domain_id),
        )


def get_domain(domain_id: int) -> dict[str, Any] | None:
    with get_conn() as conn:
        row = conn.execute("SELECT * FROM domains WHERE id = ?", (domain_id,)).fetchone()
        return dict(row) if row else None


def all_domains_simple() -> list[dict[str, Any]]:
    with get_conn() as conn:
        rows = conn.execute("SELECT id, host, port FROM domains").fetchall()
        return [dict(r) for r in rows]


def save_check_result(domain_id: int, payload: dict[str, Any]) -> None:
    cert = payload.get("cert") or {}
    tls = payload.get("tls") or {}
    sec = payload.get("security") or {}
    avail = payload.get("availability") or {}

    with get_conn() as conn:
        conn.execute(
            """
            INSERT INTO check_results
            (domain_id, checked_at, ok, error, days_left, not_after, grade, score,
             tls_version, cipher, reachable, status_code, response_ms, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                domain_id,
                payload.get("checked_at"),
                1 if payload.get("ok") else 0,
                payload.get("error"),
                cert.get("days_left"),
                cert.get("not_after"),
                sec.get("grade"),
                sec.get("score"),
                tls.get("version"),
                tls.get("cipher"),
                1 if avail.get("reachable") else 0,
                avail.get("status_code"),
                avail.get("response_ms"),
                json.dumps(payload, ensure_ascii=False),
            ),
        )
        # 仅保留每个域名最近 50 条历史
        conn.execute(
            """
            DELETE FROM check_results
            WHERE domain_id = ? AND id NOT IN (
                SELECT id FROM check_results
                WHERE domain_id = ?
                ORDER BY checked_at DESC LIMIT 50
            )
            """,
            (domain_id, domain_id),
        )


def get_latest_detail(domain_id: int) -> dict[str, Any] | None:
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT payload FROM check_results
            WHERE domain_id = ?
            ORDER BY checked_at DESC LIMIT 1
            """,
            (domain_id,),
        ).fetchone()
        if not row:
            return None
        return json.loads(row["payload"])


def get_history(domain_id: int, limit: int = 30) -> list[dict[str, Any]]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT checked_at, ok, days_left, grade, score, tls_version,
                   reachable, status_code, response_ms, error
            FROM check_results
            WHERE domain_id = ?
            ORDER BY checked_at DESC
            LIMIT ?
            """,
            (domain_id, limit),
        ).fetchall()
        return [dict(r) for r in rows]
