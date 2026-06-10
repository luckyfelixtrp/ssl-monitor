const API_BASE = "/api";

/** HTML 转义，防止 XSS */
function esc(s) {
  if (s == null) return "";
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

async function api(path, opts = {}) {
  const url = path.startsWith("/api") ? API_BASE + path.slice(4) : path;
  const res = await fetch(url, { headers: { "Content-Type": "application/json" }, ...opts });
  if (!res.ok) {
    const detail = (await res.json().catch(() => ({}))).detail;
    throw new Error(detail || `HTTP ${res.status} ${res.statusText}`);
  }
  return res.status === 204 ? null : res.json();
}

function cleanHostInput(s) {
  if (!s) return "";
  s = s.trim();
  s = s.replace(/^[a-z]+:\/\//i, "");
  s = s.split("/")[0];
  if (s.includes("@")) s = s.split("@").pop();
  return s.toLowerCase();
}

function fmtDate(s) {
  if (!s) return "-";
  return new Date(s).toLocaleString("zh-CN", { hour12: false });
}

function gradeBadge(grade) {
  if (!grade) return '<span class="text-gray-400">-</span>';
  const cls = "grade-" + grade.replace("+", "plus");
  return `<span class="badge ${cls}">${grade}</span>`;
}

function daysBadge(days, ok) {
  if (days === null || days === undefined) return '<span class="text-gray-400">-</span>';
  let cls = "text-green-700";
  if (days < 0) cls = "text-red-700 font-bold";
  else if (days <= 7) cls = "text-red-600 font-semibold";
  else if (days <= 30) cls = "text-orange-600 font-semibold";
  const label = days < 0 ? `已过期 ${-days} 天` : `${days} 天`;
  return `<span class="${cls}">${label}</span>`;
}

function rowClass(row) {
  if (!row.ok) return "row-danger";
  if (row.days_left !== null && row.days_left < 0) return "row-danger";
  if (row.days_left !== null && row.days_left <= 30) return "row-warn";
  return "";
}

function section(title, rows) {
  return `
    <div class="mb-5">
      <h4 class="text-sm font-semibold text-gray-700 border-b pb-1 mb-2">${title}</h4>
      <table class="w-full text-sm">
        ${rows.map(([k, v]) => `<tr><td class="py-1 pr-4 text-gray-500 align-top w-40">${k}</td><td class="py-1 break-all">${v}</td></tr>`).join("")}
      </table>
    </div>
  `;
}

function renderDetail(d) {
  if (!d.ok) {
    return `<div class="bg-red-50 border border-red-200 rounded p-4 text-red-700">检测失败: ${esc(d.error) || "未知错误"}</div>`;
  }
  const c = d.cert || {};
  const t = d.tls || {};
  const s = d.security || {};
  const a = d.availability || {};

  const subj = Object.entries(c.subject || {}).map(([k, v]) => `${esc(k)}=${esc(v)}`).join(", ") || "-";
  const issu = Object.entries(c.issuer || {}).map(([k, v]) => `${esc(k)}=${esc(v)}`).join(", ") || "-";

  return `
    <div class="flex items-center gap-3 mb-4">
      ${gradeBadge(s.grade)}
      <span class="text-2xl font-bold">${s.score || 0}</span>
      <span class="text-sm text-gray-500">/100</span>
    </div>
    <div class="bg-blue-50 border border-blue-200 rounded p-3 mb-4">
      <div class="text-xs font-semibold text-blue-800 mb-1">安全评估</div>
      <ul class="text-sm text-blue-900 list-disc list-inside">
        ${(s.notes || []).map(n => `<li>${esc(n)}</li>`).join("")}
      </ul>
    </div>
    ${section("证书信息", [
      ["主体 (Subject)", subj],
      ["颁发者 (Issuer)", issu],
      ["有效期", `${fmtDate(c.not_before)} ~ ${fmtDate(c.not_after)}`],
      ["剩余天数", daysBadge(c.days_left)],
      ["序列号", esc(c.serial_number) || "-"],
      ["签名算法", esc(c.signature_algorithm) || "-"],
      ["公钥", `${esc(c.key_type) || "-"} ${esc(c.key_size) || ""} bit`],
      ["域名匹配", c.hostname_match ? '<span class="text-green-600">✓ 匹配</span>' : '<span class="text-red-600">✗ 不匹配</span>'],
      ["SHA-256 指纹", `<code class="text-xs">${esc(c.fingerprint_sha256) || "-"}</code>`],
      ["SAN", (c.san || []).map(x => esc(x)).join("<br>") || "-"],
    ])}
    ${section("TLS 协议", [
      ["协议版本", esc(t.version) || "-"],
      ["加密套件", esc(t.cipher) || "-"],
      ["密钥位数", t.cipher_bits ? `${esc(t.cipher_bits)} bit` : "-"],
    ])}
    ${section("可用性", [
      ["可达", a.reachable ? '<span class="text-green-600">✓ 是</span>' : '<span class="text-red-600">✗ 否</span>'],
      ["状态码", esc(a.status_code) || "-"],
      ["响应时间", a.response_ms ? `${esc(a.response_ms)} ms` : "-"],
      ["错误信息", esc(a.error) || "-"],
    ])}
    <div class="text-xs text-gray-400 text-right">检测时间: ${fmtDate(d.checked_at)}</div>
  `;
}

export { api, cleanHostInput, fmtDate, gradeBadge, daysBadge, rowClass, section, renderDetail, esc };
