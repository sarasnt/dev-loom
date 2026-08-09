package com.devloom.workmodel;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkItemRepository extends JpaRepository<WorkItemEntity, Long> {
    List<WorkItemEntity> findAllByOrderBySortOrderAsc();

    List<WorkItemEntity> findByTypeOrderBySortOrderAsc(String type);

    java.util.Optional<WorkItemEntity> findFirstByExtId(String extId);

    /**
     * Bulk delete, executed immediately against the DB. Must be a bulk @Query (not a
     * derived delete): within replace-on-sync we insert rows with the same natural keys
     * right after, and Hibernate flushes INSERTs before entity DELETEs — a plain derived
     * delete would trip the unique constraint. A bulk delete runs before the inserts.
     */
    @Modifying
    @Query("delete from WorkItemEntity w where w.source = :source")
    int deleteBySource(@Param("source") String source);

    long countBySource(String source);
}
