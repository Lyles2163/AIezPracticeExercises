package org.leon.commonjwt.pojo;

import java.io.Serializable;

/**
 * 泛型统一返回体。UserModule/AuthModule 原有的两份 Result 不动，
 * 只有 AIModule 使用这一份，避免扩大改动爆炸半径。
 */
public class Result<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    public Result() {
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = 1;
        result.msg = "success";
        result.data = data;
        return result;
    }

    public static <T> Result<T> error(String msg) {
        return error(0, msg);
    }

    public static <T> Result<T> error(int code, String msg) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
