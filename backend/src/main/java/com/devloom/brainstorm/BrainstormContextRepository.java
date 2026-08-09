package com.devloom.brainstorm;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BrainstormContextRepository extends JpaRepository<BrainstormContextEntity, Long> {
    List<BrainstormContextEntity> findBySessionIdOrderByIdAsc(Long sessionId);
}
