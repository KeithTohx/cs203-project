package csd.tripsense.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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

@ExtendWith(MockitoExtension.class)
class NewsControllerTest {

    @Mock
    NewsService newsService;            // the service is faked, so only controller logic is tested

    @InjectMocks
    NewsController controller;

    @Captor
    ArgumentCaptor<Pageable> pageableCaptor;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // standalone = only this controller, no Spring context, no database
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void givenOneNewsItemAndTotalOf(long total) {
        News n = new News();
        n.setTitle("Typhoon hits Tokyo");
        n.setUrl("https://example.com/1");
        when(newsService.getNewsPage(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(n), PageRequest.of(0, 20), total));
    }

    // TESTS: @RequestParam(defaultValue = "0") int page,  @RequestParam(defaultValue = "20") int size
    //        + body = page content,  + X-Total-Count header = page.getTotalElements()
    @Test
    void getNews_noParams_usesPage0Size20_andReturnsTheTotalCountHeader() throws Exception {
        givenOneNewsItemAndTotalOf(42);

        mockMvc.perform(get("/api/news"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "42"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Typhoon hits Tokyo"));

        verify(newsService).getNewsPage(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    }

    // TESTS: the clamping: page >= 0 (Math.max(0, page)), size between 1 and MAX_PAGE_SIZE = 100
    //        columns: requested page, requested size, expected page, expected size
    @ParameterizedTest
    @CsvSource({
            "3,   50,   3, 50",     // normal values pass through
            "-5,  20,   0, 20",     // negative page  -> 0
            "0,   1000, 0, 100",    // huge size      -> 100
            "0,   0,    0, 1",      // size 0         -> 1
            "0,   -7,   0, 1"       // negative size  -> 1
    })
    void getNews_clampsPageAndSize(int page, int size, int expectedPage, int expectedSize) throws Exception {
        givenOneNewsItemAndTotalOf(1);

        mockMvc.perform(get("/api/news").param("page", String.valueOf(page))
                                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk());

        verify(newsService).getNewsPage(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(expectedPage);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(expectedSize);
    }
}