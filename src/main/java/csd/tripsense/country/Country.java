package csd.tripsense.country;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

import csd.tripsense.itinerary.Itinerary;

@Entity
@Table(name = "country")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Country {
    private @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    private String name;

    // one conutry can have many itineraries, the "mappedBy" attribute references the "country" property in the Itinerary class
    // CascadeType.ALL: to propagate (cascade) all persistence operations to relating entities
    // E.g., remove a country -> all associated itineraries removed
    // orphanRemoval = false: any disconnected entity instances are not removed
    // E.g., country can exist without a refererence from any itineraries
    @OneToMany(mappedBy = "country", cascade = CascadeType.ALL, orphanRemoval = false)
    @JsonIgnore
    // Ignore the field in both JSON serialization and deserialization
    private List<Itinerary> itineraries;

}

