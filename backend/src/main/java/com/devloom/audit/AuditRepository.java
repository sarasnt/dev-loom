package com.devloom.audit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEventEntity, Long> {
    List<AuditEventEntity> findTop50ByOrderByCreatedAtDesc();
}
