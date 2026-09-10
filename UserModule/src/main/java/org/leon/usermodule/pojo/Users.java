package org.leon.usermodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 用户核心账号表
 * @TableName users_dev
 */
@TableName(value ="users_dev")
@Data
public class Users implements Serializable {
    /**
     * 用户唯一编号
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户名
     */
    @TableField(value = "username")
    private String username;

    /**
     * 密码哈希值
     */
    @TableField(value = "password_hash")
    private String password_hash;

    /**
     * 账号状态：0禁用，1正常，2冻结，3已注销
     */
    @TableField(value = "status")
    private Integer status;

    /**
     * 注册时间
     */
    @TableField(value = "created_at")
    private Date created_at;

    /**
     * 最后更新时间
     */
    @TableField(value = "updated_at")
    private Date updated_at;

    /**
     * 上次成功登录时间
     */
    @TableField(value = "last_login_at")
    private Date last_login_at;

    /**
     * 最近登录IP，兼容IPv4和IPv6
     */
    @TableField(value = "last_login_ip")
    private String last_login_ip;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        Users other = (Users) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getUsername() == null ? other.getUsername() == null : this.getUsername().equals(other.getUsername()))
            && (this.getPassword_hash() == null ? other.getPassword_hash() == null : this.getPassword_hash().equals(other.getPassword_hash()))
            && (this.getStatus() == null ? other.getStatus() == null : this.getStatus().equals(other.getStatus()))
            && (this.getCreated_at() == null ? other.getCreated_at() == null : this.getCreated_at().equals(other.getCreated_at()))
            && (this.getUpdated_at() == null ? other.getUpdated_at() == null : this.getUpdated_at().equals(other.getUpdated_at()))
            && (this.getLast_login_at() == null ? other.getLast_login_at() == null : this.getLast_login_at().equals(other.getLast_login_at()))
            && (this.getLast_login_ip() == null ? other.getLast_login_ip() == null : this.getLast_login_ip().equals(other.getLast_login_ip()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getUsername() == null) ? 0 : getUsername().hashCode());
        result = prime * result + ((getPassword_hash() == null) ? 0 : getPassword_hash().hashCode());
        result = prime * result + ((getStatus() == null) ? 0 : getStatus().hashCode());
        result = prime * result + ((getCreated_at() == null) ? 0 : getCreated_at().hashCode());
        result = prime * result + ((getUpdated_at() == null) ? 0 : getUpdated_at().hashCode());
        result = prime * result + ((getLast_login_at() == null) ? 0 : getLast_login_at().hashCode());
        result = prime * result + ((getLast_login_ip() == null) ? 0 : getLast_login_ip().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", id=").append(id);
        sb.append(", username=").append(username);
        sb.append(", password_hash=").append(password_hash);
        sb.append(", status=").append(status);
        sb.append(", created_at=").append(created_at);
        sb.append(", updated_at=").append(updated_at);
        sb.append(", last_login_at=").append(last_login_at);
        sb.append(", last_login_ip=").append(last_login_ip);
        sb.append(", serialVersionUID=").append(serialVersionUID);
        sb.append("]");
        return sb.toString();
    }
}