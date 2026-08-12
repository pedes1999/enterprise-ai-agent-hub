package com.enterprisehub.gateway.mail;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;

import static org.mockito.Mockito.mock;

/**
 * Overrides Spring Boot's auto-configured JavaMailSender (which would
 * otherwise try to connect to real Brevo SMTP using the placeholder
 * credentials in application.yml) with an inert mock for every
 * @SpringBootTest that activates the "test" profile. Suppresses
 * MailSenderAutoConfiguration's own bean automatically -- it's
 * @ConditionalOnMissingBean(JavaMailSender.class).
 */
@Configuration
@Profile("test")
public class TestMailConfig {

    @Bean
    public JavaMailSender javaMailSender() {
        return mock(JavaMailSender.class);
    }
}
