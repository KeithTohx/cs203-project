package csd.tripsense.news;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
/**
 * TLDR: This Java file implements NewsService by fetching real-world transit 
 * disruption news from the GDelt Project API, parsing JSON responses, 
 * converting them to News entities, and persisting them in the database via repository methods.
 * Uses RestTemplate to fetch news articles and process them for AI disruption analysis.
 * 
 * How It All Fits Together (Data Flow)
    Controller calls Service →
    fetchAndSaveDisruptionNews() →
        buildApiUrl(query) + parseApiResponse() + convertToNews() →
        newsRepository.save() → DB persists articles → Returns List<News>
*/


@Service
public class NewsServiceImpl implements NewsService {

    private final NewsRepository newsRepository;
    private String apiBaseUrl = "http://api.gdeltproject.org/api/v2/doc/doc";
    private final RestTemplate restTemplate;

     // Default query for travel disruption events
    public static final String DEFAULT_QUERY = "travel disruption delay strike weather";

       /**
        * Constructor with dependency injection.
        */
      public NewsServiceImpl(NewsRepository newsRepository) {
        this.newsRepository = newsRepository;
        this.restTemplate = new RestTemplate();
      }

      /**
       * Optional property for GDelt API endpoint (defaults to free keyless endpoint).
       */
     @Value("${news.api.url:http://api.gdeltproject.org/api/v2/doc/doc}")
    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
      }

     /**
      * Fetch real-world disruption news from external API and save to DB.
      */
    @Override
    public List<News> fetchAndSaveDisruptionNews(String query) {
            // Use default query if query is null or empty
        if (query == null || query.trim().isEmpty()) {
            query = DEFAULT_QUERY;
        }

        try {
            String apiUrl = buildApiUrl(query);
            List<Map<String, Object>> articles = parseApiResponse(apiUrl);

            List<News> savedNews = new ArrayList<>();
            for (Map<String, Object> article : articles) {
                News news = convertToNews(article);
                if (news != null) {
                    savedNews.add(newsRepository.save(news));
                }
            }
            return savedNews;

        } catch (Exception e) {
            // Handle external API errors gracefully - don't crash the application
            System.err.println("Warning: Could not fetch news from external API. Error: " + e.getMessage());
            System.err.println("Returning empty list due to API error.");
            return new ArrayList<>();
        }
    }

     /**
      * Builds the complete API URL with query parameters.
      */
    private String buildApiUrl(String query) {
        String baseUrl = apiBaseUrl != null && !apiBaseUrl.trim().isEmpty() ? apiBaseUrl.trim() : "http://api.gdeltproject.org/api/v2/doc/doc";

        // Encode the query string for URL safety
        try {
            String encodedQuery = URLEncoder.encode(query, "UTF-8");

            // GDelt API uses pipe-separated key=value format via URL parameter
            if (!baseUrl.contains("?")) {
                baseUrl += "/?format=json&q=" + encodedQuery;
            } else {
                baseUrl += "&q=" + encodedQuery;
            }

            return baseUrl;
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is a supported encoding, this shouldn't happen
            throw new RuntimeException("Failed to encode query string", e);
        }
    }

     /**
      * Parses the API response into a list of news articles.
      */
    private List<Map<String, Object>> parseApiResponse(String apiUrl) {
        List<Map<String, Object>> articles = new ArrayList<>();

        try {
            String jsonContent = restTemplate.getForObject(apiUrl, String.class);

            if (jsonContent == null || jsonContent.trim().isEmpty()) {
                System.err.println("Warning: Empty API response");
                return articles;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonContent);
            JsonNode articlesNode = root.path("articles");

            if (!articlesNode.isArray()) {
                System.err.println("Warning: Unexpected API response shape (no 'articles' array)");
                return articles;
            }

            for (JsonNode a : articlesNode) {
                try {
                    String title = a.path("title").asText("Unknown News");
                    String url = a.path("url").asText(null);
                    String domain = a.path("domain").asText("GDelt Project API");
                    String seenDate = a.path("seendate").asText(null);

                    Map<String, Object> article = new HashMap<>();
                    article.put("title", title);
                    article.put("description", "News article about transit disruption.");
                    article.put("source", domain);
                    article.put("url", url != null ? url : getArticleUrl(null));
                    article.put("publishedAt", LocalDateTime.now().toString());

                    articles.add(article);
                } catch (Exception ex) {
                    // Skip malformed entries
                }
            }

        } catch (Exception e) {
            System.err.println("Error fetching external news API: " + e.getMessage());
        }

        return articles;
    }

     /**
      * Gets the article URL from document ID.
      */
    private String getArticleUrl(String docId) {
        if (docId == null || docId.isEmpty()) {
            return "https://www.gdeltproject.org";
        }
        return "https://www.gdeltproject.org/api/v2/doc/doc/" + docId;
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
      * Converts a parsed article map to a News entity.
      */
    private News convertToNews(Map<String, Object> article) {
        if (article == null) return null;

        News news = new News();

        String title = (String) article.get("title");
        String description = (String) article.get("description");
        String source = (String) article.get("source");
        String url = (String) article.get("url");
        String publishedAt = (String) article.get("publishedAt");

        news.setTitle(title);
        news.setDescription(description);
        news.setSource(source);
        news.setUrl(url);

        if (publishedAt != null && !publishedAt.isEmpty()) {
            try {
                news.setPublishedAt(LocalDateTime.parse(publishedAt));
            } catch (Exception e) {
                news.setPublishedAt(null);
            }
        } else {
            news.setPublishedAt(LocalDateTime.now());
        }

        return news;
    }

}
