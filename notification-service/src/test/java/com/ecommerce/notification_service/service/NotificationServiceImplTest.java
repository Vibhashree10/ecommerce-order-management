package com.ecommerce.notification_service.service;

import com.ecommerce.notification_service.channel.NotificationChannel;
import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.dto.response.NotificationResponse;
import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.Notification;
import com.ecommerce.notification_service.entity.NotificationStatus;
import com.ecommerce.notification_service.exception.InvalidEventException;
import com.ecommerce.notification_service.exception.InvalidNotificationStateException;
import com.ecommerce.notification_service.exception.ResourceNotFoundException;
import com.ecommerce.notification_service.mapper.NotificationMapper;
import com.ecommerce.notification_service.repository.NotificationRepository;
import com.ecommerce.notification_service.service.impl.NotificationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceImplTest {

    @Mock NotificationRepository repository;
    @Mock NotificationChannel channel;
    @Mock TransactionTemplate tx;

    private NotificationServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        when(channel.type()).thenReturn(ChannelType.LOG);
        when(channel.requiresRecipient()).thenReturn(false);
        when(channel.resolveRecipient(any())).thenReturn(null);
        when(repository.save(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(1L);
            return n;
        });
        when(repository.findById(1L)).thenAnswer(inv -> Optional.of(stored));
        service = new NotificationServiceImpl(repository, List.of(channel), new MessageFactory(), new NotificationMapper(), objectMapper, tx);
    }

    private Notification stored = notification(NotificationStatus.PENDING);

    private static Notification notification(NotificationStatus status) {
        Notification n = Notification.create("ORDER_CREATED:ORD-1", ChannelType.LOG, "ORDER_CREATED", "ORD-1", "c1", null, "s", "b", status);
        n.setId(1L);
        return n;
    }

    private OrderEventMessage created() {
        return new OrderEventMessage("ORDER_CREATED", "ORD-1", "c1", "PENDING", null, null, null, null, null);
    }

    @Test
    void newEvent_isStoredThenSentThenMarkedSent() throws Exception {
        List<NotificationResponse> result = service.handleEvent(created());

        assertThat(result).hasSize(1);
        verify(channel).send(any(Notification.class));
        verify(repository).markSent(eq(1L), eq(NotificationStatus.SENT), eq(NotificationStatus.PENDING), eq(NotificationStatus.FAILED), any());
        verify(repository, never()).markFailed(anyLong(), any(), any(), anyString(), any());
    }

    @Test
    void duplicateEvent_isIgnored() throws Exception {
        when(repository.existsByDedupeKeyAndChannel("ORDER_CREATED:ORD-1", ChannelType.LOG)).thenReturn(true);

        assertThat(service.handleEvent(created())).isEmpty();
        verify(repository, never()).save(any());
        verify(channel, never()).send(any());
    }

    @Test
    void lostInsertRace_isTreatedAsDuplicate() throws Exception {
        doThrow(new DataIntegrityViolationException("dup")).when(repository).save(any(Notification.class));

        assertThat(service.handleEvent(created())).isEmpty();
        verify(channel, never()).send(any());
    }

    @Test
    void sendFailure_marksFailedAndDoesNotThrow() throws Exception {
        doThrow(new IllegalStateException("smtp down")).when(channel).send(any());

        assertThat(service.handleEvent(created())).hasSize(1);
        verify(repository).markFailed(eq(1L), eq(NotificationStatus.FAILED), eq(NotificationStatus.PENDING), org.mockito.ArgumentMatchers.contains("smtp down"), any());
        verify(repository, never()).markSent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void missingRecipient_isStoredAsSkipped_andNotSent() throws Exception {
        when(channel.requiresRecipient()).thenReturn(true);

        service.handleEvent(created());

        verify(channel, never()).send(any());
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(n -> n.getStatus() == NotificationStatus.SKIPPED));
    }

    @Test
    void unknownEventType_createsNothing() {
        OrderEventMessage e = new OrderEventMessage("WHATEVER", "ORD-1", null, null, null, null, null, null, null);
        assertThat(service.handleEvent(e)).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void badPayloads_throwInvalidEvent() {
        assertThatThrownBy(() -> service.handleEvent("")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> service.handleEvent("not json")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> service.handleEvent("{\"eventType\":\"ORDER_CREATED\"}")).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void jsonPayload_withUnknownFieldsAndInstant_isParsed() throws Exception {
        String json = "{\"eventType\":\"ORDER_CREATED\",\"orderNumber\":\"ORD-1\",\"customerId\":\"c1\",\"occurredAt\":\"2026-01-01T10:00:00Z\",\"extra\":1}";
        assertThat(service.handleEvent(json)).hasSize(1);
    }

    @Test
    void retry_onlyWorksForFailed() throws Exception {
        stored = notification(NotificationStatus.SENT);
        assertThatThrownBy(() -> service.retry(1L)).isInstanceOf(InvalidNotificationStateException.class);

        stored = notification(NotificationStatus.FAILED);
        service.retry(1L);
        verify(channel).send(any());
    }

    @Test
    void retryIfFailed_ignoresOtherStatesAndMissingRows() throws Exception {
        stored = notification(NotificationStatus.SENT);
        service.retryIfFailed(1L);
        service.retryIfFailed(99L);
        verify(channel, never()).send(any());
    }

    @Test
    void get_unknownId_isNotFound() {
        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
