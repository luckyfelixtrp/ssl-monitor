package com.sslmonitor.repository;

import com.sslmonitor.entity.Domain;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainRepository extends JpaRepository<Domain, Long> {
    boolean existsByHostAndPort(String host, int port);
}
