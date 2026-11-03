package com.ecommerce.notification_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One notification for one (event, channel). Status changes after creation go through the UPDATE queries in NotificationRepository. */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedupe_key", nullable = false, length = 150)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelType channel;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "order_number", nullable = false, length = 64)
    private String orderNumber;

    @Column(name = "customer_id", length = 100)
    private String customerId;

    @Column(length = 255)
    private String recipient;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(nullable = false, length = 2000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** Factory method: the only place that knows how a notification starts out. */
    public static Notification create(String dedupeKey, ChannelType channel, String eventType, String orderNumber,
                                      String customerId, String recipient, String subject, String body,
                                      NotificationStatus status) {
        Notification n = new Notification();
        n.dedupeKey = dedupeKey;
        n.channel = channel;
        n.eventType = eventType;
        n.orderNumber = orderNumber;
        n.customerId = customerId;
        n.recipient = recipient;
        n.subject = subject;
        n.body = body;
        n.status = status;
        n.attempts = 0;
        return n;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
