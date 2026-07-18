package org.example.serviceuser.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.servicecommon.service.EmailService;
import org.example.servicecommon.until.UserContext;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.UserMapper;
import org.example.serviceuser.until.JwtUntil;
import org.example.serviceuser.until.PasswordEncoding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service

public class UserService {
    @Autowired
    private EmailService  emailService;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JwtUntil jwtUntil;
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    private final static String USER_EMAIL_KEY ="user_email";
    private final static String USER_PHONE_KEY ="user_phone";

    /**
     * 生成6位随机验证码
     */
    private String getRegisterCode() {
        Random random = new Random();
        return String.format("%06d", random.nextInt(10000000));
    }


    public Map<String,Object> login(String username, String password){
        QueryWrapper<User> queryWrapper = new QueryWrapper<User>();
        queryWrapper.eq("user_name",username);

        User user=userMapper.selectOne(queryWrapper);
        if(user==null){
            throw new RuntimeException("未找到该用户");
        }
        if(!PasswordEncoding.matches(password,user.getPassword())){
            throw new RuntimeException("密码错误");
        }
        if(user.getStatus()==0){
            throw new RuntimeException("用户被封禁,解封时间为"+user.getBanTime());
        }

        //修改登录记录
        user.setLastLoginIp(UserContext.getIp());
        user.setLastLoginTime(LocalDateTime.now());

        UserDto userDto=new UserDto(user);

        Map<String,Object> map = new HashMap<>();
        map.put("user",userDto);
        map.put("token",jwtUntil.generateToken(user.getUserId(),username));
        return map;
    }

    //预注册
    public void email_regis(String email,String username,String password) {
        System.out.println(UserContext.getIp());
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_name",username));
        if(user!=null){
            throw new RuntimeException("用户名已存在");
        }
        user=userMapper.selectOne(new QueryWrapper<User>().eq("email",email));
        if(user!=null){
            throw new RuntimeException("该邮箱已注册请直接登录");
        }
        String code=getRegisterCode();
        User newuser = User.builder().email(email).userName(username).lastLoginIp(UserContext.getIp()).lastLoginTime(LocalDateTime.now()).password(PasswordEncoding.encode(password)).nickName(username).build();
        Map<String,Object> map = new HashMap<>();
        map.put("user",newuser);
        map.put("code",code);
        String key= USER_EMAIL_KEY +"--"+email;
        redisTemplate.opsForValue().set(key,map,5, TimeUnit.MINUTES);
        String contest=String.format("【CodeWise】验证码:%s 用于邮箱身份验证，5分钟内有效，请勿泄露和转发。如非本人操作，请忽略此邮件。",code);
        emailService.sendEmail(email,"欢迎注册CodeWise",contest);

    }

    //激活
    @SuppressWarnings("unchecked")
    public void register(String n,String code){
        try {

            String key = "";
            if (n.contains("@")) {
                key = USER_EMAIL_KEY + "--" + n;
            } else {
                key = USER_PHONE_KEY + "--" + n;
            }
            Map<String, Object> map = (Map<String, Object>) redisTemplate.opsForValue().get(key);
            System.out.println("5️⃣ map: " + map);
        if(map==null){
            throw new RuntimeException("验证码已过期或未找到验证码");
        }
        if(!map.get("code").equals(code)){
            throw new RuntimeException("验证码错误");
        }
        User user=(User) map.get("user");
        System.out.println(user);
        userMapper.insert(user);

        redisTemplate.delete(key);
    }catch (DuplicateKeyException d){
            throw new RuntimeException("该邮箱或手机号已被注册");
        }catch (Exception e){
            System.err.println("❌ 异常: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("注册失败请稍后重试");
        }
    }
    //修改密码
    public void updatePassword(String oldPassword,String newPassword){
        User user= userMapper.selectById(UserContext.getUserId());
        if(user==null){
            throw new RuntimeException("未找到该用户，请确认用户名是否正确");
        }
        if(!PasswordEncoding.matches(oldPassword,user.getPassword())){
            throw new RuntimeException("密码错误");
        }
        user.setPassword(PasswordEncoding.encode(newPassword));
        int i=userMapper.updateById(user);
        if(i!=1){
            throw new RuntimeException("密码修改失败");
        }
    }
    //找回密码
    public void updatePasswordForEmail(String email,String newPassword){
        User user= userMapper.selectOne(new QueryWrapper<User>().eq("email",email));
        if(user==null){
            throw new RuntimeException("该用户不存在");
        }
        String code=getRegisterCode();
        String key= USER_EMAIL_KEY +"--"+email;
        user.setPassword(PasswordEncoding.encode(newPassword));
        Map<String,Object> map =new HashMap<>();
        map.put("code",code);
        map.put("user",user);
        String contest=String.format("【CodeWise】验证码:%s 用于邮箱身份验证，5分钟内有效，请勿泄露和转发。如非本人操作，请忽略此邮件。",code);
        redisTemplate.opsForValue().set(key,map,5, TimeUnit.MINUTES);
        emailService.sendEmail(email,"CodeWise 安全验证",contest);
    }


    @SuppressWarnings("unchecked")
    public void updatePasswordForCode(String n, String code){
        String key= "";
        if(n.contains("@")){
            key= USER_EMAIL_KEY +"--"+n;
        } else  {
            key= USER_PHONE_KEY +"--"+n;
        }

        Map<String,Object> map =(Map<String, Object>) redisTemplate.opsForValue().get(key);
        if(map==null){
            throw new RuntimeException("验证码已过期或未找到验证码");
        }
        if(!map.get("code").equals(code)){
            throw new RuntimeException("验证码错误");
        }
        User user=(User) map.get("user");
        int r=userMapper.updateById(user);
        if(r!=1){
            throw new RuntimeException("修改失败");
        }
    }
    public UserDto getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new RuntimeException("未找到该用户");
        }
        return new UserDto(user);

    }
    //批量查寻用户
    public Map<Long, org.example.serviceapi.dto.user.UserDto> BatchSelectUser(List<Long> userIds) {
        List<User> users = userMapper.selectBatchIds(userIds);
        Map<Long, org.example.serviceapi.dto.user.UserDto> map = new HashMap<>();
        for (User user : users) {
            org.example.serviceapi.dto.user.UserDto userDto = new org.example.serviceapi.dto.user.UserDto();
            userDto.setAvatarUrl(user.getAvatarUrl());
            userDto.setNickName(user.getNickName());
            userDto.setPhone(user.getPhone());
            userDto.setEmail(user.getEmail());
            userDto.setBirthday(user.getBirthday());
            userDto.setCreateTime(user.getCreateTime());
            userDto.setUserName(user.getUserName());
            userDto.setTotalSubmit(user.getTotalSubmit());
            userDto.setTotalAc(user.getTotalAc());
            userDto.setRating(user.getRating());
            userDto.setUserId(user.getUserId().toString());
            map.put(user.getUserId(),userDto);
        }
        return map;
    }

}
