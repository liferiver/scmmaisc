package com.scmmaisc.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置：放行本地前端来源。
 * - 开发期：Vite dev server（5173 端口）
 * - Docker 部署：nginx 对外端口 8088（浏览器对 POST 请求总会携带 Origin 头，
 *   即使前端经 nginx 同域代理 /api，后端仍按此白名单校验，未命中即返回 403）
 * 端口使用通配符，避免改动端口后失效；如需局域网 IP 访问，追加对应 Origin 即可。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "DELETE", "PUT", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
