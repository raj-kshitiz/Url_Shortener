package com.example.urlshortener.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.data.redis.core.RedisTemplate;

@DataRedisTest
public class RedisTests {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Test
    void testSendMail() {
        redisTemplate.opsForValue().set("personal:email", "kshitiz@gmail.com");
        Object email = redisTemplate.opsForValue().get("email");
        System.out.println(email);
    }
}
