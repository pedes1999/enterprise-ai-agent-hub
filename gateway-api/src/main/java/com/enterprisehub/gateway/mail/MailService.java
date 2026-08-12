package com.enterprisehub.gateway.mail;

import com.enterprisehub.gateway.config.MailProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Delivers the one real transactional email this platform sends today: a
 * newly created team member's temporary password (see
 * UserService.create()). Backed by Brevo's SMTP relay -- see
 * application.yml's spring.mail.* block for host/port/credentials.
 *
 * Deliberately plain text (SimpleMailMessage), no template engine: one
 * email, one purpose, not worth the added dependency yet.
 */
@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    public MailService(JavaMailSender mailSender, MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    /** Throws MailException (unchecked) on delivery failure -- UserService treats that as fatal to account creation, see its javadoc for why. */
    public void sendTemporaryPassword(String toEmail, String recipientName, String temporaryPassword) throws MailException {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailProperties.fromAddress());
        message.setTo(toEmail);
        message.setSubject("Your Enterprise AI Agent Hub account");
        message.setText("Hi " + recipientName + ",\n\n"
                + "An account has been created for you on Enterprise AI Agent Hub.\n\n"
                + "Temporary password: " + temporaryPassword + "\n\n"
                + "There is no self-service password change yet -- contact your administrator "
                + "if you need this reset.");
        mailSender.send(message);
    }
}
