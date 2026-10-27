package csd.tripsense.country;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CountryRepository extends JpaRepository<Country, Long> {

    List<Country> findByFipsCodeIn(Collection<String> fipsCodes);
}