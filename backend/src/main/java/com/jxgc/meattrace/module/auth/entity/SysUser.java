package com.jxgc.meattrace.module.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Data;

/** 账号与角色，对应 sys_user 表。角色只有 ADMIN / SUPPLIER / VIEWER 三种。 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希，禁止明文；不参与 JSON 序列化。 */
    @JsonIgnore
    private String passwordHash;

    private String displayName;

    /** ADMIN 全部写权限 / SUPPLIER 只读自己 / VIEWER 只读。 */
    private String role;

    /** SUPPLIER 角色关联的供应商，其他角色为 null。 */
    private Long supplierId;

    /** 1 启用 / 0 停用。 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
