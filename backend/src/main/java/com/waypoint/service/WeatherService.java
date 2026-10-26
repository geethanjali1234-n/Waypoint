package com.waypoint.service;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.waypoint.dto.event.WeatherResponse;
import com.waypoint.exception.BusinessException;

import tools.jackson.databind.JsonNode;

@Service
public class WeatherService {

    private static final DateTimeFormatter OPENWEATHER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final long MAX_FORECAST_DIFFERENCE_MINUTES = 180;
    private static final String WEATHER_DISABLED_MESSAGE =
            "Weather forecasts are disabled. Configure OPENWEATHER_API_KEY to enable them.";

    private final RestClient restClient;
    private final String apiKey;
    private final String geocodingUrl;
    private final String forecastUrl;

    public WeatherService(
            RestClient.Builder restClientBuilder,
            @Value("${openweather.api-key}") String apiKey,
            @Value("${openweather.geocoding-url}") String geocodingUrl,
            @Value("${openweather.forecast-url}") String forecastUrl
    ) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
        this.geocodingUrl = geocodingUrl;
        this.forecastUrl = forecastUrl;
    }

    public WeatherResponse getForecast(
            String city,
            String state,
            String country,
            LocalDateTime eventDateTime
    ) {
        validateRequest(city, country, eventDateTime);

        if (!StringUtils.hasText(apiKey)) {
            throw new BusinessException(WEATHER_DISABLED_MESSAGE);
        }

        JsonNode location = findLocation(city, state, country);

        double latitude = location.path("lat").asDouble();
        double longitude = location.path("lon").asDouble();

        JsonNode forecast = findClosestForecast(
                latitude,
                longitude,
                eventDateTime
        );

        JsonNode weatherDetails = forecast.path("weather").get(0);
        JsonNode mainDetails = forecast.path("main");
        JsonNode windDetails = forecast.path("wind");

        if (weatherDetails == null) {
            throw new IllegalStateException(
                    "OpenWeather did not return weather details."
            );
        }

        return WeatherResponse.builder()
                .city(location.path("name").asString(city))
                .country(location.path("country").asString(country))
                .weather(weatherDetails.path("main").asString())
                .description(weatherDetails.path("description").asString())
                .temperature(mainDetails.path("temp").asDouble())
                .feelsLike(mainDetails.path("feels_like").asDouble())
                .humidity(mainDetails.path("humidity").asInt())
                .windSpeed(windDetails.path("speed").asDouble())
                .rainChance(forecast.path("pop").asDouble() * 100)
                .forecastTime(forecast.path("dt_txt").asString())
                .build();
    }

    public WeatherResponse getForecastByCoordinates(
            double latitude,
            double longitude,
            LocalDateTime eventDateTime
    ) {
        if (eventDateTime == null) {
            throw new IllegalArgumentException(
                    "Event date and time are required."
            );
        }

        JsonNode forecast = findClosestForecast(
                latitude,
                longitude,
                eventDateTime
        );

        JsonNode weatherDetails = forecast.path("weather").get(0);
        JsonNode mainDetails = forecast.path("main");
        JsonNode windDetails = forecast.path("wind");

        if (weatherDetails == null) {
            throw new IllegalStateException(
                    "OpenWeather did not return weather details."
            );
        }

        return WeatherResponse.builder()
                .weather(weatherDetails.path("main").asString())
                .description(weatherDetails.path("description").asString())
                .temperature(mainDetails.path("temp").asDouble())
                .feelsLike(mainDetails.path("feels_like").asDouble())
                .humidity(mainDetails.path("humidity").asInt())
                .windSpeed(windDetails.path("speed").asDouble())
                .rainChance(forecast.path("pop").asDouble() * 100)
                .forecastTime(forecast.path("dt_txt").asString())
                .build();
    }

    private JsonNode findLocation(
            String city,
            String state,
            String country
    ) {
        String locationQuery = buildLocationQuery(city, state, country);

        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(geocodingUrl)
                    .queryParam("q", locationQuery)
                    .queryParam("limit", 1)
                    .queryParam("appid", apiKey)
                    .build()
                    .encode()
                    .toUri();

            JsonNode response = restClient
                    .get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.isArray() || response.isEmpty()) {
                throw new IllegalArgumentException(
                        "No location found for " + locationQuery
                );
            }

            return response.get(0);
        } catch (RestClientException exception) {
            throw new IllegalStateException(
                    "Unable to retrieve location information from OpenWeather.",
                    exception
            );
        }
    }

    private JsonNode findClosestForecast(
            double latitude,
            double longitude,
            LocalDateTime eventDateTime
    ) {
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(forecastUrl)
                    .queryParam("lat", latitude)
                    .queryParam("lon", longitude)
                    .queryParam("units", "imperial")
                    .queryParam("appid", apiKey)
                    .build()
                    .encode()
                    .toUri();

            JsonNode response = restClient
                    .get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.path("list").isArray()) {
                throw new IllegalStateException(
                        "OpenWeather returned an invalid forecast response."
                );
            }

            JsonNode closestForecast = null;
            long smallestDifference = Long.MAX_VALUE;

            for (JsonNode forecast : response.path("list")) {
                String forecastText = forecast.path("dt_txt").asString();

                if (forecastText == null || forecastText.isBlank()) {
                    continue;
                }

                LocalDateTime forecastDateTime = LocalDateTime.parse(
                        forecastText,
                        OPENWEATHER_DATE_FORMAT
                );

                long difference = Math.abs(
                        Duration.between(
                                eventDateTime,
                                forecastDateTime
                        ).toMinutes()
                );

                if (difference < smallestDifference) {
                    smallestDifference = difference;
                    closestForecast = forecast;
                }
            }

            if (closestForecast == null) {
                throw new IllegalStateException(
                        "No forecast is available for the selected time."
                );
            }

            if (smallestDifference > MAX_FORECAST_DIFFERENCE_MINUTES) {
                throw new BusinessException(
                        "Weather forecast is not available yet for the selected event date. "
                                + "Please check again closer to the event."
                );
            }

            return closestForecast;
        } catch (RestClientException exception) {
            throw new IllegalStateException(
                    "Unable to retrieve forecast data from OpenWeather.",
                    exception
            );
        }
    }

    private String buildLocationQuery(
            String city,
            String state,
            String country
    ) {
        StringBuilder query = new StringBuilder(city.trim());

        if (state != null && !state.isBlank()) {
            query.append(",").append(state.trim());
        }

        query.append(",").append(country.trim());

        return query.toString();
    }

    private void validateRequest(
            String city,
            String country,
            LocalDateTime eventDateTime
    ) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("City is required.");
        }

        if (country == null || country.isBlank()) {
            throw new IllegalArgumentException("Country is required.");
        }

        if (eventDateTime == null) {
            throw new IllegalArgumentException(
                    "Event date and time are required."
            );
        }
    }
}
