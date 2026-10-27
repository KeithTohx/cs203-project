package csd.tripsense.country;

import java.util.List;
import java.util.Collection;

public interface CountryService {
    List<Country> listCountries();

    /**
     * Countries whose GDELT (FIPS) code is in the given set.
     * @param fipsCodes GDELT country codes, e.g. "JA"
     * @return the matching countries; codes with no Country row are simply absent
     */
    List<Country> getCountriesByFipsCodes(Collection<String> fipsCodes);
}
