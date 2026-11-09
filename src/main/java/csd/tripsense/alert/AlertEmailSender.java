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
import org.springframework.web.util.HtmlUtils;

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
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(delivery.recipient());
        AlertResponse alert = delivery.alert();
        boolean combined = delivery.relatedAlerts().size() > 1;
        boolean activityAlert = !combined && alert.activityId() != null;
        String scope = combined ? "Disruption summary" : activityAlert ? "Activity alert" : "Itinerary alert";
        String target = activityAlert ? text(alert.activityName()) : text(alert.itineraryName());
        String explanation = combined ? "One news update, with the overall itinerary impact and affected activities together."
                : activityAlert
                ? "This alert concerns one activity in your itinerary."
                : "This alert concerns your itinerary overall.";
        if (delivery.partialAssessment()) {
            explanation += " Some activities have not been assessed. This email includes the available assessments; "
                    + "check TripSense for any later updates.";
        }
        String plainAssessment = delivery.relatedAlerts().stream().map(a ->
                (a.activityId() == null ? "Overall itinerary" : "Activity: " + text(a.activityName()))
                        + "\n" + text(a.reason())).collect(java.util.stream.Collectors.joining("\n\n"));
        String htmlAssessment = delivery.relatedAlerts().stream().map(a -> "<div style=\"margin-bottom:16px\"><strong>"
                + (a.activityId() == null ? "Overall itinerary" : "Activity: " + escape(a.activityName()))
                + "</strong><br>" + escape(a.reason()) + "</div>").collect(java.util.stream.Collectors.joining());
        helper.setSubject(("TripSense | " + scope + ": " + target
                + (activityAlert ? " | " + text(alert.itineraryName()) : "")).replaceAll("[\\r\\n]", " "));
        String plain = scope.toUpperCase(java.util.Locale.ROOT) + "\n" + target
                + "\n" + explanation + "\n\nItinerary: " + text(alert.itineraryName())
                + (activityAlert ? "\nActivity: " + target : "")
                + "\n\nWHAT HAPPENED\n" + text(alert.newsTitle())
                + "\n\nHOW THIS MAY AFFECT YOU\n" + plainAssessment
                + "\n\nSource: " + text(alert.newsSource())
                + (alert.newsUrl() == null ? "" : "\n" + alert.newsUrl())
                + "\n\nNEXT STEP\nLog in to TripSense to review this "
                + (activityAlert ? "activity" : "itinerary") + " and decide what to do."
                + "\n\nThis is an automated assessment. Verify important changes with your travel provider."
                + "\nAlert reference: " + alert.id();
        String html = """
                <!doctype html>
                <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                    </head>
                    <body style="margin:0;background:#f1f4f9;color:#16252b;font-family:Arial,sans-serif;line-height:1.6">
                        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0">
                            <tr>
                                <td style="padding:24px 12px">
                                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:600px;margin:auto;background:#ffffff;border:1px solid #e2e8f0;border-radius:8px">
                                        <tr>
                                            <td style="padding:18px 28px;border-bottom:1px solid #e2e8f0">
                                                <span style="display:inline-block;padding:2px 9px;border-radius:6px;background:#2878d0;color:#ffffff;font-weight:bold">
                                                    T
                                                </span>
                                                <span style="margin-left:8px;font-size:20px;font-weight:bold;color:#16252b">
                                                    TripSense
                                                </span>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td style="padding:24px 28px 0">
                                                <span style="display:inline-block;padding:3px 10px;border-radius:4px;background:#e7f0fc;color:#245e9d;font-size:12px;font-weight:bold">
                                                    %s
                                                </span>
                                                <h1 style="margin:12px 0 0;font-size:26px;line-height:1.3;color:#16252b">
                                                    %s
                                                </h1>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td style="padding:24px 28px">
                                                <p style="margin:0 0 20px;color:#5d6f76">
                                                    %s
                                                </p>
                                                <p>
                                                    <strong>
                                                        Itinerary:
                                                    </strong>
                                                    %s%s
                                                </p>
                                                <div style="margin-top:24px;padding:16px;background:#fbeceb;border-left:4px solid #d7312f;border-radius:4px">
                                                    <span style="display:inline-block;padding:2px 7px;background:#ffffff;color:#b42318;border-radius:4px;font-size:12px;font-weight:bold">
                                                        Disruption detected
                                                    </span>
                                                    <h2 style="font-size:16px;margin:12px 0 6px">
                                                        What happened
                                                    </h2>
                                                    <p style="margin:0">
                                                        %s
                                                    </p>
                                                    <h2 style="font-size:16px;margin:20px 0 6px">
                                                        How this may affect you
                                                    </h2>
                                                    %s
                                                </div>
                                                <p style="font-size:14px;color:#5d6f76">
                                                    Source: %s%s
                                                </p>
                                                <div style="padding:16px;background:#e7f0fc;border-left:4px solid #2878d0;border-radius:4px;margin-top:24px">
                                                    <strong>
                                                        Next step
                                                    </strong>
                                                    <br>
                                                    Log in to TripSense to review this %s and decide what to do.
                                                </div>
                                                <p style="font-size:12px;color:#5d6f76;margin-top:24px;padding-top:16px;border-top:1px solid #e2e8f0">
                                                    This is an automated assessment.
                Verify important changes with your travel provider.
                                                    <br>
                                                    Alert reference: %s
                                                </p>
                                            </td>
                                        </tr>
                                    </table>
                                </td>
                            </tr>
                        </table>
                    </body>
                </html>
                """.formatted(escape(scope), escape(target), escape(explanation),
                        escape(alert.itineraryName()), activityAlert ? "<br><strong>Activity:</strong> " + escape(target) : "",
                        escape(alert.newsTitle()), htmlAssessment, escape(alert.newsSource()),
                        sourceLink(alert.newsUrl()), activityAlert ? "activity" : "itinerary", alert.id());
        helper.setText(plain, html);
        sender.send(message);
    }

    private static String text(String value) { return value == null || value.isBlank() ? "Not provided" : value; }

    private static String escape(String value) { return HtmlUtils.htmlEscape(text(value), "UTF-8"); }

    private static String sourceLink(String url) {
        if (url == null) return "";
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) {
                return "<br><a style=\"color:#245e9d\" href=\"" + escape(url) + "\">Read source article</a>";
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid source URLs are omitted from the HTML rather than made clickable.
        }
        return "";
    }
}
