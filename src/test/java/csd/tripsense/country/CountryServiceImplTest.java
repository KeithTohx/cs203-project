package csd.tripsense.country;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CountryService Unit Tests")
class CountryServiceImplTest {

    @Mock private CountryRepository countryRepository;

    @InjectMocks
    private CountryServiceImpl countryService;

    @Test
    @DisplayName("Should return the countries matching the given FIPS codes")
    void getCountriesByFipsCodes_ShouldReturnMatches() {
        Country japan = new Country();
        japan.setFipsCode("JA");
        when(countryRepository.findByFipsCodeIn(Set.of("JA", "XX"))).thenReturn(List.of(japan));

        assertEquals(List.of(japan), countryService.getCountriesByFipsCodes(Set.of("JA", "XX")));
    }

    @Test
    @DisplayName("Should not query the database when no codes are given")
    void getCountriesByFipsCodes_ShouldReturnEmptyWithoutQuery_WhenNoCodes() {
        assertTrue(countryService.getCountriesByFipsCodes(Set.of()).isEmpty());

        verifyNoInteractions(countryRepository);
    }
}