package com.scpl.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feature switches in one place. Named "featureFlags" so templates can use ${@featureFlags.crossSocietyEnabled}.
 * A plain component rather than @ConfigurationProperties because that would register under a generated
 * bean name templates cannot reference.
 */
@Component("featureFlags")
public class FeatureFlags {

    private final boolean aiEnabled;
    private final boolean crossSocietyEnabled;
    private final boolean devLogCredentials;

    public FeatureFlags(@Value("${feature.ai.enabled:false}") boolean aiEnabled,
                        @Value("${feature.cross-society.enabled:false}") boolean crossSocietyEnabled,
                        @Value("${feature.dev-log-credentials:false}") boolean devLogCredentials) {
        this.aiEnabled = aiEnabled;
        this.crossSocietyEnabled = crossSocietyEnabled;
        this.devLogCredentials = devLogCredentials;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public boolean isCrossSocietyEnabled() {
        return crossSocietyEnabled;
    }

    public boolean isDevLogCredentials() {
        return devLogCredentials;
    }
}