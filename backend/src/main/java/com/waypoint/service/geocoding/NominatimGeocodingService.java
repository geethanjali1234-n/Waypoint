package com.waypoint.service.geocoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Geocodes via OpenStreetMap's free Nominatim API - no API key required, but
 * its usage policy caps the public instance at ~1 request/second and
 * requires a descriptive User-Agent. Fine at this project's call volume
 * (geocoding only fires on employer job create/update). Every failure mode
 * (no match, network error, timeout, malformed response) is swallowed here
 * and reported as Optional.empty() - callers rely on that to never let a
 * geocoding problem block a job save.
 */
@Service
public class NominatimGeocodingService implements GeocodingService {

    private static final Logger logger = LoggerFactory.getLogger(NominatimGeocodingService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String userAgent;
    private final Duration timeout;

    public NominatimGeocodingService(
            @Value("${waypoint.geocoding.nominatim.base-url}") String baseUrl,
            @Value("${waypoint.geocoding.nominatim.user-agent}") String userAgent,
            @Value("${waypoint.geocoding.nominatim.timeout-ms}") long timeoutMs) {
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public Optional<GeoCoordinates> geocode(String locationText) {
        if (locationText == null || locationText.isBlank()) {
            return Optional.empty();
        }

        try {
            String encodedQuery = URLEncoder.encode(locationText.trim(), StandardCharsets.UTF_8);
            URI uri = URI.create(baseUrl + "?q=" + encodedQuery + "&format=json&limit=1");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Nominatim geocoding for '{}' returned status {}", locationText, response.statusCode());
                return Optional.empty();
            }

            JsonNode results = objectMapper.readTree(response.body());
            if (!results.isArray() || results.isEmpty()) {
                return Optional.empty();
            }

            JsonNode first = results.get(0);
            BigDecimal latitude = new BigDecimal(first.get("lat").asString());
            BigDecimal longitude = new BigDecimal(first.get("lon").asString());
            return Optional.of(new GeoCoordinates(latitude, longitude));
        } catch (Exception exception) {
            // Broad catch is deliberate: any geocoding failure must degrade to
            // "no coordinates," never propagate and block a job create/update.
            logger.warn("Geocoding failed for location '{}': {}", locationText, exception.getMessage());
            return Optional.empty();
        }
    }
}
