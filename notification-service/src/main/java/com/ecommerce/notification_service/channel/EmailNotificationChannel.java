package com.ecommerce.notification_service.channel;

import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Active only when notifications.email.enabled=true. Needs SPRING_MAIL_HOST (and usually port, username, password).
 * Amazon SES SMTP works, but SES starts in sandbox mode: both the sender and every recipient must be verified there.
 * The recipient is event.customerEmail when present, otherwise notifications.email.default-recipient.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notifications.email.enabled", havingValue = "true")
public class EmailNotificationChannel implements NotificationChannel {

    private final JavaMailSender mailSender;
    private final String from;
    private final String defaultRecipient;

    public EmailNotificationChannel(ObjectProvider<JavaMailSender> mailSender,
                                    @Value("${notifications.email.from:}") String from,
                                    @Value("${notifications.email.default-recipient:}") String defaultRecipient) {
        this.mailSender = mailSender.getIfAvailable();
        if (this.mailSender == null) {
            throw new IllegalStateException("notifications.email.enabled=true but no mail server is configured: set SPRING_MAIL_HOST (and port, username, password)");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("notifications.email.enabled=true but notifications.email.from (NOTIFICATIONS_EMAIL_FROM) is empty");
        }
        this.from = from;
        this.defaultRecipient = defaultRecipient == null ? "" : defaultRecipient.trim();
    }

    @Override
    public ChannelType type() {
        return ChannelType.EMAIL;
    }

    @Override
    public boolean requiresRecipient() {
        return true;
    }

    @Override
    public String resolveRecipient(OrderEventMessage event) {
        if (event.customerEmail() != null && !event.customerEmail().isBlank()) {
            return event.customerEmail().trim();
        }
        return defaultRecipient.isEmpty() ? null : defaultRecipient;
    }

    @Override
    public void send(Notification n) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(n.getRecipient());
        message.setSubject(n.getSubject());
        message.setText(n.getBody());
        mailSender.send(message);
        log.info("Email sent for order {} ({})", n.getOrderNumber(), n.getEventType());
    }
}
