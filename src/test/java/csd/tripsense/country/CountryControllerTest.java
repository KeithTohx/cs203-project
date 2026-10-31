package csd.tripsense.country;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// unit tests for CountryController, which feeds the country dropdown on the itinerary form
@ExtendWith(MockitoExtension.class)
@DisplayName("CountryController Unit Tests")
class CountryControllerTest {

    @Mock
    private CountryService countryService;

    @InjectMocks
    private CountryController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private Country country(Long id, String name) {
        Country country = new Country();
        country.setId(id);
        country.setName(name);
        return country;
    }

    @Test
    @DisplayName("Should list every country")
    void getCountries_ShouldReturnAll() throws Exception {
        // Arrange
        when(countryService.listCountries()).thenReturn(List.of(country(1L, "Japan"), country(2L, "Singapore")));

        // Act + Assert
        mockMvc.perform(get("/api/country"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Japan"))
                .andExpect(jsonPath("$[1].name").value("Singapore"));
    }

    @Test
    @DisplayName("Should return an empty list when there are no countries")
    void getCountries_ShouldReturnEmptyList_WhenNone() throws Exception {
        // Arrange
        when(countryService.listCountries()).thenReturn(List.of());

        // Act + Assert
        mockMvc.perform(get("/api/country"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
