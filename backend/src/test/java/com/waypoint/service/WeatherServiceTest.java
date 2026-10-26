package com.waypoint.service;

import com.waypoint.dto.event.WeatherResponse;
import com.waypoint.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeatherServiceTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private static final String GEOCODING_URL = "https://geo.test/direct";
    private static final String FORECAST_URL = "https://forecast.test/forecast";

    private static final String COORDS_FORECAST_URL = "https://api.openweathermap.org/data/2.5/forecast";

    private static final String FORECAST_JSON = """
            {
              "list": [
                {
                  "dt_txt": "2026-08-06 12:00:00",
                  "main": {"temp": 85.0, "feels_like": 87.0, "humidity": 40},
                  "weather": [{"main": "Clear", "description": "clear sky"}],
                  "wind": {"speed": 5.0},
                  "pop": 0.1
                },
                {
                  "dt_txt": "2026-08-06 15:00:00",
                  "main": {"temp": 88.0, "feels_like": 90.0, "humidity": 45},
                  "weather": [{"main": "Clouds", "description": "scattered clouds"}],
                  "wind": {"speed": 6.5},
                  "pop": 0.3
                },
                {
                  "dt_txt": "2026-08-06 18:00:00",
                  "main": {"temp": 82.0, "feels_like": 84.0, "humidity": 50},
                  "weather": [{"main": "Rain", "description": "light rain"}],
                  "wind": {"speed": 8.0},
                  "pop": 0.6
                }
              ]
            }
            """;

    private WeatherService buildWeatherService(RestClient restClient) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);
        return new WeatherService(builder, "test-api-key", GEOCODING_URL, FORECAST_URL);
    }

    @SuppressWarnings("rawtypes")
    private RestClient.RequestHeadersUriSpec<?> sharedUriSpec(RestClient restClient) {
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        doReturn(uriSpec).when(restClient).get();
        return uriSpec;
    }

    @SuppressWarnings("rawtypes")
    private void stubResponseForUriContaining(
            RestClient.RequestHeadersUriSpec<?> uriSpec, String uriFragment, JsonNode response) {

        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        doReturn(headersSpec).when(uriSpec).uri(argThat((URI uri) -> uri != null && uri.toString().contains(uriFragment)));
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(JsonNode.class)).thenReturn(response);
    }

    // ---------- getForecast (geocoding-based, employer event creation) ----------

    @Test
    void getForecast_validLocation_returnsMappedWeatherResponse() {
        RestClient restClient = mock(RestClient.class);
        WeatherService weatherService = buildWeatherService(restClient);
        RestClient.RequestHeadersUriSpec<?> uriSpec = sharedUriSpec(restClient);

        JsonNode geocodeResponse = JSON_MAPPER.readTree(
                "[{\"name\":\"Austin\",\"lat\":30.2672,\"lon\":-97.7431,\"country\":\"US\"}]");
        JsonNode forecastResponse = JSON_MAPPER.readTree(
                "{\"list\":[{\"dt_txt\":\"2026-08-14 12:00:00\","
                        + "\"main\":{\"temp\":95.5,\"feels_like\":99.1,\"humidity\":40},"
                        + "\"weather\":[{\"main\":\"Clear\",\"description\":\"clear sky\"}],"
                        + "\"wind\":{\"speed\":8.5},\"pop\":0.1}]}");

        stubResponseForUriContaining(uriSpec, "geo.test", geocodeResponse);
        stubResponseForUriContaining(uriSpec, "forecast.test", forecastResponse);

        WeatherResponse response = weatherService.getForecast(
                "Austin", "TX", "US", LocalDateTime.of(2026, 8, 14, 12, 0));

        assertThat(response.getCity()).isEqualTo("Austin");
        assertThat(response.getCountry()).isEqualTo("US");
        assertThat(response.getWeather()).isEqualTo("Clear");
        assertThat(response.getDescription()).isEqualTo("clear sky");
        assertThat(response.getTemperature()).isEqualTo(95.5);
        assertThat(response.getFeelsLike()).isEqualTo(99.1);
        assertThat(response.getHumidity()).isEqualTo(40);
        assertThat(response.getWindSpeed()).isEqualTo(8.5);
        assertThat(response.getRainChance()).isEqualTo(10.0);
        assertThat(response.getForecastTime()).isEqualTo("2026-08-14 12:00:00");
    }

    @Test
    void getForecast_blankCity_throwsIllegalArgumentException() {
        WeatherService weatherService = buildWeatherService(mock(RestClient.class));

        assertThatThrownBy(() -> weatherService.getForecast("", "TX", "US", LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("City is required.");
    }

    @Test
    void getForecast_blankCountry_throwsIllegalArgumentException() {
        WeatherService weatherService = buildWeatherService(mock(RestClient.class));

        assertThatThrownBy(() -> weatherService.getForecast("Austin", "TX", " ", LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Country is required.");
    }

    @Test
    void getForecast_nullEventDateTime_throwsIllegalArgumentException() {
        WeatherService weatherService = buildWeatherService(mock(RestClient.class));

        assertThatThrownBy(() -> weatherService.getForecast("Austin", "TX", "US", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event date and time are required.");
    }

    @Test
    void getForecast_noLocationFound_throwsIllegalArgumentException() {
        RestClient restClient = mock(RestClient.class);
        WeatherService weatherService = buildWeatherService(restClient);
        RestClient.RequestHeadersUriSpec<?> uriSpec = sharedUriSpec(restClient);

        JsonNode emptyGeocodeResponse = JSON_MAPPER.readTree("[]");
        stubResponseForUriContaining(uriSpec, "geo.test", emptyGeocodeResponse);

        assertThatThrownBy(() -> weatherService.getForecast(
                "Nowhere", null, "US", LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("No location found for");
    }

    @Test
    void getForecast_forecastResponseMissingList_throwsIllegalStateException() {
        RestClient restClient = mock(RestClient.class);
        WeatherService weatherService = buildWeatherService(restClient);
        RestClient.RequestHeadersUriSpec<?> uriSpec = sharedUriSpec(restClient);

        JsonNode geocodeResponse = JSON_MAPPER.readTree(
                "[{\"name\":\"Austin\",\"lat\":30.2672,\"lon\":-97.7431,\"country\":\"US\"}]");
        JsonNode invalidForecastResponse = JSON_MAPPER.readTree("{}");

        stubResponseForUriContaining(uriSpec, "geo.test", geocodeResponse);
        stubResponseForUriContaining(uriSpec, "forecast.test", invalidForecastResponse);

        assertThatThrownBy(() -> weatherService.getForecast(
                "Austin", "TX", "US", LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OpenWeather returned an invalid forecast response.");
    }

    @Test
    void getForecast_requestedTimeBetweenSlots_selectsNearestSlot() {
        RestClient restClient = mock(RestClient.class);
        WeatherService weatherService = buildWeatherService(restClient);
        RestClient.RequestHeadersUriSpec<?> uriSpec = sharedUriSpec(restClient);

        JsonNode geocodeResponse = JSON_MAPPER.readTree(
                "[{\"name\":\"Austin\",\"lat\":30.2672,\"lon\":-97.7431,\"country\":\"US\"}]");
        JsonNode forecastResponse = JSON_MAPPER.readTree(
                "{\"list\":["
                        + "{\"dt_txt\":\"2026-08-14 12:00:00\","
                        + "\"main\":{\"temp\":90.0,\"feels_like\":92.0,\"humidity\":35},"
                        + "\"weather\":[{\"main\":\"Clear\",\"description\":\"clear sky\"}],"
                        + "\"wind\":{\"speed\":5.0},\"pop\":0.05},"
                        + "{\"dt_txt\":\"2026-08-14 15:00:00\","
                        + "\"main\":{\"temp\":95.5,\"feels_like\":99.1,\"humidity\":40},"
                        + "\"weather\":[{\"main\":\"Rain\",\"description\":\"light rain\"}],"
                        + "\"wind\":{\"speed\":8.5},\"pop\":0.6}"
                        + "]}");

        stubResponseForUriContaining(uriSpec, "geo.test", geocodeResponse);
        stubResponseForUriContaining(uriSpec, "forecast.test", forecastResponse);

        WeatherResponse response = weatherService.getForecast(
                "Austin", "TX", "US", LocalDateTime.of(2026, 8, 14, 13, 0));

        assertThat(response.getForecastTime()).isEqualTo("2026-08-14 12:00:00");
        assertThat(response.getWeather()).isEqualTo("Clear");
    }

    @Test
    void getForecast_eventBeyondForecastWindow_throwsBusinessException() {
        RestClient restClient = mock(RestClient.class);
        WeatherService weatherService = buildWeatherService(restClient);
        RestClient.RequestHeadersUriSpec<?> uriSpec = sharedUriSpec(restClient);

        JsonNode geocodeResponse = JSON_MAPPER.readTree(
                "[{\"name\":\"Austin\",\"lat\":30.2672,\"lon\":-97.7431,\"country\":\"US\"}]");
        JsonNode forecastResponse = JSON_MAPPER.readTree(
                "{\"list\":[{\"dt_txt\":\"2026-08-14 12:00:00\","
                        + "\"main\":{\"temp\":90.0,\"feels_like\":92.0,\"humidity\":35},"
                        + "\"weather\":[{\"main\":\"Clear\",\"description\":\"clear sky\"}],"
                        + "\"wind\":{\"speed\":5.0},\"pop\":0.05}]}");

        stubResponseForUriContaining(uriSpec, "geo.test", geocodeResponse);
        stubResponseForUriContaining(uriSpec, "forecast.test", forecastResponse);

        assertThatThrownBy(() -> weatherService.getForecast(
                "Austin", "TX", "US", LocalDateTime.of(2026, 8, 19, 12, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Weather forecast is not available yet for the selected event date. "
                        + "Please check again closer to the event.");
    }

    @Test
    void getForecast_eventBeforeForecastWindow_throwsBusinessException() {
        RestClient restClient = mock(RestClient.class);
        WeatherService weatherService = buildWeatherService(restClient);
        RestClient.RequestHeadersUriSpec<?> uriSpec = sharedUriSpec(restClient);

        JsonNode geocodeResponse = JSON_MAPPER.readTree(
                "[{\"name\":\"Austin\",\"lat\":30.2672,\"lon\":-97.7431,\"country\":\"US\"}]");
        JsonNode forecastResponse = JSON_MAPPER.readTree(
                "{\"list\":[{\"dt_txt\":\"2026-08-14 12:00:00\","
                        + "\"main\":{\"temp\":90.0,\"feels_like\":92.0,\"humidity\":35},"
                        + "\"weather\":[{\"main\":\"Clear\",\"description\":\"clear sky\"}],"
                        + "\"wind\":{\"speed\":5.0},\"pop\":0.05}]}");

        stubResponseForUriContaining(uriSpec, "geo.test", geocodeResponse);
        stubResponseForUriContaining(uriSpec, "forecast.test", forecastResponse);

        assertThatThrownBy(() -> weatherService.getForecast(
                "Austin", "TX", "US", LocalDateTime.of(2026, 8, 9, 12, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Weather forecast is not available yet for the selected event date. "
                        + "Please check again closer to the event.");
    }

    // ---------- getForecastByCoordinates (event register page) ----------

    private WeatherService buildCoordinatesService(RestClient.Builder builder) {
        return new WeatherService(builder, "test-api-key", GEOCODING_URL, COORDS_FORECAST_URL);
    }

    @Test
    void getForecastByCoordinatesReturnsClosestForecastSlot() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeatherService weatherService = buildCoordinatesService(builder);

        server.expect(requestTo(startsWith(COORDS_FORECAST_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("lat", "30.2672"))
                .andExpect(queryParam("lon", "-97.7431"))
                .andExpect(queryParam("units", "imperial"))
                .andExpect(queryParam("appid", "test-api-key"))
                .andRespond(withSuccess(FORECAST_JSON, MediaType.APPLICATION_JSON));

        WeatherResponse response = weatherService.getForecastByCoordinates(
                30.2672, -97.7431, LocalDateTime.of(2026, 8, 6, 14, 0));

        assertThat(response.getWeather()).isEqualTo("Clouds");
        assertThat(response.getDescription()).isEqualTo("scattered clouds");
        assertThat(response.getTemperature()).isEqualTo(88.0);
        assertThat(response.getFeelsLike()).isEqualTo(90.0);
        assertThat(response.getHumidity()).isEqualTo(45);
        assertThat(response.getWindSpeed()).isEqualTo(6.5);
        assertThat(response.getRainChance()).isEqualTo(30.0);
        assertThat(response.getForecastTime()).isEqualTo("2026-08-06 15:00:00");
    }

    @Test
    void getForecastByCoordinatesThrowsIllegalArgumentExceptionWhenEventDateTimeIsNull() {
        WeatherService weatherService = buildCoordinatesService(RestClient.builder());

        assertThatThrownBy(() -> weatherService.getForecastByCoordinates(30.2672, -97.7431, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event date and time are required.");
    }

    @Test
    void getForecastByCoordinatesThrowsIllegalStateExceptionWhenApiCallFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeatherService weatherService = buildCoordinatesService(builder);

        server.expect(requestTo(startsWith(COORDS_FORECAST_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> weatherService.getForecastByCoordinates(
                30.2672, -97.7431, LocalDateTime.of(2026, 8, 6, 14, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to retrieve forecast data from OpenWeather.");
    }

    @Test
    void getForecastByCoordinatesThrowsIllegalStateExceptionWhenResponseHasNoForecastList() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeatherService weatherService = buildCoordinatesService(builder);

        server.expect(requestTo(startsWith(COORDS_FORECAST_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> weatherService.getForecastByCoordinates(
                30.2672, -97.7431, LocalDateTime.of(2026, 8, 6, 14, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OpenWeather returned an invalid forecast response.");
    }

    @Test
    void getForecastByCoordinatesThrowsIllegalStateExceptionWhenNoSlotHasATimestamp() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeatherService weatherService = buildCoordinatesService(builder);

        server.expect(requestTo(startsWith(COORDS_FORECAST_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "list": [ { "dt_txt": "" } ] }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> weatherService.getForecastByCoordinates(
                30.2672, -97.7431, LocalDateTime.of(2026, 8, 6, 14, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No forecast is available for the selected time.");
    }
}
