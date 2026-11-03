package com.ecommerce.notification_service.service.impl;

import com.ecommerce.notification_service.channel.NotificationChannel;
import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.dto.response.NotificationResponse;
import com.ecommerce.notification_service.dto.response.PageResponse;
import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.Notification;
import com.ecommerce.notification_service.entity.NotificationStatus;
import com.ecommerce.notification_service.exception.InvalidEventException;
import com.ecommerce.notification_service.exception.InvalidNotificationStateException;
import com.ecommerce.notification_service.exception.ResourceNotFoundException;
import com.ecommerce.notification_service.mapper.NotificationMapper;
import com.ecommerce.notification_service.repository.NotificationRepository;
import com.ecommerce.notification_service.service.MessageFactory;
import com.ecommerce.notification_service.service.NotificationService;
import com.ecommerce.notification_service.util.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns an order event into notifications.
 *
 * Rules (each answers a real failure mode):
 *  1. Idempotent: UNIQUE (dedupe_key, channel). Kafka can deliver an event twice; the second delivery inserts nothing.
 *  2. No database transaction while sending: an SMTP server that takes 5 seconds must not hold one of the 5 pooled connections.
 *     Insert (short transaction) -> send (no transaction) -> mark result (short transaction).
 *  3. One channel failing never blocks another and never loses the event: the row stays FAILED and the retry job tries again.
 *  4. Status changes are guarded UPDATEs (only PENDING / FAILED rows can be marked), so concurrent retries cannot double count.
 */
@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository repository;
    private final List<NotificationChannel> channels;
    private final MessageFactory messageFactory;
    private final NotificationMapper mapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public NotificationServiceImpl(NotificationRepository repository,
                                   List<NotificationChannel> channels,
                                   MessageFactory messageFactory,
                                   NotificationMapper mapper,
                                   ObjectMapper objectMapper,
                                   TransactionTemplate tx) {
        this.repository = repository;
        this.channels = channels;
        this.messageFactory = messageFactory;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ handle events

    @Override
    public List<NotificationResponse> handleEvent(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new InvalidEventException("Empty event payload");
        }
        OrderEventMessage event;
        try {
            event = objectMapper.readValue(payloadJson, OrderEventMessage.class);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Event payload is not valid JSON: " + e.getOriginalMessage());
        }
        return handleEvent(event);
    }

    @Override
    public List<NotificationResponse> handleEvent(OrderEventMessage event) {
        if (event == null || isBlank(event.eventType()) || isBlank(event.orderNumber())) {
            throw new InvalidEventException("Event needs eventType and orderNumber");
        }
        Optional<MessageFactory.Message> message = messageFactory.build(event);
        if (message.isEmpty()) {
            log.info("Ignoring event type {} for order {} (no template)", event.eventType(), event.orderNumber());
            return List.of();
        }

        String dedupeKey = event.eventType() + ":" + event.orderNumber();
        List<NotificationResponse> created = new ArrayList<>();
        for (NotificationChannel channel : channels) {
            Notification notification = createIfNew(dedupeKey, channel, event, message.get());
            if (notification == null) {
                log.info("Duplicate event {} ignored for channel {}", dedupeKey, channel.type());
                continue;
            }
            if (notification.getStatus() == NotificationStatus.PENDING) {
                send(channel, notification);
            }
            created.add(mapper.toResponse(reload(notification.getId())));
        }
        return created;
    }

    /** Short transaction: insert the row, or return null when this (event, channel) was already handled. */
    private Notification createIfNew(String dedupeKey, NotificationChannel channel, OrderEventMessage event, MessageFactory.Message message) {
        if (repository.existsByDedupeKeyAndChannel(dedupeKey, channel.type())) {
            return null;
        }
        String recipient = channel.resolveRecipient(event);
        boolean skip = channel.requiresRecipient() && isBlank(recipient);
        Notification n = Notification.create(dedupeKey, channel.type(), event.eventType(), event.orderNumber(),
                event.customerId(), recipient, message.subject(), truncate(message.body(), Constants.MAX_BODY_LENGTH),
                skip ? NotificationStatus.SKIPPED : NotificationStatus.PENDING);
        if (skip) {
            n.setLastError("No recipient for channel " + channel.type());
        }
        try {
            return tx.execute(status -> repository.save(n));
        } catch (DataIntegrityViolationException e) {
            return null;   // another consumer instance inserted it between the check and the insert
        }
    }

    // ------------------------------------------------------------------ send

    private void send(NotificationChannel channel, Notification notification) {
        try {
            channel.send(notification);
        } catch (Exception e) {
            log.warn("Sending notification {} via {} failed: {}", notification.getId(), channel.type(), e.toString());
            mark(() -> repository.markFailed(notification.getId(), NotificationStatus.FAILED, NotificationStatus.PENDING,
                    truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), Constants.MAX_ERROR_LENGTH), LocalDateTime.now()));
            return;
        }
        mark(() -> repository.markSent(notification.getId(), NotificationStatus.SENT, NotificationStatus.PENDING,
                NotificationStatus.FAILED, LocalDateTime.now()));
    }

    private void mark(java.util.function.IntSupplier update) {
        tx.execute(status -> update.getAsInt());
    }

    private NotificationChannel channelFor(ChannelType type) {
        return channels.stream().filter(c -> c.type() == type).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ read / retry

    @Override
    public NotificationResponse get(Long id) {
        return mapper.toResponse(reload(id));
    }

    @Override
    public PageResponse<NotificationResponse> list(String orderNumber, String customerId, NotificationStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), Constants.MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Specification<Notification> spec = (root, query, cb) -> cb.conjunction();
        if (!isBlank(orderNumber)) {
            String value = orderNumber.trim();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("orderNumber"), value));
        }
        if (!isBlank(customerId)) {
            String value = customerId.trim();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), value));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Page<Notification> result = repository.findAll(spec, pageable);
        return PageResponse.from(result, mapper::toResponse);
    }

    @Override
    public NotificationResponse retry(Long id) {
        Notification n = reload(id);
        if (n.getStatus() != NotificationStatus.FAILED) {
            throw new InvalidNotificationStateException("Notification " + id + " is " + n.getStatus() + "; only FAILED notifications can be retried");
        }
        resend(n);
        return mapper.toResponse(reload(id));
    }

    @Override
    public void retryIfFailed(Long id) {
        Notification n = repository.findById(id).orElse(null);
        if (n != null && n.getStatus() == NotificationStatus.FAILED) {
            resend(n);
        }
    }

    private void resend(Notification n) {
        NotificationChannel channel = channelFor(n.getChannel());
        if (channel == null) {
            log.warn("Notification {} uses channel {} which is not active; leaving it FAILED", n.getId(), n.getChannel());
            return;
        }
        send(channel, n);
    }

    private Notification reload(Long id) {
        return repository.findById(Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Notification " + id + " not found"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
