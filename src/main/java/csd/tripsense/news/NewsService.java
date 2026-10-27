package csd.tripsense.news;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * TLDR: News service interface for retrieving and storing transit disruption news.
 * News is read from the local SQLite database and is also written to it by the
 * scheduled GDELT ingestion job (see csd.tripsense.gdelt).
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
      * Retrieve one page of news, newest first.
      * @param pageable Page number, page size and (optionally) sort
      * @return A page of News entities ordered by publishedAt descending
      */
    Page<News> getNewsPage(Pageable pageable);

     /**
      * Save only the articles not already stored, matched by URL.
      * @param articles Candidate articles, e.g. freshly fetched from GDELT
      * @return The number of articles actually inserted
      */
    int saveNewArticles(List<News> articles);

    /**
     * Which of these URLs are already stored?
     */
    Set<String> findExistingUrls(Collection<String> urls);

        /**
       * News about a country published at or after a cut-off.
       * @param countryId the unique identifier of the Country
       * @param since earliest publishedAt to include
       * @return matching news, newest first (empty if none)
       */
      List<News> getNewsByCountrySince(Long countryId, LocalDateTime since);

}
