package csd.tripsense.news;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

import csd.tripsense.country.Country;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * TLDR: This Java file defines a JPA entity class representing news articles about 
 * transit disruptions, with fields for title, description, source, URL, and publish timestamp.
 */



@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class News {

    @Id

    //Configures primary key auto-incrementing handled directly by the underlying database auto-increment feature.
    @GeneratedValue(strategy = GenerationType.IDENTITY) 
    
    private Long id;

    private String title;

    //Overrides default database column configuration, allowing description to store up to 2,000 characters instead 
    // of the standard default (usually 255).
    @Column(length = 2000)

    private String description;

    private String source;

    @Column(length = 2048, unique = true)
    private String url;

    private LocalDateTime publishedAt;

    // The countries this article is about. @JsonIgnore because NewsController returns News
    // directly and open-in-view is off, so serialising a lazy set would throw.
    @ManyToMany
    @JoinTable(name = "news_country",
            joinColumns = @JoinColumn(name = "news_id"),
            inverseJoinColumns = @JoinColumn(name = "country_id"))
    @JsonIgnore
    private Set<Country> countries = new HashSet<>();
}