package com.atlasmind.ai_travel_recommendation.controller;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbUp = isDatabaseUp();
        boolean redisUp = isRedisUp();
        HttpStatus status = (dbUp && redisUp) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status == HttpStatus.OK ? "UP" : "DEGRADED");
        body.put("service", "atlaswatch-backend");
        body.put("timeStamp", LocalDateTime.now());
        body.put("database", dbUp ? "UP" : "DOWN");
        body.put("redis", redisUp ? "UP" : "DOWN");
        return ResponseEntity.status(status).body(body);
    }

    private boolean isDatabaseUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isRedisUp() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (Exception ignored) {
            return false;
        }
    }
}
