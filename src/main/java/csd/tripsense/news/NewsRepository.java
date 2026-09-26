package csd.tripsense.news;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * TLDR: This file defines a Spring Data JPA repository interface that extends JpaRepository<News, Long> 
 * to provide automatic CRUD database operations for the News entity at runtime.
 */


public interface NewsRepository extends JpaRepository<News, Long> {
}
/*
Methods given:
CRUD Operations
┌──────────────────────────────────────────────────────┬──────────────────────────────────┬──────────────────────────────────┐
│                        Method                        │           Description            │          Example Usage           │
├──────────────────────────────────────────────────────┼──────────────────────────────────┼──────────────────────────────────┤
│ <T> T save(T entity)                                 │ Save entity (creates or updates) │ newsRepository.save(news)        │
├──────────────────────────────────────────────────────┼──────────────────────────────────┼──────────────────────────────────┤
│ <ID> T findById(ID id)                               │ Find by primary key              │ newsRepository.findById(1L)      │
├──────────────────────────────────────────────────────┼──────────────────────────────────┼──────────────────────────────────┤
│ <ID> Optional<T> findOneById(ID id)                  │ Find by ID (returns Optional)    │ newsRepository.findById(1L)      │
├──────────────────────────────────────────────────────┼──────────────────────────────────┼──────────────────────────────────┤
│ <ID> List<T> findAll()                               │ Get all entities                 │ newsRepository.findAll()         │
├──────────────────────────────────────────────────────┼──────────────────────────────────┼──────────────────────────────────┤
│ boolean existsById(ID id)                            │ Check if exists                  │ newsRepository.existsById(1L)    │
├──────────────────────────────────────────────────────┼──────────────────────────────────┼──────────────────────────────────┤
│ long count()                                         │ Total count                      │ newsRepository.count()           │
├──────────────────────────────────────────────────────┼──────────────────────────────────┼──────────────────────────────────┤
│ <ID> void deleteById(ID id)                          │ Delete by ID                     │ newsRepository.deleteById(1L)    │
├──────────────────────────────────────────────────────┼──────────────────────────────────┼──────────────────────────────────┤
│ <S extends T> List<S> saveAllIterable(S... entities) │ Bulk save                        │ newsRepository.saveAll(newsList) │
└──────────────────────────────────────────────────────┴──────────────────────────────────┴──────────────────────────────────┘
 */