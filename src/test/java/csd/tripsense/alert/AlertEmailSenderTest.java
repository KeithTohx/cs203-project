package csd.tripsense.alert;

import csd.tripsense.alert.AlertEmailStore.Delivery;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertEmailSenderTest {
    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(provider.getObject()).thenReturn(sender);
        return provider;
    }

    private Delivery delivery() {
        var alert = new AlertResponse(1L, 2L, 3L, "Japan trip", null, null, 4L, "Resort closure",
                "Test source", "https://example.com/news", "Ski lifts are closed", Instant.now(),
                null, EmailStatus.PENDING, null);
        return new Delivery(1L, 1, "stable-key", "traveller@example.com", alert);
    }

    @Test void smtpMessageContainsOnlyTheRecipientsTripAndHasAStableMessageId() throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var sender = new AlertEmailSender(provider(mail), true, "alerts@example.com");
        sender.validate(); sender.send(delivery());
        var captor = ArgumentCaptor.forClass(MimeMessage.class); verify(mail).send(captor.capture());
        MimeMessage message = captor.getValue(); message.saveChanges();
        assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("traveller@example.com");
        assertThat(message.getSubject()).contains("Japan trip");
        assertThat(message.getContent().toString()).contains("Ski lifts are closed", "https://example.com/news", "automated assessment");
        assertThat(message.getMessageID()).isEqualTo("<tripsense-alert-stable-key@tripsense.local>");
    }

    @Test void disabledEmailDoesNotRequireCredentialsOrConnectToSmtp() {
        var provider = provider(null);
        var sender = new AlertEmailSender(provider, false, "");
        sender.validate();
        assertThat(sender.enabled()).isFalse();
        assertThatThrownBy(() -> sender.send(delivery())).isInstanceOf(IllegalStateException.class);
        verify(provider, never()).getObject();
    }

    @Test void enabledEmailRequiresHostAndSender() {
        assertThatThrownBy(() -> new AlertEmailSender(provider(null), true, "alerts@example.com").validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.mail.host");
    }

    @Test void smtpHasTlsAndFiniteTimeoutsByDefault() {
        JavaMailSenderImpl mail = new JavaMailSenderImpl(); mail.setUsername("smtp-user");
        new AlertEmailSender(provider(mail), true, "alerts@example.com").validate();
        assertThat(mail.getJavaMailProperties()).containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.auth", "true").containsEntry("mail.smtp.connectiontimeout", "5000")
                .containsEntry("mail.smtp.timeout", "10000");
    }
}
