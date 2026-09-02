package org.example.serviceuser.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.example.servicecommon.aop.RequireAdmin;
import org.example.servicecommon.until.UserContext;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.UserMapper;
import org.example.serviceuser.until.JwtUntil;
import org.example.serviceuser.until.PasswordEncoding;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service

public class UserService {
    @Autowired
    private UserMailService userMailService;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JwtUntil jwtUntil;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    private final static String USER_CODE_KEY = "user_code";
    private final static String USER_CODE_COOLDOWN_KEY = "user_code_cooldown";
    private final static String USER_CODE_ATTEMPT_KEY = "user_code_attempt";
    /** 验证码有效期，与邮件正文中的说明保持一致。 */
    private final static long CODE_TTL_MINUTES = 5;
    /** 同一场景下两次发码的最小间隔，防止邮件轰炸。 */
    private final static long SEND_COOLDOWN_SECONDS = 60;
    /** 单个验证码允许的最大试错次数，超过后作废，避免被暴力枚举。 */
    private final static long MAX_VERIFY_ATTEMPTS = 5;
    private final static SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 验证码使用场景。注册、验证码登录、找回密码各自独立存储，
     * 否则共用一个 Redis key 会导致某个场景的验证码被其他场景消费。
     */
    private enum CodeScene {
        REGISTER,
        LOGIN,
        RESET
    }

    private String codeKey(CodeScene scene, String account) {
        return USER_CODE_KEY + ":" + scene.name().toLowerCase() + "--" + account;
    }

    private String cooldownKey(CodeScene scene, String account) {
        return USER_CODE_COOLDOWN_KEY + ":" + scene.name().toLowerCase() + "--" + account;
    }

    private String attemptKey(CodeScene scene, String account) {
        return USER_CODE_ATTEMPT_KEY + ":" + scene.name().toLowerCase() + "--" + account;
    }

    /**
     * 生成6位随机验证码。
     * 上界必须是 1_000_000，否则 %06d 无法截断更大的数值，会产生 7 位验证码。
     */
    private String getRegisterCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    /** 发码限流：冷却期内重复请求直接拒绝。 */
    private void ensureSendAllowed(CodeScene scene, String account) {
        Boolean allowed = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey(scene, account), "1", SEND_COOLDOWN_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new RuntimeException("验证码发送过于频繁，请稍后再试");
        }
    }

    /** 缓存验证码载荷，并重置该场景的试错计数。 */
    private void saveCode(CodeScene scene, String account, Map<String, Object> payload) {
        redisTemplate.opsForValue().set(codeKey(scene, account), payload, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.delete(attemptKey(scene, account));
    }

    /**
     * 校验并消费验证码，成功后立即删除，保证一码一用。
     *
     * @return 发码时缓存的载荷
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> consumeCode(CodeScene scene, String account, String code) {
        String key = codeKey(scene, account);
        Map<String, Object> payload = (Map<String, Object>) redisTemplate.opsForValue().get(key);
        if (payload == null) {
            throw new RuntimeException("验证码已过期或未找到验证码");
        }
        String expected = String.valueOf(payload.get("code"));
        if (code == null || !expected.equals(code.trim())) {
            String attemptKey = attemptKey(scene, account);
            Long attempts = redisTemplate.opsForValue().increment(attemptKey);
            if (attempts != null && attempts == 1L) {
                redisTemplate.expire(attemptKey, CODE_TTL_MINUTES, TimeUnit.MINUTES);
            }
            if (attempts != null && attempts >= MAX_VERIFY_ATTEMPTS) {
                redisTemplate.delete(key);
                redisTemplate.delete(attemptKey);
                throw new RuntimeException("验证码错误次数过多，请重新获取验证码");
            }
            throw new RuntimeException("验证码错误");
        }
        redisTemplate.delete(key);
        redisTemplate.delete(attemptKey(scene, account));
        return payload;
    }

    /** 账号是否处于封禁状态，status 可能为 null，不能直接拆箱比较。 */
    private void ensureNotBanned(User user) {
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new RuntimeException("用户被封禁,解封时间为" + user.getBanTime());
        }
    }


    public Map<String,Object> login(String username, String password){
        QueryWrapper<User> queryWrapper = new QueryWrapper<User>();
        if(username==null||password==null){
            throw new IllegalArgumentException("用户名不为空");
        }
        queryWrapper.eq("user_name",username);

        User user=userMapper.selectOne(queryWrapper);
        if(user==null){
            throw new RuntimeException("未找到该用户");
        }
        if(!PasswordEncoding.matches(password,user.getPassword())){
            throw new RuntimeException("密码错误");
        }
        ensureNotBanned(user);

        //修改登录记录
        user.setLastLoginIp(UserContext.getIp());
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        UserDto userDto=new UserDto(user);

        Map<String,Object> map = new HashMap<>();
        map.put("user",userDto);
        map.put("token",jwtUntil.generateToken(user.getUserId(),username));
        return map;
    }
    //邮箱and密码登录
    public  Map<String,Object>emailLogin(String email,String password){
        QueryWrapper<User> queryWrapper = new QueryWrapper<User>();
        if(email==null||password==null){
            throw new IllegalArgumentException("邮箱或密码不为空");
        }
        queryWrapper.eq("email",email);

        User user=userMapper.selectOne(queryWrapper);
        if(user==null){
            throw new RuntimeException("未找到该用户");
        }
        if(!PasswordEncoding.matches(password,user.getPassword())){
            throw new RuntimeException("密码错误");
        }
        ensureNotBanned(user);

        //修改登录记录
        user.setLastLoginIp(UserContext.getIp());
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);


        UserDto userDto=new UserDto(user);

        Map<String,Object> map = new HashMap<>();
        map.put("user",userDto);
        map.put("token",jwtUntil.generateToken(user.getUserId(),user.getUserName()));
        return map;
    }

    //验证码登录


    /**
     * 发送登录验证码。
     * 只缓存 userId，登录时再回库查询：缓存实体会导致反序列化类型与写入类型强耦合，
     * 也会让期间的账号变更（改密、封禁）在登录时被忽略。
     */
    public String getEmailCode(String email){
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        User user=userMapper.selectOne(new QueryWrapper<User>().eq("email",email));
        if (user==null){
            throw new IllegalArgumentException("未找到该用户");
        }
        ensureNotBanned(user);
        ensureSendAllowed(CodeScene.LOGIN, email);

        String code = getRegisterCode();
        Map<String,Object> payload = new HashMap<>();
        payload.put("code",code);
        payload.put("userId",user.getUserId());
        saveCode(CodeScene.LOGIN, email, payload);
        userMailService.sendCodeMail(email, code, CODE_TTL_MINUTES);
        return "success";
    }

    public Map<String,Object> emailLoginForCode(String email,String code){
        Map<String,Object> payload = consumeCode(CodeScene.LOGIN, email, code);

        Object cachedUserId = payload.get("userId");
        User user = cachedUserId == null ? null : userMapper.selectById(Long.valueOf(String.valueOf(cachedUserId)));
        if(user==null){
            throw new RuntimeException("未找到该用户");
        }
        ensureNotBanned(user);

        //修改登录记录
        user.setLastLoginIp(UserContext.getIp());
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        UserDto userDto=new UserDto(user);

        Map<String,Object> result = new HashMap<>();
        result.put("user",userDto);
        result.put("token",jwtUntil.generateToken(user.getUserId(),user.getUserName()));
        return result;
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
        ensureSendAllowed(CodeScene.REGISTER, email);

        String code=getRegisterCode();
        Map<String,Object> payload = new HashMap<>();
        payload.put("code",code);
        payload.put("email",email);
        payload.put("userName",username);
        payload.put("password",PasswordEncoding.encode(password));
        saveCode(CodeScene.REGISTER, email, payload);
        userMailService.sendCodeMail(email, code, CODE_TTL_MINUTES);
    }

    //激活
    public void register(String n,String code){
        Map<String,Object> payload = consumeCode(CodeScene.REGISTER, n, code);
        User user = User.builder()
                .email(String.valueOf(payload.get("email")))
                .userName(String.valueOf(payload.get("userName")))
                .nickName(String.valueOf(payload.get("userName")))
                .password(String.valueOf(payload.get("password")))
                .lastLoginIp(UserContext.getIp())
                .lastLoginTime(LocalDateTime.now())
                .build();
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException d) {
            throw new RuntimeException("该邮箱或手机号已被注册");
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
    /**
     * 找回密码第一步：仅发送验证码。
     * 新密码不在此步提交，避免未持有验证码的人为任意邮箱暂存一次改密。
     */
    public void updatePasswordForEmail(String email,String newPassword){
        User user= userMapper.selectOne(new QueryWrapper<User>().eq("email",email));
        if(user==null){
            throw new RuntimeException("该用户不存在");
        }
        ensureSendAllowed(CodeScene.RESET, email);

        String code=getRegisterCode();
        Map<String,Object> payload =new HashMap<>();
        payload.put("code",code);
        payload.put("userId",user.getUserId());
        saveCode(CodeScene.RESET, email, payload);
        userMailService.sendCodeMail(email, code, CODE_TTL_MINUTES);
    }

    /**
     * 找回密码第二步：校验验证码并写入新密码。
     * 新密码在这一步随验证码一起提交，两者缺一不可。
     */
    public void updatePasswordForCode(String n, String code, String newPassword){
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码长度不能少于6位");
        }
        Map<String,Object> payload = consumeCode(CodeScene.RESET, n, code);

        Object cachedUserId = payload.get("userId");
        User user = cachedUserId == null ? null : userMapper.selectById(Long.valueOf(String.valueOf(cachedUserId)));
        if(user==null){
            throw new RuntimeException("该用户不存在");
        }
        user.setPassword(PasswordEncoding.encode(newPassword));
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
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userMapper.selectBatchIds(userIds);
        Map<Long, org.example.serviceapi.dto.user.UserDto> map = new HashMap<>();
        for (User user : users) {
            if (user.getUserId() == null) {
                continue;
            }
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

    @AsyncHandler(value = "user-login",phase = AsyncPhase.AFTER_SUCCESS)
    public void sendWelcomeMail( AsyncEvent event){
        Long userId = (Long) event.data().get("userId");
        if(userId==null){
            log.warn("Skip login notification because userId is missing: eventId={}", event.id());
            return;
        }
        User user = userMapper.selectById(userId);
        if(user==null){
            log.warn("Skip login notification because user does not exist: userId={}", userId);
            return;
        }
        userMailService.sendLoginNotification(event, user);

    }

    /**
     * 管理员冻结用户账号，设置 status 为 0，表示封禁状态。
     * 仅允许管理员操作，用户无法直接冻结其他用户。
     * 该操作可逆，管理员可以通过解封操作恢复用户账号。
     * @param userId 冻结用户id
     * @param banTime 冻结时间，单位为天
     * @throws IllegalArgumentException 如果参数不合法或操作失败，抛出异常
     */
    public void banUser(Long userId,Integer banTime){
        LocalDateTime banUntil = LocalDateTime.now().plusDays(banTime);
        int userStatus = 0; // 0 表示封禁状态
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new IllegalArgumentException("未找到该用户");
        }
        if(user.getStatus()==2){
            throw new IllegalArgumentException("该用户已注销");
        }
        user.setStatus(userStatus);
        user.setUpdateTime(LocalDateTime.now());
        user.setBanTime(banUntil);
        int r=userMapper.updateById(user);
        if(r==0){
            throw new IllegalArgumentException("修改状态失败");
        }
        userMailService.sendBanMail(user);

    }

    /**
     * 用户注销账号,将 status 设置为 2，表示已注销。
     * 仅允许用户本人操作，管理员无法直接注销其他用户。
     * 该操作可逆。但是不支持恢复已注销的用户数据，用户需要重新注册。
     */
    public void deleteUser(){
        Long userId = UserContext.getUserId();
        if(userId==null){
            throw new IllegalArgumentException("用户ID不能为空");
        }
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new IllegalArgumentException("未找到该用户");
        }
        if(user.getStatus()==2){
            throw new IllegalArgumentException("该用户已注销");
        }
        user.setStatus(2);
        user.setUpdateTime(LocalDateTime.now());
        int r=userMapper.updateById(user);
        if(r==0){
            throw new IllegalArgumentException("删除用户失败");
        }
    }

    /**
     * 用户主动冻结账号，需要密码验证，设置 status 为 1，表示冻结状态。
     * 仅允许用户本人操作，管理员无法直接冻结其他用户。
     * 冻结账号后，用户无法登录，直到解冻。
     * 该操作可逆，用户可以通过找回密码或联系管理员解冻
     * @param banReason 冻结原因
     * @param password 用户密码，用于验证身份
     * @throws IllegalArgumentException 如果参数不合法或操作失败，抛出异常
     */
    public void freezeUser(String banReason,String password){
        if(banReason==null||banReason.isBlank()){
            throw new IllegalArgumentException("冻结原因不能为空");
        }
        if(password==null||password.isBlank()){
            throw new IllegalArgumentException("密码不能为空");
        }
        Long userId = UserContext.getUserId();
        User user=userMapper.selectById(userId);
        if(user==null){
            throw new IllegalArgumentException("未找到该用户");
        }
        if(!PasswordEncoding.matches(password,user.getPassword())){
            throw new IllegalArgumentException("密码错误");
        }
        user.setStatus(0);

        user.setUpdateTime(LocalDateTime.now());
        user.setBanTime(LocalDateTime.now().plusDays(3600));
        user.setBanReason(banReason);
        int r=userMapper.updateById(user);
        if(r==0){
            throw new IllegalArgumentException("冻结用户失败");
        }
        userMailService.sendFreezeMail(user);
    }


    /**
     * 用户冻结解除，需要管理员操作，设置 status 为 1，表示正常状态。
     * 仅允许管理员操作，用户无法直接解冻其他用户。
     * 该操作可逆，管理员可以通过冻结操作再次冻结用户账号。
     * @throws IllegalArgumentException 如果参数不合法或操作失败，抛出异常
     * @param userId 操作对象
     */
    public void unBanUser(Long userId){
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new IllegalArgumentException("未找到该用户");
        }
        if(user.getStatus()!=0){
            throw new IllegalArgumentException("该用户未被冻结");
        }
        user.setStatus(1);
        user.setUpdateTime(LocalDateTime.now());
        user.setBanTime(null);
        user.setBanReason(null);
        int r=userMapper.updateById(user);
        if(r==0){
            throw new IllegalArgumentException("解封用户失败");
        }
        userMailService.sendUnbanMail(user);
    }

    /**
     * 获取被封禁的用户信息，返回 UserDto 对象。
     * 仅允许管理员操作，用户无法直接查询其他用户的封禁信息。
     * 不包含敏感信息，如密码、邮箱等。
     * @throws IllegalArgumentException 如果参数不合法或操作失败，抛出异常
     * @param userId 查询对象
     */
    public UserDto getBanUser(Long userId) {
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new RuntimeException("未找到该用户");
        }
        UserDto userDto = new UserDto(user);
        userDto.setEmail(null);
        userDto.setPhone(null);
        return userDto;
    }


}
