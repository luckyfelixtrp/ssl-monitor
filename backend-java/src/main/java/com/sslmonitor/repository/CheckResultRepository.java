package com.sslmonitor.repository;

import com.sslmonitor.entity.CheckResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface CheckResultRepository extends JpaRepository<CheckResult, Long> {

    Optional<CheckResult> findFirstByDomainIdOrderByCheckedAtDescIdDesc(Long domainId);

    List<CheckResult> findByDomainIdOrderByCheckedAtDescIdDesc(Long domainId, Pageable pageable);

    @Modifying
    @Transactional
    void deleteByDomainId(Long domainId);

    /** 仅保留某域名最近 keep 条，删除更早的记录 */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM check_results WHERE domain_id = :domainId AND id NOT IN " +
            "(SELECT id FROM (SELECT id FROM check_results WHERE domain_id = :domainId " +
            "ORDER BY checked_at DESC, id DESC LIMIT :keep) t)", nativeQuery = true)
    void trimHistory(@Param("domainId") Long domainId, @Param("keep") int keep);
}
