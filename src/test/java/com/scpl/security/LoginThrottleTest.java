package com.scpl.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    private final LoginThrottle throttle = new LoginThrottle();

    @Test
    void keyIgnoresCaseAndSurroundingSpaces() {
        assertThat(LoginThrottle.key("1.2.3.4", "  Admin ")).isEqualTo("1.2.3.4|admin");
    }

    @Test
    void lockoutStartsAtTheLimit_andIsPerIpAndUsername() {
        String key = LoginThrottle.key("1.2.3.4", "admin");
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.failure(key);
        }
        assertThat(throttle.isBlocked(key)).isFalse();
        throttle.failure(key);
        assertThat(throttle.isBlocked(key)).isTrue();
        assertThat(throttle.isBlocked(LoginThrottle.key("5.6.7.8", "admin"))).isFalse();   // someone else elsewhere is unaffected
        assertThat(throttle.isBlocked(LoginThrottle.key("1.2.3.4", "other"))).isFalse();
    }

    @Test
    void aSuccessfulLoginClearsTheCounter() {
        String key = LoginThrottle.key("1.2.3.4", "admin");
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.failure(key);
        }
        throttle.success(key);
        assertThat(throttle.isBlocked(key)).isFalse();
    }
}