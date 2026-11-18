package com.jxgc.meattrace.module.idempotency.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 业务层幂等记录（BR-10）。
 *
 * <p>只记业务主键，不缓存整个响应体——避免响应结构演进后旧数据反序列化失败。
 */
@Data
@TableName("request_idempotency")
public class RequestIdempotency {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    private Long userId;

    private String endpoint;

    private Long bizId;

    private String businessNo;

    private LocalDateTime createdAt;
}
