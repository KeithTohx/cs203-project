package csd.tripsense.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import csd.tripsense.alert.AlertEmailStore.Delivery;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import jakarta.mail.Address;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

// unit tests for AlertEmailSender, which checks the mail settings on startup and builds the alert email
// the mail sender is a Mockito mock, so no test here connects to a real SMTP server
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertEmailSender Unit Tests")
class AlertEmailSenderTest {

    private static final String FROM = "alerts@example.com";

    @Mock
    private ObjectProvider<JavaMailSender> mailSenders;

    @Mock
    private JavaMailSender mailSender;

    @Captor
    private ArgumentCaptor<MimeMessage> messageCaptor;

    private AlertEmailStore.Delivery delivery;

    @BeforeEach
    void setUp() {
        AlertResponse alert = new AlertResponse(1L, 2L, 3L, "Japan trip", null, null, 4L, "Resort closure",
                "Test source", "https://example.com/news", "Ski lifts are closed", Instant.now(),
                null, EmailStatus.PENDING, null);
        delivery = new AlertEmailStore.Delivery(1L, 1, "stable-key", "traveller@example.com", alert);
    }

    // ===== VALIDATE TESTS =====

    @Test
    @DisplayName("Should throw IllegalStateException when email is enabled but no mail host is set")
    void validate_ShouldThrowIllegalStateException_WhenMailHostMissing() {
        // Arrange
        // mailSenders returns null by default, the same as having no spring.mail.host
        AlertEmailSender emailSender = new AlertEmailSender(mailSenders, true, FROM);

        // Act
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> emailSender.validate());

        // Assert
        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.contains("spring.mail.host"), "error message was: " + errorMessage);
    }

    @Test
    @DisplayName("Should throw IllegalStateException when email is enabled but the sender address is blank")
    void validate_ShouldThrowIllegalStateException_WhenFromAddressBlank() {
        // Arrange
        when(mailSenders.getIfAvailable()).thenReturn(mailSender);
        AlertEmailSender emailSender = new AlertEmailSender(mailSenders, true, " ");

        // Act
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> emailSender.validate());

        // Assert
        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.contains("alerts.email.from"), "error message was: " + errorMessage);
    }

    @Test
    @DisplayName("Should turn on TLS and finite timeouts when the SMTP settings leave them out")
    void validate_ShouldSetTlsAndTimeouts_WhenSmtpDefaultsUsed() {
        // Arrange
        JavaMailSenderImpl smtp = new JavaMailSenderImpl();
        smtp.setUsername("smtp-user");
        when(mailSenders.getIfAvailable()).thenReturn(smtp);
        AlertEmailSender emailSender = new AlertEmailSender(mailSenders, true, FROM);

        // Act
        emailSender.validate();

        // Assert
        Properties properties = smtp.getJavaMailProperties();
        assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", properties.getProperty("mail.smtp.starttls.required"));
        assertEquals("true", properties.getProperty("mail.smtp.auth"));
        assertEquals("5000", properties.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("10000", properties.getProperty("mail.smtp.timeout"));
        assertEquals("10000", properties.getProperty("mail.smtp.writetimeout"));
        assertEquals("5000", properties.getProperty("mail.smtps.connectiontimeout"));
        assertEquals("10000", properties.getProperty("mail.smtps.timeout"));
        assertEquals("10000", properties.getProperty("mail.smtps.writetimeout"));
    }

    @Test
    @DisplayName("Should not need mail settings or connect to SMTP when email is disabled")
    void validate_ShouldSkipMailSender_WhenEmailDisabled() {
        // Arrange
        AlertEmailSender emailSender = new AlertEmailSender(mailSenders, false, "");

        // Act
        emailSender.validate();

        // Assert
        assertFalse(emailSender.enabled());
        assertThrows(IllegalStateException.class, () -> emailSender.send(delivery));
        verify(mailSenders, never()).getObject();
    }

    // ===== SEND TESTS =====

    @Test
    @DisplayName("Should email only the trip owner with a stable message id when sending an alert")
    void send_ShouldEmailOnlyRecipientWithStableMessageId_WhenEmailEnabled() throws Exception {
        // Arrange
        MimeMessage blankMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSenders.getIfAvailable()).thenReturn(mailSender);
        when(mailSenders.getObject()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(blankMessage);
        AlertEmailSender emailSender = new AlertEmailSender(mailSenders, true, FROM);
        emailSender.validate();

        // Act
        emailSender.send(delivery);

        // Assert
        verify(mailSender, times(1)).send(messageCaptor.capture());
        MimeMessage message = messageCaptor.getValue();
        message.saveChanges();

        Address[] recipients = message.getAllRecipients();
        assertEquals(1, recipients.length);
        assertEquals("traveller@example.com", recipients[0].toString());
        String subject = message.getSubject();
        assertEquals("TripSense | Itinerary summary: Japan trip", subject);
        assertThat(body(message, "text/html")).contains("Itinerary summary", "What happened",
                "How this may affect you", "Read source article");

        String body = body(message, "text/plain");
        assertTrue(body.contains("Ski lifts are closed"), "email body was: " + body);
        assertTrue(body.contains("https://example.com/news"), "email body was: " + body);
        assertTrue(body.contains("automated assessment"), "email body was: " + body);

        assertEquals("<tripsense-alert-stable-key@tripsense.local>", message.getMessageID());
    }

    @Test void singleActivityUsesItinerarySummaryAndEscapesUntrustedContent() throws Exception {
        JavaMailSender mail = mailSender;
        when(mailSenders.getObject()).thenReturn(mailSender);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var alert = new AlertResponse(31L, 53L, 9L, "Bali", 17L, "Trek <sunrise>\r\nBcc: test",
                4L, "Closure <script>alert(1)</script>", "Source & news", "javascript:alert(1)",
                "Access <restricted>", Instant.now(), null, EmailStatus.PENDING, null);
        new AlertEmailSender(mailSenders, true, "alerts@example.com")
                .send(new Delivery(31L, 1, "activity-key", "traveller@example.com", alert));
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(captor.capture());
        MimeMessage message = captor.getValue(); message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("TripSense | Itinerary summary: Bali")
                .doesNotContain("\r", "\n");
        assertThat(message.getHeader("Bcc")).isNull();
        assertThat(body(message, "text/plain")).contains("ITINERARY SUMMARY", "one activity", "Itinerary: Bali", "Alert reference: 31");
        assertThat(body(message, "text/html")).contains("Itinerary summary", "&lt;sunrise&gt;", "&lt;script&gt;", "Source &amp; news")
                .doesNotContain("<script>", "javascript:", "Read source article", "itinerary overall");
    }

    @Test void groupedEmailContainsOverallAndActivityImpactsInOneMessage() throws Exception {
        JavaMailSender mail = mailSender;
        when(mailSenders.getObject()).thenReturn(mailSender);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        AlertResponse overall = delivery.alert();
        var activity = new AlertResponse(2L, 5L, 3L, "Japan trip", 8L, "Niseko skiing", 4L,
                "Resort closure", "Test source", "https://example.com/news", "Activity cancelled",
                Instant.now(), null, EmailStatus.PENDING, null);
        var grouped = new Delivery(1L, 1, "stable-key", "traveller@example.com", overall,
                java.util.List.of(overall, activity), java.util.List.of(
                        new AlertEmailStore.ClaimedAlert(1L, 1), new AlertEmailStore.ClaimedAlert(2L, 1)));
        new AlertEmailSender(mailSenders, true, "alerts@example.com").send(grouped);
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail, times(1)).send(captor.capture());
        MimeMessage message = captor.getValue(); message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("TripSense | Itinerary summary: Japan trip");
        assertThat(body(message, "text/plain")).contains("Overall itinerary", "Activity: Niseko skiing",
                "Ski lifts are closed", "Activity cancelled");
        assertThat(body(message, "text/html")).contains("Overall itinerary", "Activity: Niseko skiing",
                "Ski lifts are closed", "Activity cancelled").doesNotContain("arrive separately");
    }

    @Test void partialSummaryExplainsThatSomeActivitiesWereNotAssessed() throws Exception {
        JavaMailSender mail = mailSender;
        when(mailSenders.getObject()).thenReturn(mailSender);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        Delivery original = delivery;
        Delivery partial = new Delivery(original.alertId(), original.attempt(), original.key(), original.recipient(),
                original.alert(), original.relatedAlerts(), original.claims(), true);
        new AlertEmailSender(mailSenders, true, "alerts@example.com").send(partial);
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(captor.capture());
        MimeMessage message = captor.getValue(); message.saveChanges();
        assertThat(body(message, "text/plain")).contains("Some activities have not been assessed", "available assessments");
        assertThat(body(message, "text/html")).contains("Some activities have not been assessed", "available assessments");
    }

    @Test void itinerarySummaryContainsEveryArticlesTitleSourceAndReason() throws Exception {
        when(mailSenders.getObject()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var other = new AlertResponse(2L, 5L, 3L, "Japan trip", null, null, 9L,
                "Rail <strike>", "Rail source", "https://example.com/rail", "Trains suspended",
                Instant.now(), null, EmailStatus.PENDING, null);
        var summary = new Delivery(1L, 1, "digest-key", "traveller@example.com", delivery.alert(),
                java.util.List.of(delivery.alert(), other), java.util.List.of());
        new AlertEmailSender(mailSenders, true, FROM).send(summary);
        verify(mailSender).send(messageCaptor.capture());
        var message = messageCaptor.getValue(); message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("TripSense | Itinerary summary: Japan trip");
        assertThat(body(message, "text/plain")).contains("Resort closure", "Rail <strike>",
                "https://example.com/news", "https://example.com/rail", "Trains suspended");
        assertThat(body(message, "text/html")).contains("Resort closure", "Rail &lt;strike&gt;",
                "Rail source", "Trains suspended").doesNotContain("Rail <strike>");
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
}
