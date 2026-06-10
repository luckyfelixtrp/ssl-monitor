package com.sslmonitor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "check_results", indexes = {
        @Index(name = "idx_check_domain", columnList = "domain_id, checked_at")
})
public class CheckResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "domain_id", nullable = false)
    private Long domainId;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt = Instant.now();

    @Column(nullable = false)
    private boolean ok;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "days_left")
    private Integer daysLeft;

    @Column(name = "not_after")
    private String notAfter;

    private String grade;

    private Integer score;

    @Column(name = "tls_version")
    private String tlsVersion;

    private String cipher;

    private Boolean reachable;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "response_ms")
    private Integer responseMs;

    @Column(name = "payload", columnDefinition = "LONGTEXT", nullable = false)
    private String payload;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDomainId() { return domainId; }
    public void setDomainId(Long domainId) { this.domainId = domainId; }

    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }

    public boolean isOk() { return ok; }
    public void setOk(boolean ok) { this.ok = ok; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Integer getDaysLeft() { return daysLeft; }
    public void setDaysLeft(Integer daysLeft) { this.daysLeft = daysLeft; }

    public String getNotAfter() { return notAfter; }
    public void setNotAfter(String notAfter) { this.notAfter = notAfter; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getTlsVersion() { return tlsVersion; }
    public void setTlsVersion(String tlsVersion) { this.tlsVersion = tlsVersion; }

    public String getCipher() { return cipher; }
    public void setCipher(String cipher) { this.cipher = cipher; }

    public Boolean getReachable() { return reachable; }
    public void setReachable(Boolean reachable) { this.reachable = reachable; }

    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }

    public Integer getResponseMs() { return responseMs; }
    public void setResponseMs(Integer responseMs) { this.responseMs = responseMs; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
