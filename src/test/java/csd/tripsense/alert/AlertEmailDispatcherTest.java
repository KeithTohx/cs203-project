package csd.tripsense.alert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

// unit tests for AlertEmailDispatcher, which claims an alert email, sends it and records the result
// SMTP is mocked. The confirmation-recovery test also exercises the background worker queue.
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertEmailDispatcher Unit Tests")
class AlertEmailDispatcherTest {

    private static final Long ALERT_ID = 1L;

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private AlertEmailStore emailStore;

    @Mock
    private AlertEmailSender emailSender;

    @InjectMocks
    private AlertEmailDispatcher dispatcher;

    private AlertEmailStore.Delivery delivery;

    @BeforeEach
    void setUp() {
        delivery = new AlertEmailStore.Delivery(ALERT_ID, 1, "key", "user@example.com", null);
    }

    @AfterEach
    void tearDown() {
        dispatcher.stop();
    }

    // ===== DELIVER TESTS =====

    @Test
    @DisplayName("Should record the email as sent when the SMTP server accepts it")
    void deliver_ShouldRecordSent_WhenSmtpAcceptsEmail() throws Exception {
        // Arrange
        when(emailSender.enabled()).thenReturn(true);
        when(emailStore.claim(eq(ALERT_ID), any(Instant.class))).thenReturn(Optional.of(delivery));

        // Act
        dispatcher.deliver(ALERT_ID);

        // Assert
        InOrder order = inOrder(emailSender, emailStore);
        order.verify(emailSender, times(1)).send(delivery);
        order.verify(emailStore, times(1)).sent(eq(delivery), any(Instant.class));
        verify(emailStore, never()).failed(any(), any());
    }

    @Test
    @DisplayName("Should record a failed attempt so it can be retried when the SMTP server rejects it")
    void deliver_ShouldRecordFailure_WhenSmtpFails() throws Exception {
        // Arrange
        when(emailSender.enabled()).thenReturn(true);
        when(emailStore.claim(eq(ALERT_ID), any(Instant.class))).thenReturn(Optional.of(delivery));
        doThrow(new MailSendException("temporary failure")).when(emailSender).send(delivery);

        // Act
        dispatcher.deliver(ALERT_ID);

        // Assert
        verify(emailStore, times(1)).failed(eq(delivery), any(Instant.class));
        verify(emailStore, never()).sent(any(), any());
    }

    @Test
    @DisplayName("Should not claim the email or use up an attempt when email alerts are disabled")
    void deliver_ShouldNotClaimEmail_WhenEmailDisabled() {
        // Arrange
        when(emailSender.enabled()).thenReturn(false);

        // Act
        dispatcher.deliver(ALERT_ID);

        // Assert
        verifyNoInteractions(emailStore);
    }

    @Test
    void acceptedEmailRetriesConfirmationWithoutBlockingOtherGroups() throws Exception {
        when(emailSender.enabled()).thenReturn(true);
        when(emailStore.claim(eq(ALERT_ID), any(Instant.class))).thenReturn(Optional.of(delivery));
        var attempts = new AtomicInteger();
        var resumed = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) throw new DataAccessResourceFailureException("database locked");
            return null;
        }).when(emailStore).sent(eq(delivery), any(Instant.class));
        when(emailStore.claim(eq(2L), any(Instant.class))).thenAnswer(invocation -> {
            resumed.countDown();
            return Optional.empty();
        });

        dispatcher.deliver(ALERT_ID);
        dispatcher.deliver(2L);
        verify(emailStore, times(1)).claim(eq(ALERT_ID), any(Instant.class));
        verify(emailStore).claim(eq(2L), any(Instant.class), any());
        verify(emailStore, never()).failed(any(), any());

        // Confirmation retries run before queued sends.
        dispatcher.retryPending();
        var nextAlert = mock(AlertResponse.class);
        when(nextAlert.id()).thenReturn(2L);
        dispatcher.onCreated(new AlertCreatedEvent(10L, nextAlert));
        assertThat(resumed.await(2, TimeUnit.SECONDS)).isTrue();

        verify(emailSender, times(1)).send(delivery);
        verify(emailStore, never()).failed(any(), any());

        var acceptedTimes = ArgumentCaptor.forClass(Instant.class);
        verify(emailStore, times(2)).sent(eq(delivery), acceptedTimes.capture());
        assertThat(acceptedTimes.getAllValues().get(1)).isEqualTo(acceptedTimes.getAllValues().get(0));
    }

    @Test void permanentConfirmationFailureDoesNotStopAnotherDelivery() throws Exception {
        when(emailSender.enabled()).thenReturn(true);
        when(emailStore.claim(eq(ALERT_ID), any(Instant.class))).thenReturn(Optional.of(delivery));
        doThrow(new DataAccessResourceFailureException("unavailable confirmation"))
                .when(emailStore).sent(eq(delivery), any(Instant.class));
        var other = new AlertEmailStore.Delivery(2L, 1, "other-group", "other@example.com", null);
        when(emailStore.claim(eq(2L), any(Instant.class), any())).thenReturn(Optional.of(other));
        dispatcher.deliver(ALERT_ID);
        dispatcher.deliver(2L);
        verify(emailSender).send(delivery);
        verify(emailSender).send(other);
        verify(emailStore).sent(eq(other), any(Instant.class));
        verify(emailStore, never()).failed(any(), any());
    }

    // ===== RETRY PENDING TESTS =====

    @Test
    @DisplayName("Should not look for pending emails when email alerts are disabled")
    void retryPending_ShouldNotClaimEmails_WhenEmailDisabled() {
        // Arrange
        when(emailSender.enabled()).thenReturn(false);

        // Act
        dispatcher.retryPending();

        // Assert
        verifyNoInteractions(alertRepository, emailStore);
    }
}
