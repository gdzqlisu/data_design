package com.gdzqlisu.datadesign.auth.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    List<AuditLog> findAllByEventOrderByCreatedAtDesc(AuditEvent event);
}
