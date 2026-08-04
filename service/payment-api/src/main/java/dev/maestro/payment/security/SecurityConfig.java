package dev.maestro.payment.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class SecurityConfig {

    /**
     * Registered across every path rather than an enumerated list. An allow-list of
     * protected routes would leave a new endpoint unauthenticated by default; this way
     * a new endpoint is protected by default and exemptions are explicit and few.
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthentication(
            ApiKeyRepository apiKeys) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthenticationFilter(apiKeys));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
