package com.waypoint.config;

import com.waypoint.security.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers JwtAuthInterceptor for protected workspace API paths. Kept
 * separate from CorsConfig so CORS mapping and auth-interceptor registration
 * can evolve independently.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns(
                        "/api/waypoint-admin/**",
                        "/api/employer/**",
                        "/api/myhr/**",
                        "/api/job-seeker/jobs/**",
                        "/api/job-seeker/profile/**")
                .excludePathPatterns("/api/auth/**");
    }
}
