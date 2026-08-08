package com.devloom.workmodel;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkItemRepository extends JpaRepository<WorkItemEntity, Long> {
    List<WorkItemEntity> findAllByOrderBySortOrderAsc();
}
