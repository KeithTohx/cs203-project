package csd.tripsense.country;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;

    // one conutry can have many itineraries, the "mappedBy" attribute references the "country" property in the Itinerary class
    // CascadeType.ALL: to propagate (cascade) all persistence operations to relating entities
    // E.g., remove a country -> all associated itineraries removed
    // orphanRemoval = false: any disconnected entity instances are not removed
    // E.g., country can exist without a refererence from any itineraries
    @OneToMany(mappedBy = "country", cascade = CascadeType.ALL, orphanRemoval = false)
    @JsonIgnore
    // Ignore the field in both JSON serialization and deserialization
    private List<Itinerary> itineraries;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.modifiedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.modifiedAt = LocalDateTime.now();
    }
}

