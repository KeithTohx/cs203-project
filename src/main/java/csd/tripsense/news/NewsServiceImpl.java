package csd.tripsense.news;

import java.util.List;

import org.springframework.stereotype.Service;

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

}
