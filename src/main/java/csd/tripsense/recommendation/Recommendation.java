package csd.tripsense.recommendation;

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

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
