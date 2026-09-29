package org.example.serviceuser.service;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.until.UserContext;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.exception.UserErrorCode;
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
            throw new BizException(UserErrorCode.BIO_EMPTY);
        }
        if (bio.length() > 500) {
            throw new BizException(UserErrorCode.BIO_TOO_LONG, 500);
        }

        User user = getCurrentUserOrThrow();
        user.setBio(bio);
        user.setUpdateTime(LocalDateTime.now());
        int i= userMapper.updateById(user);
        if(i!=1){
            throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
        }
        return new UserDto(user);
    }
    public UserDto updateBirthday(String birthday) {
        if (birthday == null || birthday.isBlank()) {
            throw new BizException(UserErrorCode.BIRTHDAY_MISSING);
        }
        try {
            LocalDate parsedBirthday = LocalDate.parse(birthday);
            if(parsedBirthday.isAfter(LocalDate.now())) {
                throw new BizException(UserErrorCode.BIRTHDAY_FUTURE);
            }
            User user = getCurrentUserOrThrow();
            user.setBirthday(parsedBirthday);
            user.setUpdateTime(LocalDateTime.now());
            int i = userMapper.updateById(user);
            if (i != 1) {
                throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
            }
            return new UserDto(user);
        } catch (DateTimeParseException e) {
            throw new BizException(UserErrorCode.BIRTHDAY_FORMAT_INVALID, e);
        }
    }

    public UserDto updateNickName(String nickName) {
            if (nickName == null || nickName.isBlank()) {
                throw new BizException(UserErrorCode.NICKNAME_MISSING);
            }
            String trimmedNickName = nickName.trim();
            if (trimmedNickName.length() > 30) {
                throw new BizException(UserErrorCode.NICKNAME_TOO_LONG, 30);
            }

            User user = getCurrentUserOrThrow();
            user.setNickName(trimmedNickName);
            user.setUpdateTime(LocalDateTime.now());
            int i = userMapper.updateById(user);
            if (i != 1) {
                throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
            }
            return new UserDto(user);
    }

    public String updateAvatar(String avatar) {
        if (avatar == null || avatar.isBlank()) {
            throw new BizException(UserErrorCode.AVATAR_MISSING);
        }
        User user = getCurrentUserOrThrow();
        String oldAvatar=user.getAvatarUrl();
        user.setUpdateTime(LocalDateTime.now());
        user.setAvatarUrl(avatar);
        int i = userMapper.updateById(user);
        if(i!=1){
            throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
        }
        return  oldAvatar;
    }

    private Long getCurrentUserIdOrThrow() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        return userId;
    }

    private User getCurrentUserOrThrow() {
        User user = userMapper.selectById(getCurrentUserIdOrThrow());
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

}
