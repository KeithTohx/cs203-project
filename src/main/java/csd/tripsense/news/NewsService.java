package csd.tripsense.news;

import java.util.List;

/**
 * TLDR: News service interface for retrieving transit disruption news from local database.
 * Note: This implementation uses the SQLite database instead of external APIs.
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

}
