package com.example.urlshortener.repository;

import com.example.urlshortener.model.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {
    Optional<Url> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);

    @Modifying
    @Transactional
    @Query("UPDATE Url u SET u.clickCount = u.clickCount + :delta WHERE u.shortCode = :shortCode")
    void addToClickCount(@Param("shortCode") String shortCode, @Param("delta") long delta);
}
