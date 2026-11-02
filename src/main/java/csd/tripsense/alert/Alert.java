package csd.tripsense.alert;

import csd.tripsense.impact.Impact;
import jakarta.persistence.*;
import java.time.Instant;

/** One durable notification per assessed impact. Ownership follows the impact's itinerary. */
@Entity
@Table(name = "alert")
public class Alert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "impact_id", nullable = false, unique = true)
    private Impact impact;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant readAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailStatus emailStatus = EmailStatus.PENDING;
    @Column(nullable = false)
    // BIGINT avoids a SQLite/Hibernate identity-DDL bug triggered by an earlier INTEGER counter column.
    private long emailAttempts;
    @Column(nullable = false)
    private Instant emailNextAttemptAt = Instant.now();
    private Instant emailSentAt;
    @Column(nullable = false, updatable = false)
    private String emailDeliveryKey = java.util.UUID.randomUUID().toString();

    protected Alert() {}
    public Alert(Impact impact) { this.impact = impact; }
    public Long getId() { return id; }
    public Impact getImpact() { return impact; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public EmailStatus getEmailStatus() { return emailStatus; }
    public int getEmailAttempts() { return Math.toIntExact(emailAttempts); }
    public Instant getEmailNextAttemptAt() { return emailNextAttemptAt; }
    public Instant getEmailSentAt() { return emailSentAt; }
    public String getEmailDeliveryKey() { return emailDeliveryKey; }
    public void markRead() { if (readAt == null) readAt = Instant.now(); }
}
