package com.jxgc.meattrace.common;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存活探针——**只证明进程与 Web 容器活着**，不查库、不查链。
 *
 * <p>为什么要单独一个接口：容器 HEALTHCHECK、交付验收第一步、负载均衡摘流都需要一个
 * 「秒回且不依赖外部系统」的地址。拿业务接口当探针会把链超时、库慢查询算进健康判定，
 * 于是一次链抖动就会让容器被反复重启。
 *
 * <p>数据库/链是否就绪，分别由业务接口与公开溯源页的四类核验状态表达，不在这里混为一谈。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public R<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "meat-trace");
        body.put("time", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        return R.ok(body);
    }
}
