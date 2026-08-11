package com.devloom.briefing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefingSnapshotRepository extends JpaRepository<BriefingSnapshotEntity, Long> {
    Optional<BriefingSnapshotEntity> findFirstByKindOrderByTakenAtDesc(String kind);
    List<BriefingSnapshotEntity> findByKindOrderByTakenAtDesc(String kind);
}
