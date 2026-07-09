package org.example.serviceuser.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor   // ← 加上这个
@AllArgsConstructor
public class User {
    @TableId(value = "user_id",type = IdType.AUTO)
    private Long userId;
    //用户信息
    private String userName;
    private String password;
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
