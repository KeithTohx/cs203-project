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

    // what it was about, see AdminChangeTarget. copied names, so they still read right once the
    // trip or the article is gone. null on rows from before these existed
    @Column(name = "itinerary_id")
    private Long itineraryId;

    @Column(name = "itinerary_name")
    private String itineraryName;

    @Column(name = "news_id")
    private Long newsId;

    @Column(name = "news_title", length = 500)
    private String newsTitle;

    protected AdminChange() {
    }

    public AdminChange(Long userId, String message) {
        this(userId, message, AdminChangeTarget.NONE);
    }

    public AdminChange(Long userId, String message, AdminChangeTarget target) {
        this.userId = userId;
        this.message = message;
        this.itineraryId = target.itineraryId();
        this.itineraryName = target.itineraryName();
        this.newsId = target.newsId();
        this.newsTitle = target.newsTitle();
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

    public Long getItineraryId() {
        return itineraryId;
    }

    public String getItineraryName() {
        return itineraryName;
    }

    public Long getNewsId() {
        return newsId;
    }

    public String getNewsTitle() {
        return newsTitle;
    }
}
