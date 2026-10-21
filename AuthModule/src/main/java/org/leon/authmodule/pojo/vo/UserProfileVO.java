package org.leon.authmodule.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 内部查询返回的用户资料。刻意不含 password_hash。 */
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
