package com.example.urlshortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

// Configuration class — tells Spring how to serialize keys and values
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Use String serializers so data is human-readable in Redis
        template.setKeySerializer(new StringRedisSerializer()); //all the keys are going to be string
        template.setValueSerializer(new StringRedisSerializer()); // all the values are going to be string as well
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());

        template.afterPropertiesSet();
        return template;
    }

    // If you want to store Java objects (serialize as JSON)
//    @Bean
//    public RedisTemplate<String, Object> objectRedisTemplate(RedisConnectionFactory factory) {
//        RedisTemplate<String, Object> template = new RedisTemplate<>();
//        template.setConnectionFactory(factory);
//        template.setKeySerializer(new StringRedisSerializer());
//        template.setValueSerializer(new GenericJacksonJsonRedisSerializer()); // stores as JSON
//        return template;
//    }
}
