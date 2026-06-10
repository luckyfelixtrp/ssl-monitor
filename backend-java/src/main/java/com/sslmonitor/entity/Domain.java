package com.sslmonitor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "domains", uniqueConstraints = @UniqueConstraint(columnNames = {"host", "port"}))
public class Domain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 253)
    private String host;

    @Column(nullable = false)
    private int port = 443;

    @Column(length = 200)
    private String remark = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
