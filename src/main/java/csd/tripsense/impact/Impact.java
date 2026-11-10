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
@Table(name = "impact")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Impact {
    private @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

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

    // the revision of the activity (for an activity verdict) or the trip (for a whole trip
    // verdict) that the model was shown. null on older rows, which reads as revision 0.
    // a Long for the same reason as Itinerary.revision
    @Column(name = "judged_revision")
    private Long judgedRevision;

    public boolean wasJudgedOn(long revision) {
        long judgedOn = judgedRevision == null ? 0 : judgedRevision;
        return judgedOn == revision;
    }

    // false once the trip or activity has been edited since this was judged, so the verdict is
    // about details the traveller no longer has
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isJudgedOnCurrentDetails() {
        if (activity != null) {
            return wasJudgedOn(activity.currentRevision());
        }
        return wasJudgedOn(itinerary.currentRevision());
    }

    // An alert contains personal trip data, so remove it whenever its source impact is removed.
    @jakarta.persistence.OneToMany(mappedBy = "impact", cascade = jakarta.persistence.CascadeType.REMOVE,
            fetch = jakarta.persistence.FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.List<csd.tripsense.alert.Alert> alerts;

    // advice about this impact makes no sense without it, so it goes too. this is what lets an
    // impact, activity or itinerary be deleted without clearing its recommendations by hand first
    @jakarta.persistence.OneToMany(mappedBy = "impact", cascade = jakarta.persistence.CascadeType.REMOVE,
            fetch = jakarta.persistence.FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.List<csd.tripsense.recommendation.Recommendation> recommendations;

}

