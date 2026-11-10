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

    // a new random value every time a verdict is written to this row. sqlite hands a deleted row's
    // id to the next insert, so an id alone cannot tell the verdict someone read from the one that
    // replaced it. anything worked out from a verdict (eg advice) compares this before it is saved.
    // null on older rows, which still matches until the row is judged again
    @Column(name = "verdict_key", length = 36)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String verdictKey;

    // raise this whenever the prompts, or the rules applied to the model's answer, change in a way
    // that would give different verdicts. verdicts from an older version count as out of date like
    // an edit's, so OutdatedVerdictSweeper clears them on the next start and they are judged again.
    // the seed impacts in data.sql carry this number too, so raise it there as well
    //   1 - the first prompts (rows from before this existed have null, which reads as 1)
    //   2 - verdicts must name a kind of disruption, politics and diplomacy are never enough
    public static final long CURRENT_PROMPT_VERSION = 2;

    // a Long and not an Integer for the same reason as Itinerary.revision: the sqlite dialect drops
    // the type from the id column when a table has another integer column, and ids stop being made
    @Column(name = "prompt_version")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long promptVersion;

    public long promptVersionOrDefault() {
        if (promptVersion == null) {
            return 1;
        }
        return promptVersion;
    }

    public boolean isFromCurrentPrompt() {
        return promptVersionOrDefault() == CURRENT_PROMPT_VERSION;
    }

    // judged on these details, by the prompts as they are now
    public boolean wasJudgedOn(long revision) {
        long judgedOn = judgedRevision == null ? 0 : judgedRevision;
        return judgedOn == revision && isFromCurrentPrompt();
    }

    // false once the trip or activity has been edited since this was judged, so the verdict is
    // about details the traveller no longer has. also false for a verdict from older prompts
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

    // whether deleting this verdict also takes an alert out of the traveller's bell.
    // needs an open session, the alerts are loaded lazily
    public boolean hasAlerts() {
        return alerts != null && !alerts.isEmpty();
    }

    // whoever sees this verdict's alerts, which follows the trip like alert ownership does
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Long ownerId() {
        if (itinerary == null || itinerary.getUser() == null) {
            return null;
        }
        return itinerary.getUser().getId();
    }

    // advice about this impact makes no sense without it, so it goes too. this is what lets an
    // impact, activity or itinerary be deleted without clearing its recommendations by hand first
    @jakarta.persistence.OneToMany(mappedBy = "impact", cascade = jakarta.persistence.CascadeType.REMOVE,
            fetch = jakarta.persistence.FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.List<csd.tripsense.recommendation.Recommendation> recommendations;

}

