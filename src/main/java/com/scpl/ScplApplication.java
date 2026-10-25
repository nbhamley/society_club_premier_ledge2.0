package com.scpl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the SCPL multi-tenant platform.
 *
 * <p>Scheduling is enabled here (not in a config class) because the import poller,
 * dunning job and backup jobs all depend on it and must never be silently disabled
 * by a profile-specific configuration.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ScplApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScplApplication.class, args);
    }
}