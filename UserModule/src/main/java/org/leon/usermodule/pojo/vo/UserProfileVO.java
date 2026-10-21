package org.leon.usermodule.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 与 AuthModule 的 UserProfileVO 字段一一对应；类不同名是刻意为之（数据契约）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO {
    private Long userId;
    private String username;
    private String roleId;
    private Integer status;
    private String phone;
    private Date lastLoginAt;
    private String lastLoginIp;
}
