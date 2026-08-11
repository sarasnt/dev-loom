package com.devloom.fleet;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {
    List<AgentRunEntity> findByStatus(String status);
    List<AgentRunEntity> findAllByOrderByCreatedAtDesc();
}
