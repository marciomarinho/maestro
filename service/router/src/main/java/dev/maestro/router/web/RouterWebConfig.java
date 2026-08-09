package dev.maestro.router.web;

import dev.maestro.router.RouterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RouterWebConfig {

    /** Applied to every path, so a new endpoint is protected by default. */
    @Bean
    public FilterRegistrationBean<OpsTokenFilter> opsTokenFilter(RouterProperties properties) {
        FilterRegistrationBean<OpsTokenFilter> registration =
                new FilterRegistrationBean<>(new OpsTokenFilter(properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
