package csd.tripsense.recommendation;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityOptionRepository extends JpaRepository<ActivityOption, Long> {

    // the whole country, not filtered to substitute categories. the rules need every area to
    // work out roughly where the activity is before they can rank anything by distance
    List<ActivityOption> findByCountry(String country);
}
