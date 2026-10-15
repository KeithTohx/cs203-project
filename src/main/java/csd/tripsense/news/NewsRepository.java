package csd.tripsense.news;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * TLDR: Spring Data JPA repository for News entity.
 * Provides automatic CRUD operations for news articles stored in the local SQLite database.
 */

public interface NewsRepository extends JpaRepository<News, Long> {

    // Which of these URLs are already stored? One query for a whole batch of articles.
    List<News> findByUrlIn(Collection<String> urls);

    // One page of news, newest first.
    Page<News> findAllByOrderByPublishedAtDesc(Pageable pageable);
}
/*
Spring Data JPA automatically generates implementation methods at runtime, including:
    findAll(): Executes SELECT * FROM news and returns List<News>.
    findById(Long id): Executes SELECT * FROM news WHERE id = ? returning Optional<News>.
    save(News entity): Executes database INSERT or UPDATE.
    deleteById(Long id): Executes database DELETE.
    findByUrlIn(urls): Derived from the method name, SELECT * FROM news WHERE url IN (...).
    findAllByOrderByPublishedAtDesc(pageable): Derived query, newest first, one page at a time.
 */