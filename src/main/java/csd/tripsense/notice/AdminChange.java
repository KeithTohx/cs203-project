package csd.tripsense.notice;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// one thing an admin changed in someone's trips, worded for that traveller. the audit log has the
// admin side of it. like the audit log it keeps a plain user id rather than a link to the user
// table, and the rows go when the account is deleted
@Entity
@Table(name = "admin_change")
public class AdminChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // whose data was changed, and who gets told
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "read_at")
    private Instant readAt;

    protected AdminChange() {
    }

    public AdminChange(Long userId, String message) {
        this.userId = userId;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
