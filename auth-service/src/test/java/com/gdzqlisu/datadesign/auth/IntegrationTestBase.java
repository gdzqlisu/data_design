package com.gdzqlisu.datadesign.auth;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 所有需要数据库或 Redis 的测试都继承本类。
 * 容器用静态字段持有，整个 JVM 只启动一次。
 * 镜像固定为本机已有版本，不联网拉取。
 *
 * 注意：这里用 @DynamicPropertySource 而不是 @ServiceConnection。
 * @ServiceConnection 只对 @TestConfiguration 里的 @Bean 方法生效，
 * 加在静态字段上不会注册连接，测试会去连真实的 localhost:3306。
 */
public abstract class IntegrationTestBase {

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("data_design")
                    .withUsername("app")
                    .withPassword("app");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
