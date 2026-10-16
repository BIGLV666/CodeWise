package org.example.serviceuser.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.servicecommon.until.UserContext;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.exception.UserErrorCode;
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
import java.time.format.DateTimeFormatter;
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
    private final static int MAX_EMAIL_LENGTH = 254;
    /** 封禁提示中的解封时间展示格式，避免把 LocalDateTime 原始值透给用户。 */
    private final static DateTimeFormatter BAN_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
            throw new BizException(UserErrorCode.CODE_SEND_TOO_FREQUENT);
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
            throw new BizException(UserErrorCode.CODE_EXPIRED);
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
                throw new BizException(UserErrorCode.CODE_TOO_MANY_ATTEMPTS);
            }
            throw new BizException(UserErrorCode.CODE_INCORRECT);
        }
        redisTemplate.delete(key);
        redisTemplate.delete(attemptKey(scene, account));
        return payload;
    }

    /** 账号是否处于封禁状态，status 可能为 null，不能直接拆箱比较。 */
    private void ensureNotBanned(User user) {
        if (user.getStatus() != null && user.getStatus() == 0) {
            String unbanInfo = user.getBanTime() == null
                    ? "解封时间待定，请联系管理员"
                    : "预计解封时间为 " + user.getBanTime().format(BAN_TIME_FORMATTER);
            throw new BizException(UserErrorCode.ACCOUNT_BANNED, unbanInfo);
        }
    }


    /** 仅限制长度，保留邮箱原值及现有空值、格式处理行为。 */
    private void validateEmailLength(String email) {
        if (email != null && email.length() > MAX_EMAIL_LENGTH) {
            throw new BizException(UserErrorCode.EMAIL_TOO_LONG, MAX_EMAIL_LENGTH);
        }
    }

    public Map<String,Object> login(String username, String password){
        QueryWrapper<User> queryWrapper = new QueryWrapper<User>();
        if(username==null||password==null){
            throw new BizException(UserErrorCode.USERNAME_OR_PASSWORD_MISSING);
        }
        queryWrapper.eq("user_name",username);

        User user=userMapper.selectOne(queryWrapper);
        // 用户名优先；仅用户名不存在时尝试邮箱，密码错误不能切换到另一个账号。
        if (user == null) {
            validateEmailLength(username);
            user = userMapper.selectOne(new QueryWrapper<User>().eq("email", username));
        }
        // 账号不存在与密码错误返回同一文案，避免枚举有效账号
        if(user==null || !PasswordEncoding.matches(password,user.getPassword())){
            throw new BizException(UserErrorCode.LOGIN_FAILED);
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
    //邮箱and密码登录
    public  Map<String,Object>emailLogin(String email,String password){
        validateEmailLength(email);
        QueryWrapper<User> queryWrapper = new QueryWrapper<User>();
        if(email==null||password==null){
            throw new BizException(UserErrorCode.EMAIL_OR_PASSWORD_MISSING);
        }
        queryWrapper.eq("email",email);

        User user=userMapper.selectOne(queryWrapper);
        // 账号不存在与密码错误返回同一文案，避免枚举有效账号
        if(user==null || !PasswordEncoding.matches(password,user.getPassword())){
            throw new BizException(UserErrorCode.LOGIN_FAILED);
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
        validateEmailLength(email);
        if (email == null || email.isBlank()) {
            throw new BizException(UserErrorCode.EMAIL_MISSING);
        }
        User user=userMapper.selectOne(new QueryWrapper<User>().eq("email",email));
        if (user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
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
        validateEmailLength(email);
        Map<String,Object> payload = consumeCode(CodeScene.LOGIN, email, code);

        Object cachedUserId = payload.get("userId");
        User user = cachedUserId == null ? null : userMapper.selectById(Long.valueOf(String.valueOf(cachedUserId)));
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
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
        validateEmailLength(email);
        System.out.println(UserContext.getIp());
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_name",username));
        if(user!=null){
            throw new BizException(UserErrorCode.USERNAME_TAKEN);
        }
        user=userMapper.selectOne(new QueryWrapper<User>().eq("email",email));
        if(user!=null){
            throw new BizException(UserErrorCode.EMAIL_TAKEN);
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
        validateEmailLength(n);
        Map<String,Object> payload = consumeCode(CodeScene.REGISTER, n, code);
        String email = String.valueOf(payload.get("email"));
        validateEmailLength(email);
        User user = User.builder()
                .email(email)
                .userName(String.valueOf(payload.get("userName")))
                .nickName(String.valueOf(payload.get("userName")))
                .password(String.valueOf(payload.get("password")))
                .lastLoginIp(UserContext.getIp())
                .lastLoginTime(LocalDateTime.now())
                .build();
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException d) {
            throw new BizException(UserErrorCode.REGISTER_ACCOUNT_TAKEN, d);
        }
    }
    //修改密码
    public void updatePassword(String oldPassword,String newPassword){
        User user= userMapper.selectById(UserContext.getUserId());
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        if(!PasswordEncoding.matches(oldPassword,user.getPassword())){
            throw new BizException(UserErrorCode.PASSWORD_INCORRECT);
        }
        user.setPassword(PasswordEncoding.encode(newPassword));
        int i=userMapper.updateById(user);
        if(i!=1){
            throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
        }
    }
    /**
     * 找回密码第一步：仅发送验证码。
     * 新密码不在此步提交，避免未持有验证码的人为任意邮箱暂存一次改密。
     */
    public void updatePasswordForEmail(String email,String newPassword){
        validateEmailLength(email);
        User user= userMapper.selectOne(new QueryWrapper<User>().eq("email",email));
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
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
        validateEmailLength(n);
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException(UserErrorCode.PASSWORD_TOO_SHORT, 6);
        }
        Map<String,Object> payload = consumeCode(CodeScene.RESET, n, code);

        Object cachedUserId = payload.get("userId");
        User user = cachedUserId == null ? null : userMapper.selectById(Long.valueOf(String.valueOf(cachedUserId)));
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        user.setPassword(PasswordEncoding.encode(newPassword));
        int r=userMapper.updateById(user);
        if(r!=1){
            throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
        }
    }
    public UserDto getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
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
     */
    public void banUser(Long userId,Integer banTime){
        // TODO(安全): 管理员封禁他人时不会作废对方已签发的 token。
        //  现有的 logout_token:<token> 黑名单按 token 原文匹配，而这里拿到的是 userId，
        //  服务端没有 token -> 用户的索引，所以复用不了 blacklistCurrentToken（它作废的是
        //  请求者自己的凭证，用在这里会把管理员自己踢下线）。
        //  要覆盖需要按用户维度记录失效时间戳，例如 user_token_invalid_before:<userId> = now，
        //  由 UserAuthInterceptor 比对 JWT 的 iat（iat <= 该时间戳即视为已失效，同时天然支持
        //  "改密码后其它会话全部下线"）。在那之前，被封禁用户在 token 自然过期前仍可继续调用接口。
        LocalDateTime banUntil = LocalDateTime.now().plusDays(banTime);
        int userStatus = 0; // 0 表示封禁状态
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        if(user.getStatus()==2){
            throw new BizException(UserErrorCode.USER_CANCELLED);
        }
        user.setStatus(userStatus);
        user.setUpdateTime(LocalDateTime.now());
        user.setBanTime(banUntil);
        int r=userMapper.updateById(user);
        if(r==0){
            throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
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
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        if(user.getStatus()==2){
            throw new BizException(UserErrorCode.USER_CANCELLED);
        }
        // 先作废旧凭证，再落库状态变更：JWT 无状态，网关只验签名不看账号状态，
        // 只有黑名单写成功，"账号已注销"才真的等于"旧 token 立即失效"。
        // 若顺序反过来，一旦黑名单写失败就会留下「账号已注销但旧 token 还能用」的窗口；
        // 现在最坏情况只是状态更新失败、用户需要重新登录一次。
        blacklistCurrentToken(UserErrorCode.SESSION_REVOKE_FAILED);
        user.setStatus(2);
        user.setUpdateTime(LocalDateTime.now());
        int r=userMapper.updateById(user);
        if(r==0){
            throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
        }
    }

    /**
     * 用户主动冻结账号，需要密码验证，设置 status 为 1，表示冻结状态。
     * 仅允许用户本人操作，管理员无法直接冻结其他用户。
     * 冻结账号后，用户无法登录，直到解冻。
     * 该操作可逆，用户可以通过找回密码或联系管理员解冻
     * @param banReason 冻结原因
     * @param password 用户密码，用于验证身份
     */
    public void freezeUser(String banReason,String password){
        if(banReason==null||banReason.isBlank()){
            throw new BizException(UserErrorCode.FREEZE_REASON_MISSING);
        }
        if(password==null||password.isBlank()){
            throw new BizException(UserErrorCode.PASSWORD_MISSING);
        }
        Long userId = UserContext.getUserId();
        User user=userMapper.selectById(userId);
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        if(!PasswordEncoding.matches(password,user.getPassword())){
            throw new BizException(UserErrorCode.PASSWORD_INCORRECT);
        }
        // 密码核验通过后才作废旧凭证（校验失败不该把人踢下线），
        // 且先作废再落库：账号状态变更与凭证失效保持一致，
        // 不留「已冻结但旧 token 还能用」的窗口，理由同 deleteUser。
        blacklistCurrentToken(UserErrorCode.SESSION_REVOKE_FAILED);
        user.setStatus(0);

        user.setUpdateTime(LocalDateTime.now());
        user.setBanTime(LocalDateTime.now().plusDays(3600));
        user.setBanReason(banReason);
        int r=userMapper.updateById(user);
        if(r==0){
            throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
        }
        userMailService.sendFreezeMail(user);
    }


    /**
     * 用户冻结解除，需要管理员操作，设置 status 为 1，表示正常状态。
     * 仅允许管理员操作，用户无法直接解冻其他用户。
     * 该操作可逆，管理员可以通过冻结操作再次冻结用户账号。
     * @param userId 操作对象
     */
    public void unBanUser(Long userId){
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        if(user.getStatus()!=0){
            throw new BizException(UserErrorCode.USER_NOT_FROZEN);
        }
        user.setStatus(1);
        user.setUpdateTime(LocalDateTime.now());
        user.setBanTime(null);
        user.setBanReason(null);
        int r=userMapper.updateById(user);
        if(r==0){
            throw new BizException(CommonErrorCode.DATA_OPERATION_FAILED);
        }
        userMailService.sendUnbanMail(user);
    }

    /**
     * 获取被封禁的用户信息，返回 UserDto 对象。
     * 仅允许管理员操作，用户无法直接查询其他用户的封禁信息。
     * 不包含敏感信息，如密码、邮箱等。
     * @param userId 查询对象
     */
    public UserDto getBanUser(Long userId) {
        User user = userMapper.selectById(userId);
        if(user==null){
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        UserDto userDto = new UserDto(user);
        userDto.setEmail(null);
        userDto.setPhone(null);
        return userDto;
    }

    /**
     * <P>用户退出登录，因为为jwt无状态token，所以不支持天然踢出，
     * 在此使用redis存放退出token，token有效期为7天，所以我们ttl需要大于7,
     * 否则可能出现用户退出登录前端清除token但是还可以拿旧token登录的情况。
     * </P>
     */
    public void logout(){
        blacklistCurrentToken(UserErrorCode.LOGOUT_FAILED);
    }

    /**
     * 把当前请求携带的凭证写入黑名单，使其立即失效。
     *
     * <p>退出登录、冻结账号、注销账号都必须走这一步：JWT 是无状态的，网关只验签名、
     * 不看账号状态，真正让旧 token 失效的是 {@code UserAuthInterceptor} 每次请求
     * 对 {@code logout_token:<token>} 的检查，所以只有写进黑名单，旧凭证才真的作废。</p>
     *
     * <p>没有单独的下线广播，也没有把 key 存进数据库：这里刻意复用登出同一套机制，
     * 保证三个场景的失效语义完全一致（同样是 7 天 TTL，同样按原 token 原文匹配）。</p>
     *
     * @param persistFailedCode 写入黑名单失败时抛出的业务码（各场景面向用户的文案不同）
     */
    private void blacklistCurrentToken(UserErrorCode persistFailedCode){
        Long userId=UserContext.getUserId();
        String token=UserContext.getToken();

        //网关注入的token，其实已经解析过一次，以防万一在此再解析一次
        Long user;
        try {
            var body= jwtUntil.parseToken(token);
            //jwt解析出的id
            user = Long.parseLong(body.getSubject());
        }catch (Exception e){
            //token 缺失、过期、签名不符或被篡改都归为凭证无效，原始异常只进日志
            log.warn("解析待作废凭证失败: {}",e.getMessage(),e);
            throw new BizException(CommonErrorCode.TOKEN_INVALID, e);
        }

        //token 身份与当次请求身份不一致：凭证不可信，同样按凭证无效处理
        if(!user.equals(userId)){
            throw new BizException(CommonErrorCode.TOKEN_INVALID);
        }

        //把token添加到redis做持久化
        try {
            String key = RedisContext.LOGOUT_TOKEN + ":" + token;
            redisTemplate.opsForValue().set(key, token, 7, TimeUnit.DAYS);
        }catch (Exception e){
            log.error("凭证写入黑名单失败{}",e.getMessage(),e);
            throw new BizException(persistFailedCode, e);
        }
    }


}
