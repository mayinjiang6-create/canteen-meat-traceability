package com.jxgc.meattrace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 高校食堂肉类供应链溯源系统 · 启动类。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MeatTraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeatTraceApplication.class, args);
    }
}
