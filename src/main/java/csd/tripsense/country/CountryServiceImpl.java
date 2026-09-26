package csd.tripsense.country;

import java.util.List;

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
}
