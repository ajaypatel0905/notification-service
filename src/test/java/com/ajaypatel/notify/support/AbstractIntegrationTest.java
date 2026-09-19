package com.ajaypatel.notify.support;

import com.ajaypatel.notify.dispatch.DispatchPoller;
import com.ajaypatel.notify.notification.NotificationRepository;
import com.ajaypatel.notify.ratelimit.TenantRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
public abstract class AbstractIntegrationTest {
    public static final String ADMIN_KEY = "test-admin-key-123456";
    public static final String CALLBACK_SECRET = "test-callback-secret";

    @Autowired
    protected TestRestTemplate rest;

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected NotificationRepository notificationRepository;

    @Autowired
    protected DispatchPoller poller;

    @Autowired
    protected TenantRateLimiter rateLimiter;

    protected ApiClient api;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, ADMIN_KEY);
        poller.resume();
    }
}
