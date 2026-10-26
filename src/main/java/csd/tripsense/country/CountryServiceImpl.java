package csd.tripsense.country;

import java.util.List;
import java.util.Collection;

import org.springframework.stereotype.Service;

@Service
public class CountryServiceImpl implements CountryService {
    public CountryRepository countries;

    public CountryServiceImpl(CountryRepository countries) {
        this.countries = countries;
    }

    @Override
    public List<Country> listCountries() {
        return countries.findAll();
    }

    @Override
    public List<Country> getCountriesByFipsCodes(Collection<String> fipsCodes) {
        if (fipsCodes.isEmpty()) {
            return List.of();
        }
        return countries.findByFipsCodeIn(fipsCodes);
    }
}
