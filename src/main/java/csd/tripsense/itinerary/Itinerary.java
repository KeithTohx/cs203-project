package csd.tripsense.itinerary;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import csd.tripsense.activity.Activity;
import csd.tripsense.country.Country;
import csd.tripsense.impact.Impact;
import csd.tripsense.user.User;

import jakarta.persistence.*;
import lombok.*;

@Entity 
@Getter 
@Setter 
public class Itinerary {

    private @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    // Identifies this row's lifetime even when SQLite reuses its numeric id.
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @Column(updatable = false)
    private String assessmentIdentity = java.util.UUID.randomUUID().toString();

    @PrePersist
    private void initializeAssessmentIdentity() {
        assessmentIdentity = java.util.UUID.randomUUID().toString();
        assignIdentityKey();
    }

    // goes up by one each time something the model is shown about the trip changes (its name,
    // country or dates). every verdict records the revision it was judged on, which is how a
    // verdict about the old details is told apart from one about the new ones.
    // null on rows made before this existed, which reads as revision 0.
    // a Long and not an Integer: the sqlite dialect drops the type from the id column when a
    // table has another integer column, and then sqlite stops generating ids
    @Column(name = "revision")
    @JsonIgnore
    private Long revision;

    public long currentRevision() {
        if (revision == null) {
            return 0;
        }
        return revision;
    }

    public void bumpRevision() {
        revision = currentRevision() + 1;
    }

    // random, set once when the trip is first saved and never changed. sqlite hands a deleted
    // row's id to the next insert, and a new trip starts back at revision 0, so the id and revision
    // together cannot tell a trip from the one that replaced it. this can.
    // rows made before this existed are filled in by data.sql on startup
    @Column(name = "identity_key", length = 36, updatable = false)
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    private String identityKey;

    // always a fresh key, even if the request body sent one, so no two trips can ever share it
    void assignIdentityKey() {
        identityKey = UUID.randomUUID().toString();
    }

    public long currentAssessmentRevision() { return currentRevision(); }
    public void reviseAssessment() { bumpRevision(); }

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "date_start")
    private LocalDateTime dateStart;

    @Column(name = "date_end")
    private LocalDateTime dateEnd;

    // These relationships only clean up alert-delivery metadata when a trip is deleted.
    @OneToMany(mappedBy="itinerary",cascade=CascadeType.REMOVE)
    @JsonIgnore
    private List<csd.tripsense.alert.AlertEmailGroup> alertEmailGroups;
    @OneToMany(mappedBy="itinerary",cascade=CascadeType.REMOVE)
    @JsonIgnore
    private List<csd.tripsense.impact.EvaluationBatch> alertEvaluationBatches;
    @OneToMany(mappedBy="itinerary",cascade=CascadeType.REMOVE)
    @JsonIgnore
    private List<csd.tripsense.impact.EvaluationTarget> alertEvaluationTargets;
    // when an admin last changed this trip or its activities, for the note the traveller sees.
    // ignored in request bodies, since travellers post this entity directly and could set it
    @Column(name = "admin_edited_at")
    @JsonIgnore
    private Instant adminEditedAt;

    // the foreign keys live on activity and impact (mappedBy), these just let jpa find them.
    // deleting an itinerary deletes its activities and impacts too, instead of leaving rows
    // that point at an itinerary that no longer exists
    @OneToMany(mappedBy = "itinerary", cascade = CascadeType.REMOVE)
    @JsonIgnore
    private List<Activity> activities;

    @OneToMany(mappedBy = "itinerary", cascade = CascadeType.REMOVE)
    @JsonIgnore
    private List<Impact> impacts;
}
