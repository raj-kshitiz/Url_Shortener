package com.example.urlshortener.service;

import com.example.urlshortener.repository.UrlRepository;
import org.springframework.stereotype.Service;

@Service
public class UrlService {
    UrlRepository urlRepository;
    public UrlService(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }


}
