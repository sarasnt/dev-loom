package com.devloom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DevLoom — calm engineering command center.
 *
 * <p>Modular monolith. Packages map to the modules in SPEC.md §18:
 * {@code workmodel} (unified domain), {@code priority} (deterministic engine),
 * {@code ai} (provider ports), {@code common} (redaction, shared), {@code api} (transport).
 */
@SpringBootApplication
public class DevLoomApplication {
    public static void main(String[] args) {
        SpringApplication.run(DevLoomApplication.class, args);
    }
}
