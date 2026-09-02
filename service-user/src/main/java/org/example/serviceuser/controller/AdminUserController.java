package org.example.serviceuser.controller;


import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.entry.UserAppeal;
import org.example.serviceuser.service.UserAppealService;
import org.example.serviceuser.service.UserService;
import org.example.serviceuser.vo.AppealVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 类级示例：整个类都需要管理员身份。
 * 类上标注 {@link RequireAdmin} 后，类内所有接口在执行前都会先经过 AdminAuthAspect 校验，
 * 无需在每个方法上重复标注。
 */
@RequireAdmin
@RestController
@RequestMapping("/api/user/admin")
public class AdminUserController {

    @Autowired
    private UserService userService;

    @Autowired
    private UserAppealService userAppealService;

    @GetMapping("/detail")
    @RateLimit(limit = 100, window = 60)
    public Result<UserDto> detail(@RequestParam Long userId) {
        return Result.success(userService.getUserById(userId));
    }

    /** 封禁用户，banTime 单位为天。 */
    @PostMapping("/ban")
    @RateLimit(limit = 20, window = 60)
    public Result<String> ban(@RequestParam Long userId, @RequestParam Integer banTime) {
        userService.banUser(userId, banTime);
        return Result.success("success");
    }

    /** 解封用户。 */
    @PostMapping("/unban")
    @RateLimit(limit = 20, window = 60)
    public Result<String> unban(@RequestParam Long userId) {
        userService.unBanUser(userId);
        return Result.success("success");
    }

    /** 查询被封禁用户信息（不含邮箱、手机号等敏感字段）。 */
    @GetMapping("/ban/detail")
    @RateLimit(limit = 100, window = 60)
    public Result<UserDto> banDetail(@RequestParam Long userId) {
        return Result.success(userService.getBanUser(userId));
    }

    /** 查询所有未处理的申诉。 */
    @GetMapping("/appeal/list")
    @RateLimit(limit = 100, window = 60)
    public Result<List<UserAppeal>> appealList() {
        return Result.success(userAppealService.listUserAppeals());
    }

    /** 查询申诉详情（含申诉人封禁信息）。 */
    @GetMapping("/appeal/detail")
    @RateLimit(limit = 100, window = 60)
    public Result<AppealVo> appealDetail(@RequestParam Long userAppealId) {
        return Result.success(userAppealService.getUserAppealById(userAppealId));
    }

    /**
     * 处理申诉。
     * @param approve true-批准并解封用户，false-拒绝
     * @param processResult 处理结果说明，将作为邮件正文发送给申诉人
     */
    @PostMapping("/appeal/process")
    @RateLimit(limit = 20, window = 60)
    public Result<String> appealProcess(@RequestParam Long userAppealId,
                                        @RequestParam String processResult,
                                        @RequestParam boolean approve) {
        userAppealService.processUserAppeal(userAppealId, processResult, approve);
        return Result.success("success");
    }
}

