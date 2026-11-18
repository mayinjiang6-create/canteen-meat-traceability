package com.jxgc.meattrace.common.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 时间格式统一。
 *
 * <p>数据库是 DATETIME（无时区），实体用 {@link LocalDateTime}；接口出参按契约要求
 * 输出带偏移的 ISO-8601（如 {@code 2026-11-18T09:30:00+08:00}），所以序列化时
 * 固定补 {@code +08:00}。
 *
 * <p>数量与金额不走全局配置：VO 里直接用 String 承载（Service 层格式化），
 * 避免"120.000 变成 120.00099999999999"这类浮点误差毁掉摘要比对。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+08:00'");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer timeCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME));
            module.addSerializer(LocalDate.class, new LocalDateSerializer(DATE));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME));
            module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE));
            builder.modulesToInstall(module);
        };
    }
}
