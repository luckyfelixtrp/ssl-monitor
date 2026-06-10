package com.sslmonitor.controller;

import com.sslmonitor.dto.DomainRequest;
import com.sslmonitor.entity.Domain;
import com.sslmonitor.service.DomainService;
import com.sslmonitor.service.ScanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DomainController {

    private final DomainService domainService;
    private final ScanService scanService;

    public DomainController(DomainService domainService, ScanService scanService) {
        this.domainService = domainService;
        this.scanService = scanService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/domains")
    public List<Map<String, Object>> listDomains() {
        return domainService.listDomains();
    }

    @PostMapping("/domains")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addDomain(@Valid @RequestBody DomainRequest req) {
        String host;
        int port;
        try {
            Object[] hp = domainService.normalizeHostPort(req.getHost(), req.getPort());
            host = (String) hp[0];
            port = (int) hp[1];
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        Domain d;
        try {
            d = domainService.addDomain(host, port, req.getRemark());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "域名 " + host + ":" + port + " 已存在，请勿重复添加");
        }

        try {
            domainService.checkAndSave(d);
        } catch (Exception ignored) {
            // 首次检测失败不影响添加
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", d.getId());
        resp.put("host", host);
        resp.put("port", port);
        return resp;
    }

    @PutMapping("/domains/{id}")
    public Map<String, Object> updateDomain(@PathVariable Long id, @Valid @RequestBody DomainRequest req) {
        Domain d = domainService.getDomain(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "域名不存在"));
        String host;
        int port;
        try {
            Object[] hp = domainService.normalizeHostPort(req.getHost(), req.getPort());
            host = (String) hp[0];
            port = (int) hp[1];
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        domainService.updateDomain(d, host, port, req.getRemark());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("host", host);
        resp.put("port", port);
        return resp;
    }

    @DeleteMapping("/domains/{id}")
    public Map<String, Object> deleteDomain(@PathVariable Long id) {
        domainService.deleteDomain(id);
        return Map.of("ok", true);
    }

    @PostMapping("/domains/{id}/check")
    public Map<String, Object> checkOne(@PathVariable Long id) {
        Domain d = domainService.getDomain(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "域名不存在"));
        return domainService.checkAndSave(d);
    }

    @PostMapping("/check-all")
    public Map<String, Object> checkAll() {
        scanService.scanAllAsync();
        return Map.of("ok", true, "message", "扫描已在后台执行");
    }

    @GetMapping("/domains/{id}/detail")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> detail = domainService.getLatestDetail(id);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无检测记录");
        }
        return detail;
    }

    @GetMapping("/domains/{id}/history")
    public List<Map<String, Object>> history(@PathVariable Long id,
                                             @RequestParam(defaultValue = "30") int limit) {
        return domainService.getHistory(id, limit);
    }
}
