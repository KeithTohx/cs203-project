package csd.tripsense.recommendation;

import csd.tripsense.activity.ActivityCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Somewhere a traveller could go instead when a planned activity is disrupted.
 *
 * These are real places pulled from Wikidata by tools/build-options.py and seeded from
 * options.sql. The model is never asked to invent a venue, it only picks from this table,
 * which is what stops it suggesting an address that does not exist.
 *
 * Place data from Wikidata, CC0.
 */
@Entity
@Table(name = "activity_option",
       // every lookup is by country, and the table is thousands of rows
       indexes = @Index(name = "idx_activity_option_country", columnList = "country"))
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ActivityOption {

    // the wikidata q number, so regenerating the seed does not create duplicates
    private @Id Long id;

    @Column(nullable = false)
    private String country;

    // a word that appears in activity addresses, eg "Sapporo", so we can match on it
    @Column(nullable = false)
    private String area;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityCategory category;

    @Column(nullable = false)
    private String name;

    @Column
    private String address;

    // nullable, the first 180 rows were generated before we kept the coordinates. the rules
    // fall back to matching the area name against the address when these are missing
    @Column
    private Double latitude;

    @Column
    private Double longitude;
}
