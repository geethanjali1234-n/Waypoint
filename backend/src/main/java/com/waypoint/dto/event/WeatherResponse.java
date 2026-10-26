package com.waypoint.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherResponse {

    private String city;

    private String country;

    private String weather;

    private String description;

    private Double temperature;

    private Double feelsLike;

    private Integer humidity;

    private Double windSpeed;

    private Double rainChance;

    private String forecastTime;
}
