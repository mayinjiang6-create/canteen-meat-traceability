package com.jxgc.meattrace.module.evidence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 存证任务。
 *
 * <p>状态流转：PENDING → SUBMITTED → CONFIRMED / CONFLICT / FAILED / UNREACHABLE。
 * 状态语义见 docs/tech/02-数据库设计.md 6.3。
 */
@Data
@TableName("evidence_task")
public class EvidenceTask {

    public static final String PENDING = "PENDING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String FAILED = "FAILED";
    public static final String UNREACHABLE = "UNREACHABLE";
    public static final String CONFLICT = "CONFLICT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
