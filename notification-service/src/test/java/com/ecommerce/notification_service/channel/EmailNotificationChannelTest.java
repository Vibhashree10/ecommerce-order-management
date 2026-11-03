package com.ecommerce.notification_service.channel;

import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.Notification;
import com.ecommerce.notification_service.entity.NotificationStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailNotificationChannelTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    private OrderEventMessage event(String email) {
        return new OrderEventMessage("ORDER_CREATED", "ORD-1", "c1", null, null, null, null, null, email);
    }

    @Test
    void recipient_prefersEventEmail_thenDefault_thenNull() {
        EmailNotificationChannel withDefault = new EmailNotificationChannel(provider(mock(JavaMailSender.class)), "from@x.com", "default@x.com");
        assertThat(withDefault.resolveRecipient(event("a@x.com"))).isEqualTo("a@x.com");
        assertThat(withDefault.resolveRecipient(event(null))).isEqualTo("default@x.com");

        EmailNotificationChannel noDefault = new EmailNotificationChannel(provider(mock(JavaMailSender.class)), "from@x.com", "");
        assertThat(noDefault.resolveRecipient(event(" "))).isNull();
        assertThat(noDefault.requiresRecipient()).isTrue();
        assertThat(noDefault.type()).isEqualTo(ChannelType.EMAIL);
    }

    @Test
    void send_buildsAndSendsTheMail() {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailNotificationChannel channel = new EmailNotificationChannel(provider(sender), "from@x.com", "");
        Notification n = Notification.create("k", ChannelType.EMAIL, "ORDER_CREATED", "ORD-1", "c1", "to@x.com", "Subject", "Body", NotificationStatus.PENDING);

        channel.send(n);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("from@x.com");
        assertThat(captor.getValue().getTo()).containsExactly("to@x.com");
        assertThat(captor.getValue().getSubject()).isEqualTo("Subject");
        assertThat(captor.getValue().getText()).isEqualTo("Body");
    }

    @Test
    void startupFails_withClearMessage_whenMailServerOrSenderMissing() {
        assertThatThrownBy(() -> new EmailNotificationChannel(provider(null), "from@x.com", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SPRING_MAIL_HOST");
        assertThatThrownBy(() -> new EmailNotificationChannel(provider(mock(JavaMailSender.class)), "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NOTIFICATIONS_EMAIL_FROM");
        verify(mock(JavaMailSender.class), org.mockito.Mockito.never()).send(any(SimpleMailMessage.class));
    }
}
