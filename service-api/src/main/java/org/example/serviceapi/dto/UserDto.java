package org.example.serviceapi.dto;

import lombok.Data;


import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class UserDto {

    private String userId;
    //用户信息
    private String userName;

    private String email;
    private String phone;

    private String bio;//个人简介
    private String nickName;
    private String avatarUrl;//头像
    private LocalDate birthday;

    private Integer roleId;//1-普通用户 2-管理员
    private Integer status;//0-禁用 1-启用  2-已注销',

    //第三方登录
    private String openId;

    //oj
    private Long totalSubmit;//总提交;
    private Long totalAc;
    private Long rating;//竞赛评分

    //安全
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private String lastLoginIp;
    private LocalDateTime lastLoginTime;
    private LocalDateTime banTime;



}
