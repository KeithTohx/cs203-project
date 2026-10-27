package csd.tripsense.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for NewsServiceImpl
 * The repository (database) is mocked
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NewsService Unit Tests")
class NewsServiceImplTest {

    @Mock
    private NewsRepository repository;

    @InjectMocks
    private NewsServiceImpl service;

    @Captor
    private ArgumentCaptor<Iterable<News>> savedCaptor;

    private static News news(String url) {
        News n = new News();
        n.setUrl(url);
        n.setTitle("Title for " + url);
        return n;
    }

    private static List<String> urlsOf(Iterable<News> items) {
        List<String> urls = new java.util.ArrayList<>();
        items.forEach(n -> urls.add(n.getUrl()));
        return urls;
    }

    // ===== saveNewArticles(List<News>) =====

    // TESTS: if (articles.isEmpty()) return 0;     (the early exit)
    @Test
    @DisplayName("Should return zero and touch nothing when the list is empty")
    void saveNewArticles_ShouldReturnZeroAndTouchNothing_WhenListEmpty() {
        assertEquals(0, service.saveNewArticles(List.of()));

        verifyNoInteractions(repository);
    }

    // TESTS: repository.findByUrlIn(...) -> filter out URLs already stored -> repository.saveAll(...)
    @Test
    @DisplayName("Should save only URLs that are not in the database yet")
    void saveNewArticles_ShouldSaveOnlyUrlsNotInDatabase() {
        when(repository.findByUrlIn(anyCollection())).thenReturn(List.of(news("https://a")));
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int saved = service.saveNewArticles(List.of(news("https://a"), news("https://b")));

        assertEquals(1, saved);
        verify(repository, times(1)).saveAll(savedCaptor.capture());
        assertEquals(List.of("https://b"), urlsOf(savedCaptor.getValue()));
    }

    // TESTS: the LinkedHashMap.putIfAbsent(...) de-dupe inside one batch, and the null-URL skip
    @Test
    @DisplayName("Should de-duplicate within the batch and skip null URLs")
    void saveNewArticles_ShouldDedupeWithinBatch_AndSkipNullUrls() {
        when(repository.findByUrlIn(anyCollection())).thenReturn(List.of());
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int saved = service.saveNewArticles(Arrays.asList(
                news("https://a"), news("https://a"), news(null), news("https://b")));

        assertEquals(2, saved);
        verify(repository, times(1)).saveAll(savedCaptor.capture());
        assertEquals(List.of("https://a", "https://b"), urlsOf(savedCaptor.getValue()));   // first wins, order kept
    }

    // ===== findExistingUrls(Collection<String>) =====

    // TESTS: if (urls.isEmpty()) return Set.of();   (no database query for an empty input)
    @Test
    @DisplayName("Should return an empty set without querying when the input is empty")
    void findExistingUrls_ShouldReturnEmptySetWithoutQuerying_WhenInputEmpty() {
        Set<String> existing = service.findExistingUrls(List.of());

        assertNotNull(existing);
        assertTrue(existing.isEmpty());
        verifyNoInteractions(repository);
    }

    // TESTS: repository.findByUrlIn(urls) mapped to a Set of URL strings
    @Test
    @DisplayName("Should return the URLs found in the database")
    void findExistingUrls_ShouldReturnUrlsFoundInDatabase() {
        when(repository.findByUrlIn(anyCollection())).thenReturn(List.of(news("https://a")));

        Set<String> existing = service.findExistingUrls(List.of("https://a", "https://b"));

        assertEquals(Set.of("https://a"), existing);
    }

    // ===== getNewsPage(Pageable) =====

    // TESTS: return repository.findAllByOrderByPublishedAtDesc(pageable);
    @Test
    @DisplayName("Should delegate to the repository with the same pageable")
    void getNewsPage_ShouldDelegateToRepositoryWithSamePageable() {
        Pageable pageable = PageRequest.of(2, 50);
        Page<News> page = new PageImpl<>(List.of(news("https://a")), pageable, 101);
        when(repository.findAllByOrderByPublishedAtDesc(pageable)).thenReturn(page);

        assertSame(page, service.getNewsPage(pageable));
        verify(repository, times(1)).findAllByOrderByPublishedAtDesc(pageable);
    }

    // ===== getNewsByCountrySince(Long, LocalDateTime) =====

    // TESTS: return newsRepository.findByCountryIdSince(countryId, since);
    @Test
    @DisplayName("Should delegate to the repository with the same arguments")
    void getNewsByCountrySince_ShouldDelegateToRepositoryWithSameArguments() {
        LocalDateTime since = LocalDateTime.of(2026, 10, 22, 0, 0);
        News n = news("https://a");
        when(repository.findByCountryIdSince(1L, since)).thenReturn(List.of(n));

        List<News> result = service.getNewsByCountrySince(1L, since);

        assertEquals(List.of(n), result);
        verify(repository, times(1)).findByCountryIdSince(1L, since);
    }

    // TESTS: no news for the country in the window -> an empty list, not an exception
    @Test
    @DisplayName("Should return an empty list, not throw, when there is no news")
    void getNewsByCountrySince_ShouldReturnEmptyList_WhenNoNews() {
        LocalDateTime since = LocalDateTime.of(2026, 10, 22, 0, 0);
        when(repository.findByCountryIdSince(1L, since)).thenReturn(List.of());

        List<News> result = service.getNewsByCountrySince(1L, since);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
