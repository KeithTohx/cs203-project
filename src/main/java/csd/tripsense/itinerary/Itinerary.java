package csd.tripsense.itinerary;

import java.time.LocalDateTime;

import csd.tripsense.country.Country;
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

    
}
