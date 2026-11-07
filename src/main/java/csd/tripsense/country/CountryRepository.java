package csd.tripsense.country;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CountryRepository extends JpaRepository<Country, Long> {

    // alphabetical, so the country picker reads like a list rather than in id order
    List<Country> findAllByOrderByNameAsc();

    List<Country> findByFipsCodeIn(Collection<String> fipsCodes);
}