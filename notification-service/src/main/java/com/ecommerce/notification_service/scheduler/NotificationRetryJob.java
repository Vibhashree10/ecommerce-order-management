package com.ecommerce.notification_service.scheduler;

import com.ecommerce.notification_service.entity.NotificationStatus;
import com.ecommerce.notification_service.repository.NotificationRepository;
import com.ecommerce.notification_service.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** Re-sends FAILED notifications (for example while the SMTP server was down) until notifications.max-attempts is reached. */
@Slf4j
@Component
@ConditionalOnProperty(name = "notifications.retry-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRetryJob {

    private static final int BATCH_SIZE = 50;

    private final NotificationRepository repository;
    private final NotificationService service;
    private final long retryAfterSeconds;
    private final int maxAttempts;

    public NotificationRetryJob(NotificationRepository repository,
                                NotificationService service,
                                @Value("${notifications.retry-after-seconds:60}") long retryAfterSeconds,
                                @Value("${notifications.max-attempts:3}") int maxAttempts) {
        this.repository = repository;
        this.service = service;
        this.retryAfterSeconds = retryAfterSeconds;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${notifications.retry-scan-ms:60000}", initialDelayString = "${notifications.retry-scan-ms:60000}")
    public void run() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(retryAfterSeconds);
        List<Long> ids = repository.findRetryCandidateIds(NotificationStatus.FAILED, maxAttempts, cutoff, PageRequest.of(0, BATCH_SIZE));
        for (Long id : ids) {
            try {
                service.retryIfFailed(id);
            } catch (RuntimeException e) {
                log.warn("Retry of notification {} failed: {}", id, e.toString());
            }
        }
    }
}
