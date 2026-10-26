package csd.tripsense.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

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

@ExtendWith(MockitoExtension.class)
class NewsServiceImplTest {

    @Mock
    NewsRepository repository;          // the database is faked

    @InjectMocks
    NewsServiceImpl service;            // the real class under test, with the fake repository injected

    @Captor
    ArgumentCaptor<Iterable<News>> savedCaptor;

    private static News news(String url) {
        News n = new News();
        n.setUrl(url);
        n.setTitle("Title for " + url);
        return n;
    }

    // ======================================================================
    // saveNewArticles(List<News>)
    // ======================================================================

    // TESTS: if (articles.isEmpty()) return 0;     (the early exit)
    @Test
    void saveNewArticles_emptyList_returnsZeroAndTouchesNothing() {
        assertThat(service.saveNewArticles(List.of())).isZero();

        verifyNoInteractions(repository);
    }

    // TESTS: repository.findByUrlIn(...) -> filter out URLs already stored -> repository.saveAll(...)
    @Test
    void saveNewArticles_savesOnlyUrlsThatAreNotInTheDatabaseYet() {
        when(repository.findByUrlIn(anyCollection())).thenReturn(List.of(news("https://a")));
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int saved = service.saveNewArticles(List.of(news("https://a"), news("https://b")));

        assertThat(saved).isEqualTo(1);
        verify(repository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).extracting(News::getUrl).containsExactly("https://b");
    }

    // TESTS: the LinkedHashMap.putIfAbsent(...) de-dupe inside one batch, and the null-URL skip
    @Test
    void saveNewArticles_dedupesWithinTheBatch_andSkipsNullUrls() {
        when(repository.findByUrlIn(anyCollection())).thenReturn(List.of());
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int saved = service.saveNewArticles(Arrays.asList(
                news("https://a"), news("https://a"), news(null), news("https://b")));

        assertThat(saved).isEqualTo(2);
        verify(repository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).extracting(News::getUrl)
                .containsExactly("https://a", "https://b");     // first wins, order kept
    }

    // ======================================================================
    // findExistingUrls(Collection<String>)
    // ======================================================================

    // TESTS: if (urls.isEmpty()) return Set.of();   (no database query for an empty input)
    @Test
    void findExistingUrls_emptyInput_returnsEmptySetWithoutQuerying() {
        assertThat(service.findExistingUrls(List.of())).isEmpty();

        verifyNoInteractions(repository);
    }

    // TESTS: repository.findByUrlIn(urls) mapped to a Set of URL strings
    @Test
    void findExistingUrls_returnsTheUrlsFoundInTheDatabase() {
        when(repository.findByUrlIn(anyCollection())).thenReturn(List.of(news("https://a")));

        Set<String> existing = service.findExistingUrls(List.of("https://a", "https://b"));

        assertThat(existing).containsExactly("https://a");
    }

    // ======================================================================
    // getNewsPage(Pageable)
    // ======================================================================

    // TESTS: return repository.findAllByOrderByPublishedAtDesc(pageable);
    @Test
    void getNewsPage_delegatesToTheRepositoryWithTheSamePageable() {
        Pageable pageable = PageRequest.of(2, 50);
        Page<News> page = new PageImpl<>(List.of(news("https://a")), pageable, 101);
        when(repository.findAllByOrderByPublishedAtDesc(pageable)).thenReturn(page);

        assertThat(service.getNewsPage(pageable)).isSameAs(page);
    }
}