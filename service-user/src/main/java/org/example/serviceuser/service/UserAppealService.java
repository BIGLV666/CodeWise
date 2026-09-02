package org.example.serviceuser.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.service.EmailService;
import org.example.servicecommon.until.UserContext;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.entry.UserAppeal;
import org.example.serviceuser.enums.UserAppealStatus;
import org.example.serviceuser.mapper.UserMapper;
import org.example.serviceuser.until.PasswordEncoding;
import org.example.serviceuser.vo.AppealVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class UserAppealService {
    @Autowired
    private org.example.serviceuser.mapper.UserAppealMapper userAppealMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private EmailService emailService;


    private User getCurrentUserOrThrow(String phoneOrEmail) {
        String type = phoneOrEmail.contains("@") ? "email" : "phone";
        return switch (type) {
            case "email" -> userMapper.selectOne(new QueryWrapper<User>().eq("email", phoneOrEmail));
            case "phone" -> userMapper.selectOne(new QueryWrapper<User>().eq("phone", phoneOrEmail));
            default -> throw new IllegalArgumentException("无效的用户标识");
        };
    }

    /**
     * 用户申诉提交入口，填写邮箱或者手机号，申诉理由，申诉邮箱，密码
     * @param phoneOrEmail 电话或者邮箱
     * @param appealReason 申诉理由
     * @param reasonEmail 申诉附件发送的邮箱
     * @param password 密码
     * @throws IllegalArgumentException 如果参数无效或用户不存在
     * @throws RuntimeException 如果创建申诉失败
     */
    public void createUserAppeal(String phoneOrEmail, String appealReason,String reasonEmail,String password) {
        if (phoneOrEmail == null || appealReason == null || appealReason.isBlank()) {
            throw new IllegalArgumentException("手机号或邮箱和申诉理由不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        User user = getCurrentUserOrThrow(phoneOrEmail);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        if (!PasswordEncoding.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("密码不正确");
        }
        if (user.getStatus() != 0) {
            throw new IllegalArgumentException("用户未被封禁，无需申诉");
        }
        org.example.serviceuser.entry.UserAppeal userAppeal = new org.example.serviceuser.entry.UserAppeal();

        userAppeal.setStatus(0); // 0-待处理
        userAppeal.setUserId(user.getUserId());
        userAppeal.setAppealReason(appealReason);
        userAppeal.setReasonEmail(reasonEmail);
        userAppeal.setStatus(0); // 0-待处理
        userAppeal.setCreateTime(java.time.LocalDateTime.now());
        userAppeal.setUpdateTime(java.time.LocalDateTime.now());
        try {
            int rowsInserted = userAppealMapper.insert(userAppeal);
            if (rowsInserted != 1) {
                throw new RuntimeException("创建用户申诉失败");
            }
        } catch (Exception e) {
            if (e instanceof org.springframework.dao.DuplicateKeyException) {
                throw new RuntimeException("您已提交过申诉，请耐心等待处理");
            } else {
                throw new RuntimeException("创建用户申诉失败");
            }

        }
    }


    public List<UserAppeal> listUserAppeals() {
         List<UserAppeal> userAppeals = userAppealMapper.selectList(new QueryWrapper<UserAppeal>().eq("status", UserAppealStatus.UNPROCESSED.getCode()).orderByDesc("create_time"));
         return userAppeals;
    }

    /**
     * 获取申诉对象
     * @param userAppealId 申诉记录ID
     */
    public AppealVo getUserAppealById(Long userAppealId) {
        UserAppeal userAppeal = userAppealMapper.selectById(userAppealId);
        if (userAppeal == null) {
            throw new IllegalArgumentException("申诉记录不存在");
        }
        User user = userMapper.selectById(userAppeal.getUserId());
        if (user == null) {
            throw new IllegalArgumentException("申诉用户不存在");
        }
        AppealVo appealVo = new AppealVo();
        appealVo.setUserAppealId(userAppeal.getUserAppealId());
        appealVo.setUserId(user.getUserId());
        appealVo.setBanTime(user.getBanTime());
        appealVo.setBanReason(user.getBanReason());
        appealVo.setAppealReason(userAppeal.getAppealReason());
        appealVo.setReasonEmail(userAppeal.getReasonEmail());
        return appealVo;
    }

    /**
     * 处理申诉结果
     * @param userAppealId 申诉记录ID
     * @param processResult 处理结果
     * @param approve 是否批准申诉，true-批准，false-拒绝
     * @throws IllegalArgumentException 如果申诉记录不存在或已处理，或申诉用户不存在
     * @throws RuntimeException 如果更新申诉记录或解封用户失败
     *
     */
    public void processUserAppeal(Long userAppealId, String processResult, boolean approve) {
        UserAppeal userAppeal = userAppealMapper.selectById(userAppealId);
        if (userAppeal == null) {
            throw new IllegalArgumentException("申诉记录不存在");
        }
        if (userAppeal.getStatus() != UserAppealStatus.UNPROCESSED.getCode()) {
            throw new IllegalArgumentException("申诉记录已处理");
        }
        User user = userMapper.selectById(userAppeal.getUserId());
        if (user == null) {
            throw new IllegalArgumentException("申诉用户不存在");
        }

        // 更新申诉记录：WHERE status=0 乐观锁防并发重复处理。
        int rowsUpdated = userAppealMapper.updateAppeal(
                UserAppealStatus.PROCESSED.getCode(),
                processResult,
                UserContext.getUserId(),
                LocalDateTime.now(),
                userAppeal.getUserAppealId());
        if (rowsUpdated != 1) {
            throw new RuntimeException("更新申诉记录失败,可能已被修改");
        }


        // 如果批准，解封用户
        if (approve) {
            user.setStatus(1); // 1-启用
            user.setBanTime(null);
            user.setBanReason(null);
            int rowsUserUpdated = userMapper.updateById(user);
            if (rowsUserUpdated != 1) {
                throw new RuntimeException("解封用户失败");
            }
        }

        emailService.sendEmail(user.getEmail(), "申诉处理结果", processResult);

    }
}
