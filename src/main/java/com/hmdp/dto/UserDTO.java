package com.hmdp.dto;

import lombok.Data;

/**
 * User Data Transfer Object
 * 用于用户数据脱敏
 */
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
