package csd.tripsense.alert;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlertEmailDispatcherTest {
    private final AlertEmailStore store = mock(AlertEmailStore.class);
    private final AlertEmailSender sender = mock(AlertEmailSender.class);
    private final AlertEmailDispatcher dispatcher = new AlertEmailDispatcher(mock(AlertRepository.class), store, sender);
    private final AlertEmailStore.Delivery delivery = new AlertEmailStore.Delivery(1L, 1, "key", "user@example.com", null);
    @AfterEach void stop() { dispatcher.stop(); }

    @Test void successfulSmtpAcceptanceIsRecorded() throws Exception {
        when(sender.enabled()).thenReturn(true); when(store.claim(eq(1L), any())).thenReturn(Optional.of(delivery));
        dispatcher.deliver(1L);
        var order = inOrder(store, sender); order.verify(sender).send(delivery); order.verify(store).sent(eq(delivery), any(Instant.class));
        verify(store, never()).failed(any(), any());
    }

    @Test void smtpFailureIsRetriedWithoutBreakingInAppDelivery() throws Exception {
        when(sender.enabled()).thenReturn(true); when(store.claim(eq(1L), any())).thenReturn(Optional.of(delivery));
        doThrow(new MailSendException("temporary failure")).when(sender).send(delivery);
        dispatcher.deliver(1L);
        verify(store).failed(eq(delivery), any(Instant.class)); verify(store, never()).sent(any(), any());
    }

    @Test void disabledEmailDoesNotConsumeAttempts() {
        dispatcher.deliver(1L); dispatcher.retryPending();
        verifyNoInteractions(store);
    }
}
