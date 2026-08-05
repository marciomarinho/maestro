package dev.maestro.ledger.web;

import dev.maestro.events.EventCodec;
import dev.maestro.ledger.LedgerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class LedgerWebConfig {

    /** Applied to every path, so a new endpoint is protected by default. */
    @Bean
    public FilterRegistrationBean<OpsTokenFilter> opsTokenFilter(LedgerProperties properties) {
        FilterRegistrationBean<OpsTokenFilter> registration =
                new FilterRegistrationBean<>(new OpsTokenFilter(properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * The ledger consumes events but owns no outbox, so it does not get the codec from the
     * outbox auto-configuration the way payment-api and the router do.
     */
    @Bean
    public EventCodec eventCodec() {
        return new EventCodec();
    }
}
