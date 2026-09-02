package org.example.serviceuser.controller;


import io.github.biglv666.apigovernance.annotation.RateLimit;

import io.github.biglv666.apigovernance.async.annotation.AsyncAction;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.service.UserService;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {
    @Autowired
    private UserService userService;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @GetMapping("/hello")
    public String hello(){
        return "hello";
    }
    @PostMapping("/emailregister")
    @RateLimit(limit = 20, window = 60)
    public Result<String> emailRegister(@RequestParam String email, @RequestParam String password, @RequestParam String username){
        userService.email_regis(email,username,password);
        return Result.success("success");
    }
    @PostMapping("/register")
    @RateLimit(limit = 30, window = 60)
    public Result<String>register(@RequestParam String number, @RequestParam String code){
        userService.register(number,code);
        return Result.success("success");
    }
    @PostMapping("/updatepassword")
    @RateLimit(limit = 5, window = 60)
    public Result<String>updatePassword(@RequestParam String oldPassword, @RequestParam String newPassword){
        userService.updatePassword(oldPassword,newPassword);
        return Result.success("success");
    }
    @PostMapping("/login")
    @RateLimit(limit = 60, window = 60)
    @AsyncAction(value = "user-login")
    public Result<Map<String,Object>> login(@RequestParam String username, @RequestParam String password){
        return  Result.success(userService.login(username,password));
    }

    @PostMapping("/emaillogin")
    @RateLimit(limit = 60, window = 60)
    public Result<Map<String,Object>> emailLogin(@RequestParam String email, @RequestParam String password){
        return Result.success(userService.emailLogin(email,password));
    }

    @PostMapping("/getemailcode")
    @RateLimit(limit = 20, window = 60)
    public Result<String> getEmailCode(@RequestParam String email){
        return Result.success(userService.getEmailCode(email));
    }

    @PostMapping("/emailloginforcode")
    @RateLimit(limit = 60, window = 60)
    public Result<Map<String,Object>> emailLoginForCode(@RequestParam String email, @RequestParam String code){
        return Result.success(userService.emailLoginForCode(email,code));
    }

    /**
     * 找回密码第一步：发送验证码。
     * password 已不再于此步生效，保留入参仅为兼容旧客户端。
     */
    @PostMapping("/updatepasswordforemail")
    @RateLimit(limit = 20, window = 60)
    public Result<String> updatePasswordForEmail(@RequestParam String email,
                                                 @RequestParam(required = false) String password){
        userService.updatePasswordForEmail(email,password);
        return Result.success("success");
    }

    /** 找回密码第二步：验证码 + 新密码一起提交。 */
    @PostMapping("/updatefromcode")
    @RateLimit(limit = 30, window = 60)
    public Result<String> updateFromCode(@RequestParam String number,
                                        @RequestParam String code,
                                        @RequestParam String password){
        userService.updatePasswordForCode(number,code,password);
        return Result.success("success");
    }
    @GetMapping("/getuserbyid")
    @RateLimit(limit = 200, window = 60)
    public Result<UserDto> getUserById(){
        return Result.success(userService.getUserById(UserContext.getUserId()));
    }

    // 方法级示例：仅管理员可查询任意用户信息
    @RequireAdmin
    @GetMapping("/admin/user")
    @RateLimit(limit = 100, window = 60)
    public Result<UserDto> getUserByIdForAdmin(@RequestParam Long userId){
        return Result.success(userService.getUserById(userId));
    }

    /** 用户注销账号（仅本人可操作）。 */
    @PostMapping("/delete")
    @RateLimit(limit = 10, window = 60)
    public Result<String> deleteUser(){
        userService.deleteUser();
        return Result.success("success");
    }

    /** 用户主动冻结账号，需密码验证。 */
    @PostMapping("/freeze")
    @RateLimit(limit = 10, window = 60)
    public Result<String> freeze(@RequestParam String banReason, @RequestParam String password){
        userService.freezeUser(banReason, password);
        return Result.success("success");
    }

}
