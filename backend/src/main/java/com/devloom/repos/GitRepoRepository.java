package com.devloom.repos;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GitRepoRepository extends JpaRepository<GitRepoEntity, Long> {
    Optional<GitRepoEntity> findByPath(String path);
    // Windows paths are case-insensitive (and a scan may report a different drive-letter case
    // than the one first stored), so dedupe case-insensitively to avoid duplicate rows.
    Optional<GitRepoEntity> findByPathIgnoreCase(String path);
    boolean existsByPath(String path);
}
