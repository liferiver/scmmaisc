package com.scmmaisc;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 电商物流与供应链参数化模拟平台启动类。
 */
@SpringBootApplication
@MapperScan("com.scmmaisc.mapper")
@EnableScheduling
public class ScmSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScmSimApplication.class, args);
    }
}
