package com.scmmaisc.controller;

import com.scmmaisc.common.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（T023，C8）：GET /api/health 返回 { status, db }，db 探测 MySQL 连通性。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/health")
    public ApiResult<Map<String, String>> health() {
        boolean dbUp = isDbUp();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", dbUp ? "UP" : "DOWN");
        data.put("db", dbUp ? "UP" : "DOWN");
        return ApiResult.ok(data);
    }

    private boolean isDbUp() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
