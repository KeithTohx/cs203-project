package csd.tripsense.itinerary;

import java.time.LocalDateTime;
import java.util.List;

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
