package com.devloom.brainstorm;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BrainstormMessageRepository extends JpaRepository<BrainstormMessageEntity, Long> {
    List<BrainstormMessageEntity> findBySessionIdOrderBySeqAsc(Long sessionId);
    int countBySessionId(Long sessionId);
}
