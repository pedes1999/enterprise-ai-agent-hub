package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.config.CredentialsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves this fails a REAL Spring context boot, not just direct bean
 * construction (see LocalAesGcmCredentialEncryptorTest for the unit-level
 * equivalent) -- the actual regression this guards against is
 * CREDENTIAL_LOCAL_KEY being left unset in a real deployment and the
 * application starting up anyway with a compromised/known key.
 */
class LocalAesGcmCredentialEncryptorStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void placeholderLocalKey_failsApplicationStartup() {
        contextRunner
                .withPropertyValues("app.credentials.local-key=REPLACE_ME_WITH_A_STRONG_KEY_FROM_VAULT")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure()).hasMessageContaining("placeholder");
                });
    }

    @Test
    void realLocalKey_startsSuccessfully() {
        contextRunner
                .withPropertyValues("app.credentials.local-key=" + java.util.Base64.getEncoder().encodeToString(new byte[32]))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    @EnableConfigurationProperties(CredentialsProperties.class)
    static class TestConfig {
        @Bean
        LocalAesGcmCredentialEncryptor localAesGcmCredentialEncryptor(CredentialsProperties properties) {
            return new LocalAesGcmCredentialEncryptor(properties);
        }
    }
}
