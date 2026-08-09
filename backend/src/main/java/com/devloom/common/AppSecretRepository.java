package com.devloom.common;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSecretRepository extends JpaRepository<AppSecretEntity, String> {
}
