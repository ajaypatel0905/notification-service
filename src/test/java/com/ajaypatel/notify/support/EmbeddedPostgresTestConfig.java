package com.ajaypatel.notify.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;

/** One embedded PostgreSQL for the whole test JVM; the Spring context is cached across IT classes. */
@TestConfiguration
public class EmbeddedPostgresTestConfig {
    private static volatile EmbeddedPostgres pg;

    @Bean
    @Primary
    DataSource testDataSource() throws IOException {
        if (pg == null) {
            synchronized (EmbeddedPostgresTestConfig.class) {
                if (pg == null) {
                    pg = EmbeddedPostgres.builder().start();
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            pg.close();
                        } catch (IOException ignored) {
                        }
                    }));
                }
            }
        }
        return pg.getPostgresDatabase();
    }
}
