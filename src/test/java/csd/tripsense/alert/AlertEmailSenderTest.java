package csd.tripsense.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        assertEquals("true", properties.getProperty("mail.smtp.starttls.required"));
        assertEquals("true", properties.getProperty("mail.smtp.auth"));
        assertEquals("5000", properties.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("10000", properties.getProperty("mail.smtp.timeout"));
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
        assertTrue(subject.contains("Japan trip"), "email subject was: " + subject);

        String body = message.getContent().toString();
        assertTrue(body.contains("Ski lifts are closed"), "email body was: " + body);
        assertTrue(body.contains("https://example.com/news"), "email body was: " + body);
        assertTrue(body.contains("automated assessment"), "email body was: " + body);

        assertEquals("<tripsense-alert-stable-key@tripsense.local>", message.getMessageID());
    }
}
