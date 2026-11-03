package com.ecommerce.notification_service.repository;

import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.Notification;
import com.ecommerce.notification_service.entity.NotificationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Flyway migration + entity mapping + guarded UPDATEs against PostgreSQL. Skipped automatically without Docker. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class NotificationRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private NotificationRepository repository;
    @Autowired private EntityManager em;

    private Notification save(String key, ChannelType channel, NotificationStatus status) {
        return repository.saveAndFlush(Notification.create(key, channel, "ORDER_CREATED", "ORD-1", "c1", null, "s", "b", status));
    }

    @Test
    void uniqueDedupeKeyPerChannel() {
        save("ORDER_CREATED:ORD-1", ChannelType.LOG, NotificationStatus.PENDING);
        save("ORDER_CREATED:ORD-1", ChannelType.EMAIL, NotificationStatus.PENDING);   // other channel: allowed

        assertThat(repository.existsByDedupeKeyAndChannel("ORDER_CREATED:ORD-1", ChannelType.LOG)).isTrue();
        assertThatThrownBy(() -> save("ORDER_CREATED:ORD-1", ChannelType.LOG, NotificationStatus.PENDING))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void markSent_countsTheAttempt_andOnlyWorksOnce() {
        Long id = save("k1", ChannelType.LOG, NotificationStatus.PENDING).getId();
        LocalDateTime now = LocalDateTime.now();

        int first = repository.markSent(id, NotificationStatus.SENT, NotificationStatus.PENDING, NotificationStatus.FAILED, now);
        int second = repository.markSent(id, NotificationStatus.SENT, NotificationStatus.PENDING, NotificationStatus.FAILED, now);
        em.clear();

        Notification n = repository.findById(id).orElseThrow();
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getSentAt()).isNotNull();
    }

    @Test
    void markFailed_recordsError_andRetryCandidatesRespectMaxAttemptsAndCutoff() {
        Long id = save("k2", ChannelType.LOG, NotificationStatus.PENDING).getId();
        repository.markFailed(id, NotificationStatus.FAILED, NotificationStatus.PENDING, "smtp down", LocalDateTime.now().minusMinutes(10));
        em.clear();

        Notification n = repository.findById(id).orElseThrow();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getLastError()).isEqualTo("smtp down");

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(1);
        List<Long> candidates = repository.findRetryCandidateIds(NotificationStatus.FAILED, 3, cutoff, PageRequest.of(0, 10));
        assertThat(candidates).contains(id);
        assertThat(repository.findRetryCandidateIds(NotificationStatus.FAILED, 1, cutoff, PageRequest.of(0, 10))).doesNotContain(id);
    }

    @Test
    void markSent_ignoresSkippedRows() {
        Long id = save("k3", ChannelType.EMAIL, NotificationStatus.SKIPPED).getId();
        int changed = repository.markSent(id, NotificationStatus.SENT, NotificationStatus.PENDING, NotificationStatus.FAILED, LocalDateTime.now());
        assertThat(changed).isZero();
    }
}
