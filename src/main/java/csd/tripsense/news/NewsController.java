package csd.tripsense.news;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * REST Controller for managing news articles about transit disruptions.
 * Maps to the /api/news endpoint prefix.
 * Note: Uses local SQLite database instead of external API calls.
 */
@RestController //Marks this class as a Web REST Controller, meaning returned object instances will automatically be serialized to JSON/XML in the HTTP response body.
@RequestMapping("/api/news")
public class NewsController {

    private final NewsService newsService;

     /**
      * Constructor injection for dependency.
      *
      * @param newsService The NewsService implementation for managing news
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

}
