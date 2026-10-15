package csd.tripsense.news;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TLDR: This Java file implements NewsService by retrieving transit disruption news
 * from the local database. Fetches pre-loaded news articles for AI disruption analysis.
 */

@Service // Marks this class as a Spring Service component within the service layer, making it eligible for auto-detection and injection.
public class NewsServiceImpl implements NewsService {

    private final NewsRepository newsRepository;

    /**
     * Constructor with dependency injection.
     */
    public NewsServiceImpl(NewsRepository newsRepository) {
        this.newsRepository = newsRepository;
    }



    /**
     * Fetches all news articles from the database.
     */
    @Override
    public List<News> getAllNews() {
        return newsRepository.findAll();
    }

    /**
     * Gets a single news article by ID, or throws NewsNotFoundException if not found.
     */
    @Override
    public News getNewsById(Long id) {
        return newsRepository.findById(id)
                  .orElseThrow(() -> new NewsNotFoundException(id));
    }

    /**
     * Fetches one page of news, newest first.
     */
    @Override
    public Page<News> getNewsPage(Pageable pageable) {
        return newsRepository.findAllByOrderByPublishedAtDesc(pageable);
    }

    /**
     * Inserts only articles whose URL is not already stored. Runs in one transaction,
     * so the whole batch is saved or none of it is.
     */
    @Override
    @Transactional
    public int saveNewArticles(List<News> articles) {
        // 1. Drop articles without a URL and duplicates inside the batch itself.
        Map<String, News> unique = new LinkedHashMap<>();
        for (News n : articles) {
            if (n.getUrl() != null) {
                unique.putIfAbsent(n.getUrl(), n);
            }
        }
        if (unique.isEmpty()) {
            return 0;
        }

        // 2. Drop articles already in the database (one query for the whole batch).
        Set<String> existing = newsRepository.findByUrlIn(unique.keySet()).stream()
                .map(News::getUrl)
                .collect(Collectors.toSet());

        List<News> fresh = unique.values().stream()
                .filter(n -> !existing.contains(n.getUrl()))
                .toList();

        newsRepository.saveAll(fresh);
        return fresh.size();
    }

    @Override
    public Set<String> findExistingUrls(Collection<String> urls) {
        if (urls.isEmpty()) {
            return Set.of();
        }
        return newsRepository.findByUrlIn(urls).stream()
                .map(News::getUrl)
                .collect(Collectors.toSet());
    }

}
