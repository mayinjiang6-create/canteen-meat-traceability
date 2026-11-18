package com.jxgc.meattrace.module.supplier.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 供应商备案。状态 ACTIVE / SUSPENDED / BLACKLISTED。 */
@Data
@TableName("supplier")
public class Supplier {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String BLACKLISTED = "BLACKLISTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 形如 SUP-0001，创建时自动生成。 */
    private String supplierCode;

    private String name;

    private String creditCode;

    private String licenseNo;

    private String contactName;

    private String contactPhone;

    private String address;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
