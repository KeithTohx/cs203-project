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
