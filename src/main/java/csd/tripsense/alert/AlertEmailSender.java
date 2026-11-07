package csd.tripsense.alert;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Standard SMTP; credentials are supplied through Spring's mail environment settings. */
@Component
public class AlertEmailSender {
    private final ObjectProvider<JavaMailSender> senders;
    private final boolean enabled;
    private final String from;

    public AlertEmailSender(ObjectProvider<JavaMailSender> senders,
                            @Value("${alerts.email.enabled:false}") boolean enabled,
                            @Value("${alerts.email.from:}") String from) {
        this.senders = senders;
        this.enabled = enabled;
        this.from = from;
    }

    @PostConstruct
    void validate() {
        if (!enabled) return;
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) {
            throw new IllegalStateException("Email alerts require spring.mail.host and alerts.email.from");
        }
        if (sender instanceof JavaMailSenderImpl smtp) {
            var properties = smtp.getJavaMailProperties();
            if (!"smtps".equalsIgnoreCase(smtp.getProtocol())) {
                properties.putIfAbsent("mail.smtp.starttls.enable", "true");
                properties.putIfAbsent("mail.smtp.starttls.required", "true");
            }
            if (smtp.getUsername() != null && !smtp.getUsername().isBlank()) {
                properties.putIfAbsent("mail.smtp.auth", "true");
            }
            properties.putIfAbsent("mail.smtps.connectiontimeout", "5000");
            properties.putIfAbsent("mail.smtps.timeout", "10000");
            properties.putIfAbsent("mail.smtps.writetimeout", "10000");
            properties.putIfAbsent("mail.smtp.connectiontimeout", "5000");
            properties.putIfAbsent("mail.smtp.timeout", "10000");
            properties.putIfAbsent("mail.smtp.writetimeout", "10000");
        }
    }

    public boolean enabled() { return enabled; }

    public void send(AlertEmailStore.Delivery delivery) throws MessagingException {
        if (!enabled) throw new IllegalStateException("Email alerts are disabled");
        JavaMailSender sender = senders.getObject();
        // A stable Message-ID helps mail servers recognize retries. SMTP acceptance is not proof of inbox delivery.
        MimeMessage message = new MimeMessage(sender.createMimeMessage().getSession()) {
            @Override protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", "<tripsense-alert-" + delivery.key() + "@tripsense.local>");
            }
        };
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(from);
        helper.setTo(delivery.recipient());
        helper.setSubject("TripSense: disruption affecting " + delivery.alert().itineraryName().replaceAll("[\\r\\n]", " "));
        AlertResponse alert = delivery.alert();
        helper.setText("A disruption may affect your trip: " + alert.itineraryName()
                + (alert.activityName() == null ? "" : "\nActivity: " + alert.activityName())
                + "\nNews: " + alert.newsTitle() + "\nAssessment: " + alert.reason()
                + "\nSource: " + (alert.newsSource() == null ? "Not provided" : alert.newsSource())
                + (alert.newsUrl() == null ? "" : "\n" + alert.newsUrl())
                + "\n\nThis is an automated assessment. Verify important changes with your travel provider."
                + "\nLog in to TripSense to review the alert and decide what to do.", false);
        sender.send(message);
    }
}
