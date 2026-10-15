package csd.tripsense.news;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for managing news articles about transit disruptions.
 * Maps to the /api/news endpoint prefix.
 * News is stored in the local SQLite database and is kept up to date by the
 * scheduled GDELT ingestion job.
 */
@RestController
@RequestMapping("/api")
public class NewsController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    /**
     * Get one page of stored news, newest first.
     *
     * @param page zero-based page number (default 0)
     * @param size items per page (default 20, capped at 100)
     * @return the page's articles; total count is in the X-Total-Count header
     */
    @GetMapping("/news")
    public ResponseEntity<List<News>> getAllNews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<News> result = newsService.getNewsPage(PageRequest.of(safePage, safeSize));
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.getTotalElements()))
                .body(result.getContent());
    }

    /**
     * Get a single news article by its ID.
     */
    @GetMapping("/news/{id}")
    public ResponseEntity<News> getNewsById(@PathVariable Long id) {
        try {
            News news = newsService.getNewsById(id);
            return ResponseEntity.ok(news);
        } catch (NewsNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

}