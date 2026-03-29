package com.atlasmind.ai_travel_recommendation.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private RedisConnectionFactory redisConnectionFactory;
    @Mock
    private Connection connection;
    @Mock
    private RedisConnection redisConnection;

    @InjectMocks
    private HealthController healthController;

    @Test
    void healthReturnsUpWhenDatabaseAndRedisAreReachable() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        ResponseEntity<Map<String, Object>> response = healthController.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("UP", response.getBody().get("database"));
        assertEquals("UP", response.getBody().get("redis"));
    }

    @Test
    void healthReturnsDegradedWhenRedisCheckFails() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(redisConnectionFactory.getConnection()).thenThrow(new IllegalStateException("redis down"));

        ResponseEntity<Map<String, Object>> response = healthController.health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DEGRADED", response.getBody().get("status"));
        assertEquals("UP", response.getBody().get("database"));
        assertEquals("DOWN", response.getBody().get("redis"));
    }
}
