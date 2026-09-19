package com.ajaypatel.notify.common.local;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Zero-setup local run: boots a throwaway PostgreSQL in-process so the service can be started
 * with a single command. Not used in tests (they have their own bootstrap) or in real deployments.
 */
@Configuration
@ConditionalOnProperty(prefix = "notify.local", name = "embedded-postgres", havingValue = "true")
public class EmbeddedPostgresConfig {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
        EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
        log.info("Embedded PostgreSQL started on port {}", pg.getPort());
        return pg;
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres pg) {
        return pg.getPostgresDatabase();
    }
}
