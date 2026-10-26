package com.waypoint.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configures secure Cross-Origin Resource Sharing (CORS).
 * Restricts credentialed access to verified local development endpoints,
 * private mobile test subnets (192.168.x.x, 10.x.x.x), hybrid mobile app
 * origins (Capacitor/Ionic), and configured production origins via
 * {@code CORS_ALLOWED_ORIGINS}.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${waypoint.cors.allowed-origins:${CORS_ALLOWED_ORIGINS:}}")
    private String customAllowedOrigins;

    private static final String[] DEFAULT_ALLOWED_ORIGIN_PATTERNS = {
            "http://localhost:[*]",
            "https://localhost:[*]",
            "http://127.0.0.1:[*]",
            "https://127.0.0.1:[*]",
            "http://192.168.*:[*]",
            "https://192.168.*:[*]",
            "http://10.*:[*]",
            "https://10.*:[*]",
            "http://172.16.*:[*]",
            "https://172.16.*:[*]",
            "capacitor://localhost",
            "ionic://localhost",
            "https://localhost",
            "http://localhost"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns = resolveOriginPatterns();

        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);

        registry.addMapping("/uploads/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    private String[] resolveOriginPatterns() {
        List<String> patterns = new ArrayList<>(Arrays.asList(DEFAULT_ALLOWED_ORIGIN_PATTERNS));
        if (StringUtils.hasText(customAllowedOrigins)) {
            for (String origin : customAllowedOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty()) {
                    patterns.add(trimmed);
                }
            }
        }
        return patterns.toArray(new String[0]);
    }
}
