package com.devloom.integrations;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceInstanceRepository extends JpaRepository<SourceInstanceEntity, Long> {
    List<SourceInstanceEntity> findAllByOrderByTypeAscNameAsc();
    List<SourceInstanceEntity> findByEnabledTrue();
    List<SourceInstanceEntity> findByType(String type);
    Optional<SourceInstanceEntity> findByNameIgnoreCase(String name);
    boolean existsByType(String type);
    long count();
}
