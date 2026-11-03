package csd.tripsense.alert;

import csd.tripsense.alert.AlertEmailStore.Delivery;
import jakarta.mail.Session;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
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
        assertThat(message.getSubject()).isEqualTo("TripSense | Itinerary alert: Japan trip");
        assertThat(body(message, "text/plain")).contains("Ski lifts are closed", "https://example.com/news", "automated assessment",
                "ITINERARY ALERT", "itinerary overall");
        assertThat(body(message, "text/html")).contains("Itinerary alert", "What happened", "How this may affect you", "Read source article");
        assertThat(message.getMessageID()).isEqualTo("<tripsense-alert-stable-key@tripsense.local>");
    }

    @Test void activityEmailHasDistinctScopeAndEscapesUntrustedContent() throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var alert = new AlertResponse(31L, 53L, 9L, "Bali", 17L, "Trek <sunrise>\r\nBcc: test",
                4L, "Closure <script>alert(1)</script>", "Source & news", "javascript:alert(1)",
                "Access <restricted>", Instant.now(), null, EmailStatus.PENDING, null);
        new AlertEmailSender(provider(mail), true, "alerts@example.com")
                .send(new Delivery(31L, 1, "activity-key", "traveller@example.com", alert));
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(captor.capture());
        MimeMessage message = captor.getValue(); message.saveChanges();
        assertThat(message.getSubject()).startsWith("TripSense | Activity alert: Trek").endsWith(" | Bali")
                .doesNotContain("\r", "\n");
        assertThat(message.getHeader("Bcc")).isNull();
        assertThat(body(message, "text/plain")).contains("ACTIVITY ALERT", "one activity", "Itinerary: Bali", "Alert reference: 31");
        assertThat(body(message, "text/html")).contains("Activity alert", "&lt;sunrise&gt;", "&lt;script&gt;", "Source &amp; news")
                .doesNotContain("<script>", "javascript:", "Read source article", "itinerary overall");
    }

    private String body(Part part, String mimeType) throws Exception {
        if (part.isMimeType(mimeType)) return (String) part.getContent();
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String result = body(multipart.getBodyPart(i), mimeType);
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }

    @Test void disabledEmailDoesNotRequireCredentialsOrConnectToSmtp() {
        var provider = provider(null);
        var sender = new AlertEmailSender(provider, false, "");
        sender.validate();
        assertThat(sender.enabled()).isFalse();
        assertThatThrownBy(() -> sender.send(delivery())).isInstanceOf(IllegalStateException.class);
        verify(provider, never()).getObject();
    }

    @Test void groupedEmailContainsOverallAndActivityImpactsInOneMessage() throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        AlertResponse overall = delivery().alert();
        var activity = new AlertResponse(2L, 5L, 3L, "Japan trip", 8L, "Niseko skiing", 4L,
                "Resort closure", "Test source", "https://example.com/news", "Activity cancelled",
                Instant.now(), null, EmailStatus.PENDING, null);
        var grouped = new Delivery(1L, 1, "stable-key", "traveller@example.com", overall,
                java.util.List.of(overall, activity), java.util.List.of(
                        new AlertEmailStore.ClaimedAlert(1L, 1), new AlertEmailStore.ClaimedAlert(2L, 1)));
        new AlertEmailSender(provider(mail), true, "alerts@example.com").send(grouped);
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail, times(1)).send(captor.capture());
        MimeMessage message = captor.getValue(); message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("TripSense | Disruption summary: Japan trip");
        assertThat(body(message, "text/plain")).contains("Overall itinerary", "Activity: Niseko skiing",
                "Ski lifts are closed", "Activity cancelled");
        assertThat(body(message, "text/html")).contains("Overall itinerary", "Activity: Niseko skiing",
                "Ski lifts are closed", "Activity cancelled").doesNotContain("arrive separately");
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
