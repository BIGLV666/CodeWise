package org.example.serviceuser.service;

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

@Service
public class InformationService {
    Logger logger = LoggerFactory.getLogger(InformationService.class);
    @Autowired
    private UserMapper userMapper;

    //修改个人简介
    public UserDto updateBio(String bio){
        try{
        User user=userMapper.selectById(UserContext.getUserId());
        if(user==null){
            throw new RuntimeException("未找到该用户");
        }
        user.setBio(bio);
        int i= userMapper.updateById(user);
        if(i!=1){
            throw new RuntimeException("修改失败");
        }
        return new UserDto(user);
    }catch(RuntimeException e){
            throw  new RuntimeException(e.getMessage());
        }
    }
    public UserDto updateBirthday(String birthday) {
        try {
            User user = userMapper.selectById(UserContext.getUserId());
            if(user==null){
                throw new RuntimeException("未找到该用户");
            }
            user.setBirthday(LocalDate.parse(birthday));
            int i = userMapper.updateById(user);
            if (i != 1) {
                throw new RuntimeException("修改失败");
            }
            return new UserDto(user);
        } catch (RuntimeException e) {
            throw new RuntimeException("修改失败，请查看日期是否正确");
        }
    }

    public UserDto updateNickName(String nickName) {

            User user = userMapper.selectById(UserContext.getUserId());
            if(user==null){
                throw new RuntimeException("未找到该用户");
            }
            user.setNickName(nickName);
            int i = userMapper.updateById(user);
            if (i != 1) {
                throw new RuntimeException("修改失败");
            }
            return new UserDto(user);
    }

    public String updateAvatar(String avatar) {
        User user = userMapper.selectById(UserContext.getUserId());
        if(user==null){
            throw new RuntimeException("未找到该用户");
        }
        String oldAvatar=user.getAvatarUrl();
        user.setUpdateTime(LocalDateTime.now());
        user.setAvatarUrl(avatar);
        int i = userMapper.updateById(user);
        if(i!=1){
            throw new RuntimeException("修改失败");
        }
        return  oldAvatar;
    }

}
