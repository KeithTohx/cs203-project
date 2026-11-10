package csd.tripsense.activity;

import java.time.LocalDateTime;
import java.util.List;

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
    }

    // Null on older rows means revision zero; identity still identifies the row's lifetime.
    @JsonIgnore
    @Setter(lombok.AccessLevel.NONE)
    private Long assessmentRevision;

    public long currentAssessmentRevision() { return assessmentRevision == null ? 0 : assessmentRevision; }
    public void reviseAssessment() { assessmentRevision = currentAssessmentRevision() + 1; }

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
