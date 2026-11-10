package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlatformGmailApiEmailServiceTest {
    private final GoogleOAuthProperties properties = new GoogleOAuthProperties();
    private final PlatformGoogleSenderService sender = mock(PlatformGoogleSenderService.class);
    private final GoogleOAuthClient google = mock(GoogleOAuthClient.class);
    private PlatformGmailApiEmailService service;

    @BeforeEach void setUp() {
        properties.setPlatformSenderEnabled(true);
        service = new PlatformGmailApiEmailService(properties, sender, google);
        ReflectionTestUtils.setField(service, "fromName", "WayPoint");
    }

    @Test void sendsMimeMessageThroughHttpsGmailTransport() {
        when(sender.accessToken()).thenReturn("access-token");
        service.send("person@example.test", "OTP", "<p>123456</p>", "123456", List.of());
        verify(google).sendMessage(eq("access-token"), argThat(raw -> raw != null && !raw.isBlank()));
    }

    @Test void providerOrAuthorizationFailureIsNotRoutedToSmtp() {
        when(sender.accessToken()).thenThrow(new PlatformGoogleSenderService.Unavailable("NOT_CONNECTED"));
        assertThatThrownBy(() -> service.send("person@example.test", "Reset", "<p>x</p>", null, List.of()))
                .isInstanceOf(PlatformGmailApiEmailService.Unavailable.class);
        verify(google, never()).sendMessage(anyString(), anyString());
    }
}
