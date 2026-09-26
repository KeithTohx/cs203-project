package csd.tripsense.news;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * REST Controller for managing news articles about transit disruptions.
 * Maps to the /api/news endpoint prefix.
 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsService newsService;

     /**
      * Constructor injection for dependency (no @Autowired on fields).
      *
      * @param newsService The NewsService implementation for fetching and managing news
      */
    public NewsController(NewsService newsService) {
        this.newsService = newsService;
     }

     /**
      * Get all stored news entries from the database.
      *
      * @return List of all news articles
      */
     @GetMapping
    public ResponseEntity<List<News>> getAllNews() {
        List<News> newsList = newsService.getAllNews();
        return ResponseEntity.ok(newsList);
     }

     /**
      * Get a single news article by its ID.
      *
      * @param id The unique identifier of the news article
      * @return The news article if found
      */
     @GetMapping("/{id}")
    public ResponseEntity<News> getNewsById(@PathVariable Long id) {
        try {
            News news = newsService.getNewsById(id);
            return ResponseEntity.ok(news);
         } catch (NewsNotFoundException e) {
             return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
         }
     }

     /**
      * Fetch real-world disruption news from external API, save to database, and return results.
      *
      * @param query Search query keyword (default: "travel disruption delay strike weather")
      * @return List of fetched and saved news articles
      */
     @GetMapping("/fetch")
    public ResponseEntity<List<News>> fetchDisruptionNews(@RequestParam(required = false) String query) {
        List<News> newsList = newsService.fetchAndSaveDisruptionNews(query);

        if (newsList.isEmpty()) {
            return ResponseEntity.noContent().build();
         }

        // Return success status with fetched news
        return ResponseEntity.ok(newsList);
     }

     /**
      * Fetch real-world disruption news from external API and save without returning.
      *
      * @param query Search query keyword (default: "travel disruption delay strike weather")
      */
     @PostMapping("/fetch")
    public ResponseEntity<Void> fetchDisruptionNewsAndSave(@RequestParam(required = false) String query) {
        List<News> newsList = newsService.fetchAndSaveDisruptionNews(query);

        if (newsList.isEmpty()) {
            return ResponseEntity.noContent().build();
         }

        // Return success status
        return ResponseEntity.ok().build();
     }

}
