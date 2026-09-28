package csd.tripsense.country;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CountryController {
    private CountryService countryService;

    public CountryController(CountryService countryService) {
        this.countryService = countryService;
    }

    /**
     * List all countries in the system
     *
     * @return list of all countries
     */
    @GetMapping("/country")
    public List<Country> getCountries(){
        return countryService.listCountries();
    }
}
