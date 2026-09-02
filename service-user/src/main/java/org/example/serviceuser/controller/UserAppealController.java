package org.example.serviceuser.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.serviceuser.service.UserAppealService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户申诉接口。
 * <p>被封禁用户无法登录获取 token，故提交申诉为匿名接口，
 * 通过「手机号/邮箱 + 密码」验证身份（路径已在网关与身份拦截器白名单中放行）。</p>
 */
@RestController
@RequestMapping("/api/user/appeal")
public class UserAppealController {

    @Autowired
    private UserAppealService userAppealService;

    /**
     * 提交申诉。
     *
     * @param phoneOrEmail 手机号或邮箱（用于定位账号）
     * @param appealReason 申诉理由
     * @param reasonEmail  接收补充材料的邮箱，可选
     * @param password     账号密码，用于验证身份
     */
    @PostMapping("/submit")
    @RateLimit(limit = 5, window = 60)
    public Result<String> submit(@RequestParam String phoneOrEmail,
                                 @RequestParam String appealReason,
                                 @RequestParam(required = false) String reasonEmail,
                                 @RequestParam String password) {
        userAppealService.createUserAppeal(phoneOrEmail, appealReason, reasonEmail, password);
        return Result.success("success");
    }
}
