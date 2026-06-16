package com.example.urlshortener.repository;

import com.example.urlshortener.model.ClickEvents;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClickEventsRepository extends MongoRepository<ClickEvents, String> {
    List<ClickEvents> findByShortCode(String shortCode);
    long countByShortCode(String shortCode);
}
