package csd.tripsense.recommendation;

import java.time.Instant;
import java.time.LocalDateTime;

import csd.tripsense.impact.Impact;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What to do about one impacted activity.
 *
 * Hangs off the impact rather than the activity, because the impact already carries the user,
 * the news, the itinerary and the activity, and the advice only makes sense against the news
 * story that caused it.
 */
@Entity
@Table(name = "recommendation")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Recommendation {

    private @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne
    @JoinColumn(name = "impact_id", nullable = false)
    private Impact impact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    @Column(length = 500)
    private String advice;

    // null when there is nothing to swap to, eg a whole trip impact or a low severity one
    @ManyToOne
    @JoinColumn(name = "suggested_option_id")
    private ActivityOption suggestedOption;

    // the version (UserPreference.updatedAt) of the preferences this advice was written for.
    // null on rows made before this existed, and for travellers who never saved preferences
    @Column(name = "preferences_version")
    private Instant preferencesVersion;

    @Transient
    private boolean stale;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // one impact can hold several attempts now, so the decision sits on the attempt and not
    // on the impact. newest row per impact is the live one
    // the default is what lets sqlite add this column to a database that already has rows,
    // it refuses a not null column otherwise and every existing db would fail to start
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(255) default 'PENDING' not null")
    private RecommendationStatus status;

    // null until they accept or dismiss, the story wants the decision and when it was made
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;
}
