package org.example.serviceuser.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.example.serviceuser.entry.User;

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

    public UserDto(User user) {
        this.userId = user.getUserId().toString();
        this.userName = user.getUserName();
        this.email = user.getEmail();
        this.phone = user.getPhone();
        this.bio = user.getBio();
        this.nickName = user.getNickName();
        this.avatarUrl = user.getAvatarUrl();
        this.birthday = user.getBirthday();
        this.roleId = user.getRoleId();
        this.status = user.getStatus();
        this.openId = user.getOpenId();
        this.totalSubmit = user.getTotalSubmit();
        this.totalAc = user.getTotalAc();
        this.rating = user.getRating();
        this.createTime = user.getCreateTime();
        this.updateTime = user.getUpdateTime();
        this.lastLoginIp = user.getLastLoginIp();
        this.lastLoginTime = user.getLastLoginTime();
        this.banTime = user.getBanTime();

    }

}
