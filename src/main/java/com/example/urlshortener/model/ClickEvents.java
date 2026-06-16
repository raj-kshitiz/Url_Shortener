package com.example.urlshortener.model;

import org.springframework.data.annotation.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "click_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClickEvents {

    @Id
    private String id;

    @Indexed
    private String shortCode;

    private Instant timestamp;

    @Field("ip_address")
    private String ipAddress;

    private String userAgent;
    private String referer;

    private GeoLocation location;

}

// This class becomes an embedded document inside ClickEvent
@Data
@NoArgsConstructor
@AllArgsConstructor
class GeoLocation {
    private String country;
    private String city;
}
