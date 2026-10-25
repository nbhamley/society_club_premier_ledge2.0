package com.scpl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AppConfig {

    /**
     * static on purpose: SocietyService needs this bean, and a non-static @Bean would force Spring to
     * build this whole configuration class first. Cost 11 is a deliberate step up from the default 10.
     */
    @Bean
    static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(11);
    }
}