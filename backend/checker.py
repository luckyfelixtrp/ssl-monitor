"""SSL/TLS 证书检测核心逻辑"""
import asyncio
import socket
import ssl
import time
from datetime import datetime, timezone
from typing import Any

import httpx
from cryptography import x509
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import hashes
from cryptography.x509.oid import NameOID, ExtensionOID

# 已知的弱加密算法关键字
WEAK_CIPHERS = ("RC4", "3DES", "DES", "MD5", "NULL", "EXPORT", "anon")
# TLS 版本评分
TLS_SCORES = {
    "TLSv1.3": 100,
    "TLSv1.2": 90,
    "TLSv1.1": 50,
    "TLSv1": 30,
    "SSLv3": 10,
    "SSLv2": 0,
}


def _parse_name(name: x509.Name) -> dict[str, str]:
    """解析 X509 Name 为字典"""
    result: dict[str, str] = {}
    mapping = {
        NameOID.COMMON_NAME: "CN",
        NameOID.ORGANIZATION_NAME: "O",
        NameOID.ORGANIZATIONAL_UNIT_NAME: "OU",
        NameOID.COUNTRY_NAME: "C",
        NameOID.STATE_OR_PROVINCE_NAME: "ST",
        NameOID.LOCALITY_NAME: "L",
    }
    for attr in name:
        key = mapping.get(attr.oid, attr.oid.dotted_string)
        result[key] = attr.value
    return result


def _get_san(cert: x509.Certificate) -> list[str]:
    """提取 Subject Alternative Names"""
    try:
        ext = cert.extensions.get_extension_for_oid(ExtensionOID.SUBJECT_ALTERNATIVE_NAME)
        return [str(name.value) for name in ext.value]
    except x509.ExtensionNotFound:
        return []


def _evaluate_security(tls_version: str, cipher: str | None) -> tuple[int, list[str]]:
    """根据 TLS 版本与 cipher 给出 0-100 安全评分及说明"""
    score = TLS_SCORES.get(tls_version, 50)
    notes: list[str] = []

    if tls_version in ("SSLv2", "SSLv3", "TLSv1", "TLSv1.1"):
        notes.append(f"使用了不安全的协议版本 {tls_version}")

    if cipher:
        upper = cipher.upper()
        for weak in WEAK_CIPHERS:
            if weak in upper:
                score -= 30
                notes.append(f"使用了弱加密算法 {weak}")
                break
        if "GCM" in upper or "CHACHA20" in upper:
            pass  # AEAD，安全
        elif "CBC" in upper:
            score -= 5
            notes.append("使用 CBC 模式（建议升级到 GCM/ChaCha20）")

    score = max(0, min(100, score))
    if not notes:
        notes.append("协议与加密套件配置良好")
    return score, notes


async def check_certificate(host: str, port: int = 443, timeout: float = 8.0) -> dict[str, Any]:
    """
    异步检测单个域名的 SSL/TLS 证书与可用性。
    返回结构化字典，包含证书全字段、安全评分、可用性。
    """
    result: dict[str, Any] = {
        "host": host,
        "port": port,
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "ok": False,
        "error": None,
        "cert": None,
        "tls": None,
        "security": None,
        "availability": None,
    }

    loop = asyncio.get_running_loop()

    # 1) 拉取证书 + 协议信息（在线程池中执行同步 socket 代码）
    def _fetch_cert():
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # 即使过期也要拿到信息
        with socket.create_connection((host, port), timeout=timeout) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as ssock:
                der = ssock.getpeercert(binary_form=True)
                tls_version = ssock.version() or "unknown"
                cipher_info = ssock.cipher()  # (name, version, bits)
                return der, tls_version, cipher_info

    try:
        der, tls_version, cipher_info = await asyncio.wait_for(
            loop.run_in_executor(None, _fetch_cert), timeout=timeout + 2
        )
    except (socket.gaierror, ssl.SSLError, OSError, asyncio.TimeoutError) as e:
        result["error"] = f"连接或握手失败: {type(e).__name__}: {e}"
        return result

    cert = x509.load_der_x509_certificate(der, default_backend())

    not_before = cert.not_valid_before_utc
    not_after = cert.not_valid_after_utc
    now = datetime.now(timezone.utc)
    days_left = (not_after - now).days

    # 指纹
    sha256_fp = cert.fingerprint(hashes.SHA256()).hex().upper()
    sha1_fp = cert.fingerprint(hashes.SHA1()).hex().upper()

    # 签名算法
    try:
        sig_algo = cert.signature_hash_algorithm.name if cert.signature_hash_algorithm else "unknown"
    except Exception:
        sig_algo = "unknown"

    # 公钥信息
    pub_key = cert.public_key()
    key_size = getattr(pub_key, "key_size", None)
    key_type = type(pub_key).__name__.replace("PublicKey", "")

    cert_info = {
        "subject": _parse_name(cert.subject),
        "issuer": _parse_name(cert.issuer),
        "serial_number": format(cert.serial_number, "X"),
        "version": cert.version.name,
        "not_before": not_before.isoformat(),
        "not_after": not_after.isoformat(),
        "days_left": days_left,
        "expired": days_left < 0,
        "san": _get_san(cert),
        "signature_algorithm": sig_algo,
        "key_type": key_type,
        "key_size": key_size,
        "fingerprint_sha256": ":".join(sha256_fp[i:i + 2] for i in range(0, len(sha256_fp), 2)),
        "fingerprint_sha1": ":".join(sha1_fp[i:i + 2] for i in range(0, len(sha1_fp), 2)),
    }

    # 主机名匹配检查
    cert_info["hostname_match"] = _hostname_matches(host, cert_info["subject"].get("CN", ""), cert_info["san"])

    # TLS 信息
    tls_info = {
        "version": tls_version,
        "cipher": cipher_info[0] if cipher_info else None,
        "cipher_version": cipher_info[1] if cipher_info else None,
        "cipher_bits": cipher_info[2] if cipher_info else None,
    }

    # 安全评分
    score, notes = _evaluate_security(tls_version, tls_info["cipher"])
    if cert_info["expired"]:
        score = max(0, score - 50)
        notes.insert(0, "证书已过期")
    elif days_left < 14:
        score = max(0, score - 20)
        notes.insert(0, f"证书即将过期（剩 {days_left} 天）")
    if not cert_info["hostname_match"]:
        score = max(0, score - 30)
        notes.insert(0, "证书不匹配请求的主机名")
    if sig_algo in ("md5", "sha1"):
        score = max(0, score - 25)
        notes.append(f"签名算法过弱: {sig_algo}")

    grade = "A+" if score >= 95 else "A" if score >= 85 else "B" if score >= 70 else "C" if score >= 55 else "D" if score >= 40 else "F"

    result.update({
        "ok": True,
        "cert": cert_info,
        "tls": tls_info,
        "security": {"score": score, "grade": grade, "notes": notes},
    })

    # 2) 可用性检测 (HTTPS HEAD/GET)
    result["availability"] = await _check_availability(host, port)

    return result


def _hostname_matches(host: str, cn: str, san_list: list[str]) -> bool:
    """简易通配符匹配"""
    candidates = [cn] + list(san_list) if cn else list(san_list)
    host_l = host.lower()
    for c in candidates:
        c_l = c.lower().strip()
        if c_l == host_l:
            return True
        if c_l.startswith("*."):
            suffix = c_l[1:]  # ".example.com"
            if host_l.endswith(suffix) and host_l.count(".") >= c_l.count("."):
                return True
    return False


async def _check_availability(host: str, port: int) -> dict[str, Any]:
    """检测 HTTPS 可用性"""
    url = f"https://{host}" if port == 443 else f"https://{host}:{port}"
    start = time.perf_counter()
    try:
        async with httpx.AsyncClient(verify=False, timeout=8.0, follow_redirects=False) as client:
            resp = await client.get(url, headers={"User-Agent": "ssl-monitor/1.0"})
            elapsed_ms = int((time.perf_counter() - start) * 1000)
            return {
                "reachable": True,
                "status_code": resp.status_code,
                "response_ms": elapsed_ms,
                "error": None,
            }
    except Exception as e:
        return {
            "reachable": False,
            "status_code": None,
            "response_ms": int((time.perf_counter() - start) * 1000),
            "error": f"{type(e).__name__}: {e}",
        }
