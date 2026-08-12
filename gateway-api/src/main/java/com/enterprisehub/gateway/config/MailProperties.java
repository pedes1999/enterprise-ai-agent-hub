package com.enterprisehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** fromAddress must be a sender Brevo has verified for this account, or Brevo rejects the send outright. */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(String fromAddress) {
}
