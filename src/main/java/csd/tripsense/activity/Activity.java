package csd.tripsense.activity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import csd.tripsense.impact.Impact;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.country.Country;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.AccessLevel;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

@Entity 
@Getter 
@Setter 
public class Activity {
    
    private @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @JsonIgnore
    @Setter(lombok.AccessLevel.NONE)
    @Column(updatable = false)
    private String assessmentIdentity = java.util.UUID.randomUUID().toString();

    @jakarta.persistence.PrePersist
    private void initializeAssessmentIdentity() {
        assessmentIdentity = java.util.UUID.randomUUID().toString();
        assignIdentityKey();
    }

    // goes up by one each time something an activity verdict depends on changes: its name,
    // address or start, the trip it belongs to, or that trip's country. see Itinerary.revision.
    // null on rows made before this existed, which reads as revision 0. a Long for the same
    // reason as Itinerary.revision
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

    // see Itinerary.identityKey, an activity id is handed on the same way
    @Column(name = "identity_key", length = 36, updatable = false)
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    private String identityKey;

    void assignIdentityKey() {
        identityKey = UUID.randomUUID().toString();
    }
    public long currentAssessmentRevision() { return currentRevision(); }
    public void reviseAssessment() { bumpRevision(); }

    @ManyToOne
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address")
    private String address;

    @Column(name = "date_start")
    private LocalDateTime dateStart;

    @Column(name = "date_end")
    private LocalDateTime dateEnd;

    // nullable on purpose, activities created before this existed still work, the
    // recommendation rules just skip them
    @Enumerated(EnumType.STRING)
    @Column(name = "category")
    private ActivityCategory category;

    // deleting an activity deletes the impacts recorded against it (the foreign key is on impact)
    @OneToMany(mappedBy = "activity", cascade = CascadeType.REMOVE)
    @JsonIgnore
    private List<Impact> impacts;

    // Which country this activity is in. Copied from the itinerary by ActivityService, never
    // taken from the request. Lets the news evaluator find activities by country in one query.
    @ManyToOne
    @JoinColumn(name = "country_id")
    private Country country;
}
