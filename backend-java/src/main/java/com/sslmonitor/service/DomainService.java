package com.sslmonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sslmonitor.entity.CheckResult;
import com.sslmonitor.entity.Domain;
import com.sslmonitor.repository.CheckResultRepository;
import com.sslmonitor.repository.DomainRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class DomainService {

    private static final int HISTORY_KEEP = 50;
    private static final Pattern HOSTNAME_RE =
            Pattern.compile("^[a-z0-9]([a-z0-9\\-.]{0,251}[a-z0-9])?$");

    private final DomainRepository domainRepo;
    private final CheckResultRepository resultRepo;
    private final CertCheckService certCheckService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DomainService(DomainRepository domainRepo,
                         CheckResultRepository resultRepo,
                         CertCheckService certCheckService) {
        this.domainRepo = domainRepo;
        this.resultRepo = resultRepo;
        this.certCheckService = certCheckService;
    }

    /** 清洗用户输入，返回 [host, port]。非法抛 IllegalArgumentException */
    public Object[] normalizeHostPort(String rawHost, Integer rawPort) {
        int port = rawPort == null ? 443 : rawPort;
        String s = rawHost == null ? "" : rawHost.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("域名不能为空");

        String host;
        if (s.contains("://") || s.startsWith("//")) {
            if (s.startsWith("//")) s = "http:" + s;
            URI uri = URI.create(s);
            host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (uri.getPort() > 0) port = uri.getPort();
        } else {
            s = s.split("/", 2)[0]; // 去路径
            if (s.contains(":")) {
                String[] parts = s.split(":", 2);
                host = parts[0].toLowerCase();
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("端口格式错误: " + parts[1]);
                }
            } else {
                host = s.toLowerCase();
            }
        }

        if (host.isEmpty()) throw new IllegalArgumentException("解析不出有效的域名");
        if (!HOSTNAME_RE.matcher(host).matches()) {
            throw new IllegalArgumentException("域名格式不合法: " + host + "（请只填主机名，例如 example.com）");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口超出范围: " + port);
        }
        return new Object[]{host, port};
    }

    public List<Map<String, Object>> listDomains() {
        List<Domain> domains = domainRepo.findAll();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Domain d : domains) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("host", d.getHost());
            row.put("port", d.getPort());
            row.put("remark", d.getRemark());
            row.put("created_at", d.getCreatedAt().toString());

            CheckResult r = resultRepo.findFirstByDomainIdOrderByCheckedAtDescIdDesc(d.getId()).orElse(null);
            if (r != null) {
                row.put("checked_at", r.getCheckedAt().toString());
                row.put("ok", r.isOk() ? 1 : 0);
                row.put("error", r.getError());
                row.put("days_left", r.getDaysLeft());
                row.put("not_after", r.getNotAfter());
                row.put("grade", r.getGrade());
                row.put("score", r.getScore());
                row.put("tls_version", r.getTlsVersion());
                row.put("cipher", r.getCipher());
                row.put("reachable", r.getReachable() != null && r.getReachable() ? 1 : 0);
                row.put("status_code", r.getStatusCode());
                row.put("response_ms", r.getResponseMs());
            } else {
                for (String k : new String[]{"checked_at", "error", "days_left", "not_after",
                        "grade", "score", "tls_version", "cipher", "status_code", "response_ms"}) {
                    row.put(k, null);
                }
                row.put("ok", null);
                row.put("reachable", null);
            }
            out.add(row);
        }
        // 排序：无数据置后，按剩余天数升序，再按 host
        out.sort((a, b) -> {
            Integer da = (Integer) a.get("days_left");
            Integer db = (Integer) b.get("days_left");
            if (da == null && db == null) return ((String) a.get("host")).compareTo((String) b.get("host"));
            if (da == null) return 1;
            if (db == null) return -1;
            if (!da.equals(db)) return Integer.compare(da, db);
            return ((String) a.get("host")).compareTo((String) b.get("host"));
        });
        return out;
    }

    public Domain addDomain(String host, int port, String remark) {
        if (domainRepo.existsByHostAndPort(host, port)) {
            throw new IllegalStateException("已存在相同的 host:port");
        }
        Domain d = new Domain();
        d.setHost(host);
        d.setPort(port);
        d.setRemark(remark == null ? "" : remark);
        d.setCreatedAt(Instant.now());
        return domainRepo.save(d);
    }

    public Optional<Domain> getDomain(Long id) {
        return domainRepo.findById(id);
    }

    public void updateDomain(Domain d, String host, int port, String remark) {
        d.setHost(host);
        d.setPort(port);
        d.setRemark(remark == null ? "" : remark);
        domainRepo.save(d);
    }

    public void deleteDomain(Long id) {
        resultRepo.deleteByDomainId(id);
        domainRepo.deleteById(id);
    }

    /** 执行一次检测并保存，返回完整 payload */
    public Map<String, Object> checkAndSave(Domain d) {
        Map<String, Object> payload = certCheckService.check(d.getHost(), d.getPort());
        saveResult(d.getId(), payload);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private void saveResult(Long domainId, Map<String, Object> payload) {
        CheckResult r = new CheckResult();
        r.setDomainId(domainId);
        r.setCheckedAt(Instant.now());
        r.setOk(Boolean.TRUE.equals(payload.get("ok")));
        r.setError((String) payload.get("error"));

        Map<String, Object> cert = (Map<String, Object>) payload.get("cert");
        Map<String, Object> tls = (Map<String, Object>) payload.get("tls");
        Map<String, Object> sec = (Map<String, Object>) payload.get("security");
        Map<String, Object> avail = (Map<String, Object>) payload.get("availability");

        if (cert != null) {
            r.setDaysLeft((Integer) cert.get("days_left"));
            r.setNotAfter((String) cert.get("not_after"));
        }
        if (sec != null) {
            r.setGrade((String) sec.get("grade"));
            r.setScore((Integer) sec.get("score"));
        }
        if (tls != null) {
            r.setTlsVersion((String) tls.get("version"));
            r.setCipher((String) tls.get("cipher"));
        }
        if (avail != null) {
            r.setReachable(Boolean.TRUE.equals(avail.get("reachable")));
            r.setStatusCode((Integer) avail.get("status_code"));
            r.setResponseMs((Integer) avail.get("response_ms"));
        }
        try {
            r.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            r.setPayload("{}");
        }
        resultRepo.save(r);
        resultRepo.trimHistory(domainId, HISTORY_KEEP);
    }

    public Map<String, Object> getLatestDetail(Long domainId) {
        CheckResult r = resultRepo.findFirstByDomainIdOrderByCheckedAtDescIdDesc(domainId).orElse(null);
        if (r == null) return null;
        try {
            return objectMapper.readValue(r.getPayload(), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    public List<Map<String, Object>> getHistory(Long domainId, int limit) {
        List<CheckResult> rows = resultRepo.findByDomainIdOrderByCheckedAtDescIdDesc(
                domainId, PageRequest.of(0, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CheckResult r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("checked_at", r.getCheckedAt().toString());
            m.put("ok", r.isOk() ? 1 : 0);
            m.put("days_left", r.getDaysLeft());
            m.put("grade", r.getGrade());
            m.put("score", r.getScore());
            m.put("tls_version", r.getTlsVersion());
            m.put("reachable", r.getReachable() != null && r.getReachable() ? 1 : 0);
            m.put("status_code", r.getStatusCode());
            m.put("response_ms", r.getResponseMs());
            m.put("error", r.getError());
            out.add(m);
        }
        return out;
    }

    public List<Domain> allDomains() {
        return domainRepo.findAll();
    }
}
