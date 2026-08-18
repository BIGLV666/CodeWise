package org.example.serviceuser.controller;

import org.example.apigovernancespringbootstarter.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/detail")
    @RateLimit(limit = 100, window = 60)
    public Result<UserDto> detail(@RequestParam Long userId) {
        return Result.success(userService.getUserById(userId));
    }
}

