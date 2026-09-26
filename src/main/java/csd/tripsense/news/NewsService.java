package csd.tripsense.news;

import java.util.List;

/**
 * TLDR: This Java file implements the NewsService interface with CRUD methods for managing news entities and a 
 * fetch-and-save method to retrieve real-world transit disruption news from external APIs and store them in the database.
 * NewsService TLDR
 */


public interface NewsService {

    /**
     * Retrieve all news records from the database.
     * @return List of all News entities
     */
    List<News> getAllNews();

    /**
     * Retrieve a single news record by its ID.
     * @param id The unique identifier of the news article
     * @return The News entity with the given ID
     * @throws NewsNotFoundException If no news found with the given ID
     */
    News getNewsById(Long id);

    /**
     * Fetch real-world external news data about transit disruptions,
     * map it to News entities, save to database, and return for AI disruption checks.
     * @param query Search query keyword for fetching disruption-related news
     * @return List of saved and mapped News entities
     */
    List<News> fetchAndSaveDisruptionNews(String query);
}
