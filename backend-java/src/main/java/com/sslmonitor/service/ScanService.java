package com.sslmonitor.service;

import com.sslmonitor.entity.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final DomainService domainService;

    public ScanService(DomainService domainService) {
        this.domainService = domainService;
    }

    /** 每天 03:00 自动扫描全部域名（服务器本地时区） */
    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledScan() {
        log.info("scheduled scan started");
        scanAll();
        log.info("scheduled scan finished");
    }

    /** 手动触发：异步执行，避免阻塞请求线程 */
    @Async
    public void scanAllAsync() {
        scanAll();
    }

    public void scanAll() {
        List<Domain> domains = domainService.allDomains();
        log.info("scanning {} domains", domains.size());
        // 限制并发为 5
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(5, Math.max(1, domains.size())));
        try {
            for (Domain d : domains) {
                pool.submit(() -> {
                    try {
                        domainService.checkAndSave(d);
                    } catch (Exception e) {
                        log.warn("check failed for {}: {}", d.getHost(), e.getMessage());
                    }
                });
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
