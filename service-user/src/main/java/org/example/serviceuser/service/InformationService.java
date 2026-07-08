package org.example.serviceuser.service;

import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.until.UserContext;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

@Service
@Slf4j
public class InformationService {

    @Autowired
    private UserMapper userMapper;

    //修改个人简介
    public UserDto updateBio(String bio){
        if (bio == null) {
            throw new IllegalArgumentException("个人简介不能为空");
        }
        if (bio.length() > 500) {
            throw new IllegalArgumentException("个人简介不能超过500个字符");
        }

        User user = getCurrentUserOrThrow();
        user.setBio(bio);
        user.setUpdateTime(LocalDateTime.now());
        int i= userMapper.updateById(user);
        if(i!=1){
            throw new RuntimeException("修改失败");
        }
        return new UserDto(user);
    }
    public UserDto updateBirthday(String birthday) {
        if (birthday == null || birthday.isBlank()) {
            throw new IllegalArgumentException("生日不能为空");
        }
        try {
            LocalDate parsedBirthday = LocalDate.parse(birthday);
            if(parsedBirthday.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("出生日期不能晚于当天");
            }
            User user = getCurrentUserOrThrow();
            user.setBirthday(parsedBirthday);
            user.setUpdateTime(LocalDateTime.now());
            int i = userMapper.updateById(user);
            if (i != 1) {
                throw new RuntimeException("修改失败");
            }
            return new UserDto(user);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("生日格式错误，请使用 yyyy-MM-dd");
        }
    }

    public UserDto updateNickName(String nickName) {
            if (nickName == null || nickName.isBlank()) {
                throw new IllegalArgumentException("昵称不能为空");
            }
            String trimmedNickName = nickName.trim();
            if (trimmedNickName.length() > 30) {
                throw new IllegalArgumentException("昵称不能超过30个字符");
            }

            User user = getCurrentUserOrThrow();
            user.setNickName(trimmedNickName);
            user.setUpdateTime(LocalDateTime.now());
            int i = userMapper.updateById(user);
            if (i != 1) {
                throw new RuntimeException("修改失败");
            }
            return new UserDto(user);
    }

    public String updateAvatar(String avatar) {
        if (avatar == null || avatar.isBlank()) {
            throw new IllegalArgumentException("头像地址不能为空");
        }
        User user = getCurrentUserOrThrow();
        String oldAvatar=user.getAvatarUrl();
        user.setUpdateTime(LocalDateTime.now());
        user.setAvatarUrl(avatar);
        int i = userMapper.updateById(user);
        if(i!=1){
            throw new RuntimeException("修改失败");
        }
        return  oldAvatar;
    }

    private Long getCurrentUserIdOrThrow() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("用户未登录或请求头缺少用户信息");
        }
        return userId;
    }

    private User getCurrentUserOrThrow() {
        User user = userMapper.selectById(getCurrentUserIdOrThrow());
        if(user==null){
            throw new RuntimeException("未找到该用户");
        }
        return user;
    }

}
