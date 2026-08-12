package com.enterprisehub.gateway.mail;

import com.enterprisehub.gateway.config.MailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MailServiceTest {

    private JavaMailSender mailSender;
    private MailService service;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        service = new MailService(mailSender, new MailProperties("noreply@example.com"));
    }

    @Test
    void sendTemporaryPassword_sendsToCorrectRecipient_includesThePassword() {
        service.sendTemporaryPassword("dev@acme.com", "Dev Person", "hunter2xyz");

        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("dev@acme.com");
        assertThat(sent.getFrom()).isEqualTo("noreply@example.com");
        assertThat(sent.getText()).contains("hunter2xyz").contains("Dev Person");
    }

    @Test
    void sendTemporaryPassword_deliveryFails_propagatesMailException() {
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> service.sendTemporaryPassword("dev@acme.com", "Dev Person", "hunter2xyz"))
                .isInstanceOf(MailSendException.class);
    }
}
