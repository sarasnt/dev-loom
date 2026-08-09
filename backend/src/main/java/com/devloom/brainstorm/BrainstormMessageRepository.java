package com.devloom.brainstorm;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BrainstormMessageRepository extends JpaRepository<BrainstormMessageEntity, Long> {
    List<BrainstormMessageEntity> findBySessionIdOrderBySeqAsc(Long sessionId);
    int countBySessionId(Long sessionId);

    @Modifying
    @Query("delete from BrainstormMessageEntity m where m.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") Long sessionId);
}
