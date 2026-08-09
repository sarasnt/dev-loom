package com.devloom.brainstorm;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BrainstormSessionRepository extends JpaRepository<BrainstormSessionEntity, Long> {
    List<BrainstormSessionEntity> findAllByOrderByUpdatedAtDesc();
    List<BrainstormSessionEntity> findByRepoPathIsNotNullOrderByUpdatedAtDesc();
}
