package com.ecommerce.notification_service.repository;

import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.Notification;
import com.ecommerce.notification_service.entity.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long>, JpaSpecificationExecutor<Notification> {

    boolean existsByDedupeKeyAndChannel(String dedupeKey, ChannelType channel);

    /** Failed notifications that may be retried: fewer than maxAttempts so far and not touched since the cutoff. */
    @Query("SELECT n.id FROM Notification n WHERE n.status = :status AND n.attempts < :maxAttempts "
            + "AND n.updatedAt < :cutoff ORDER BY n.updatedAt ASC")
    List<Long> findRetryCandidateIds(@Param("status") NotificationStatus status,
                                     @Param("maxAttempts") int maxAttempts,
                                     @Param("cutoff") LocalDateTime cutoff,
                                     Pageable pageable);

    /** Compare-and-set to SENT: only a notification that is PENDING or FAILED can be marked, and every attempt is counted. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.status = :sent, n.attempts = n.attempts + 1, n.lastError = NULL, "
            + "n.sentAt = :now, n.updatedAt = :now WHERE n.id = :id AND n.status IN (:pending, :failed)")
    int markSent(@Param("id") Long id,
                 @Param("sent") NotificationStatus sent,
                 @Param("pending") NotificationStatus pending,
                 @Param("failed") NotificationStatus failed,
                 @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.status = :failedStatus, n.attempts = n.attempts + 1, n.lastError = :error, "
            + "n.updatedAt = :now WHERE n.id = :id AND n.status IN (:pending, :failedStatus)")
    int markFailed(@Param("id") Long id,
                   @Param("failedStatus") NotificationStatus failedStatus,
                   @Param("pending") NotificationStatus pending,
                   @Param("error") String error,
                   @Param("now") LocalDateTime now);
}
