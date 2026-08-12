package com.devloom.handoff;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HandoffRepository extends JpaRepository<HandoffEntity, Long> {
    List<HandoffEntity> findAllByOrderByCreatedAtDesc(Limit limit);
}
