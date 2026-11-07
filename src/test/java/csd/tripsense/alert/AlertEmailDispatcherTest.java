package csd.tripsense.alert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

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
// deliver is called directly, so none of these tests go through the background worker thread
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
