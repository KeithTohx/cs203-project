package csd.tripsense.impact;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import csd.tripsense.news.News;

// unit tests for ImpactResponse.from, which flattens an impact and its news into what the api returns
@DisplayName("ImpactResponse Unit Tests")
class ImpactResponseTest {

    private News testNews;
    private LocalDateTime testPublishedAt;

    @BeforeEach
    void setUp() {
        testPublishedAt = LocalDateTime.of(2026, 9, 21, 14, 0);

        testNews = new News();
        testNews.setId(4L);
        testNews.setTitle("Flooding closes Bangkok tourist areas");
        testNews.setSource("Bangkok Post");
        testNews.setUrl("https://www.bangkokpost.com/");
        testNews.setDescription("Heavy rain has flooded several districts in central Bangkok.");
        testNews.setPublishedAt(testPublishedAt);
    }

    // ===== NEWS DETAILS TESTS =====

    @Test
    @DisplayName("Should copy news details when impact has news")
    void from_ShouldCopyNewsDetails_WhenImpactHasNews() {
        // arrange
        Impact impact = new Impact();
        impact.setNews(testNews);

        // act
        ImpactResponse response = ImpactResponse.from(impact);

        // assert
        // every value is different, so two string fields swapped in the constructor would fail here
        assertNotNull(response);
        assertEquals(4L, response.newsId());
        assertEquals("Flooding closes Bangkok tourist areas", response.newsTitle());
        assertEquals("Bangkok Post", response.newsSource());
        assertEquals("https://www.bangkokpost.com/", response.newsUrl());
        assertEquals("Heavy rain has flooded several districts in central Bangkok.", response.newsDescription());
        assertEquals(testPublishedAt, response.newsPublishedAt());
    }

    @Test
    @DisplayName("Should leave news details null when impact has no news")
    void from_ShouldLeaveNewsDetailsNull_WhenImpactHasNoNews() {
        Impact impact = new Impact();

        ImpactResponse response = ImpactResponse.from(impact);

        assertNotNull(response);
        assertNull(response.newsId());
        assertNull(response.newsTitle());
        assertNull(response.newsSource());
        assertNull(response.newsUrl());
        assertNull(response.newsDescription());
        assertNull(response.newsPublishedAt());
    }

    // ===== REJECTED TESTS =====

    @Test
    @DisplayName("Should report rejected when the user rejected the impact")
    void from_ShouldBeRejected_WhenImpactIsRejected() {
        Impact impact = new Impact();
        impact.setRejected(true);

        ImpactResponse response = ImpactResponse.from(impact);

        assertTrue(response.rejected());
    }

    @Test
    @DisplayName("Should report not rejected when rejected is null")
    void from_ShouldNotBeRejected_WhenRejectedIsNull() {
        // rows saved before the column existed have null, and clients should not have to handle that
        Impact impact = new Impact();

        ImpactResponse response = ImpactResponse.from(impact);

        assertFalse(response.rejected());
    }
}
