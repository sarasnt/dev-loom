package com.devloom.repos;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GitRepoRepository extends JpaRepository<GitRepoEntity, Long> {
    Optional<GitRepoEntity> findByPath(String path);
    boolean existsByPath(String path);
}
