package csd.tripsense.impact;

import csd.tripsense.activity.Activity;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.news.News;
import csd.tripsense.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "impact", indexes = @jakarta.persistence.Index(
        name = "uk_impact_assessment_key", columnList = "assessment_key", unique = true))
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Impact {
    private @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.Setter(lombok.AccessLevel.NONE)
    @Column(updatable=false)
    private String assessmentIdentity = java.util.UUID.randomUUID().toString();

    @jakarta.persistence.PrePersist
    private void initializeAssessmentIdentity() { assessmentIdentity = java.util.UUID.randomUUID().toString(); }

    // Nullable for historical rows; all newly assessed rows have a stable unique key.
    @Column(name = "assessment_key", length = 120)
    private String assessmentKey;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "news_id", nullable = false)
    private News news;

    @ManyToOne
    @JoinColumn(name = "itinerary_id")
    private Itinerary itinerary;

    @ManyToOne
    @JoinColumn(name = "activity_id")
    private Activity activity;

    @Column
    private Boolean impacted;

    // why the model decided this, the boolean on its own is not much use to a reader
    @Column(length = 500)
    private String reason;

    // set when the user disagrees with the verdict. kept on the row instead of deleting it,
    // because a deleted row would just be evaluated again and the impact would come back.
    // null on older rows, which counts as not rejected
    @Column
    private Boolean rejected;

    // Null on pre-alert rows: recovery must not notify everyone about historical assessments.
    @Column
    private Boolean alertEligible;

    @Column
    private Long judgedRevision;

    public boolean judgedOnCurrentDetails() {
        long revision = activity == null ? itinerary.currentAssessmentRevision() : activity.currentAssessmentRevision();
        return (judgedRevision == null ? 0 : judgedRevision) == revision;
    }

    @jakarta.persistence.OneToMany(mappedBy = "impact", cascade = jakarta.persistence.CascadeType.REMOVE)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.List<csd.tripsense.recommendation.Recommendation> recommendations;

    // An alert contains personal trip data, so remove it whenever its source impact is removed.
    @jakarta.persistence.OneToMany(mappedBy = "impact", cascade = jakarta.persistence.CascadeType.REMOVE,
            fetch = jakarta.persistence.FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.List<csd.tripsense.alert.Alert> alerts;

}

