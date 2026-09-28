package csd.tripsense.news;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * TLDR: Spring Data JPA repository for News entity.
 * Provides automatic CRUD operations for news articles stored in the local SQLite database.
 */

public interface NewsRepository extends JpaRepository<News, Long> {
}
/*
Spring Data JPA automatically generates implementation methods at runtime, including:
    findAll(): Executes SELECT * FROM news and returns List<News>.
    findById(Long id): Executes SELECT * FROM news WHERE id = ? returning Optional<News>.
    save(News entity): Executes database INSERT or UPDATE.
    deleteById(Long id): Executes database DELETE.
 */