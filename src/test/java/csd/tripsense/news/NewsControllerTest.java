package csd.tripsense.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for NewsController
 * The service is mocked and MockMvc is set up in standalone mode,
 * so only the controller logic (paging defaults, clamping, headers) is tested.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NewsController Unit Tests")
class NewsControllerTest {

    @Mock
    private NewsService newsService;

    @InjectMocks
    private NewsController controller;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // standalone = only this controller, no Spring context, no database
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ---------------------------------------------------------------- helpers

    private void givenOneNewsItemAndTotalOf(long total) {
        News n = new News();
        n.setTitle("Typhoon hits Tokyo");
        n.setUrl("https://example.com/1");
        when(newsService.getNewsPage(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(n), PageRequest.of(0, 20), total));
    }

    // ===== GET NEWS TESTS =====

    @Test
    @DisplayName("Should use page 0 and size 20 and return the total count header when no params are given")
    void getNews_ShouldUseDefaultsAndReturnTotalCountHeader_WhenNoParams() throws Exception {
        // Arrange
        givenOneNewsItemAndTotalOf(42);

        // Act + Assert (HTTP response)
        mockMvc.perform(get("/api/news"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "42"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Typhoon hits Tokyo"));

        // Assert (Pageable handed to the service)
        verify(newsService, times(1)).getNewsPage(pageableCaptor.capture());
        Pageable actualPageable = pageableCaptor.getValue();
        assertNotNull(actualPageable);
        assertEquals(0, actualPageable.getPageNumber());
        assertEquals(20, actualPageable.getPageSize());
    }

    // columns: requested page, requested size, expected page, expected size
    @ParameterizedTest(name = "page={0}, size={1} -> page={2}, size={3}")
    @DisplayName("Should clamp page to >= 0 and size to between 1 and 100")
    @CsvSource({
            "3,   50,   3, 50",     // normal values pass through
            "-5,  20,   0, 20",     // negative page  -> 0
            "0,   1000, 0, 100",    // huge size      -> 100
            "0,   0,    0, 1",      // size 0         -> 1
            "0,   -7,   0, 1"       // negative size  -> 1
    })
    void getNews_ShouldClampPageAndSize(int page, int size, int expectedPage, int expectedSize) throws Exception {
        // Arrange
        givenOneNewsItemAndTotalOf(1);

        // Act
        mockMvc.perform(get("/api/news")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk());

        // Assert
        verify(newsService, times(1)).getNewsPage(pageableCaptor.capture());
        Pageable actualPageable = pageableCaptor.getValue();
        assertNotNull(actualPageable);
        assertEquals(expectedPage, actualPageable.getPageNumber());
        assertEquals(expectedSize, actualPageable.getPageSize());
    }
}
