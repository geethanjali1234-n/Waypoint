package com.waypoint.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClaudeDocumentExtractionCapSafetyTest {
    @Test void disablingExtractionPreventsProviderCalls() {
        var builder = mock(RestClient.Builder.class);
        var client = mock(RestClient.class);
        when(builder.build()).thenReturn(client);
        var service = new ClaudeDocumentExtractionService(builder, "test-key", "http://127.0.0.1:1", "test-model");
        service.enabled(false);

        assertThat(service.isConfigured()).isFalse();
        assertThat(service.extract(new byte[]{1}, "timesheet.png", "doc", "MDY")).isEmpty();
        verifyNoInteractions(client);
    }
}
