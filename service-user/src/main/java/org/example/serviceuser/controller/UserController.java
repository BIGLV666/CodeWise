package org.example.serviceuser.controller;

import org.example.serviceapi.dto.ai.AiAdviceWADto;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.config.MqContexts;
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
    public Result<String> emailRegister(@RequestParam String email, @RequestParam String password, @RequestParam String username){
        userService.email_regis(email,username,password);
        return Result.success("success");
    }
    @PostMapping("/register")
    public Result<String>register(@RequestParam String number, @RequestParam String code){
        userService.register(number,code);
        return Result.success("success");
    }
    @PostMapping("/updatepassword")
    public Result<String>updatePassword(@RequestParam String oldPassword, @RequestParam String newPassword){
        userService.updatePassword(oldPassword,newPassword);
        return Result.success("success");
    }
    @PostMapping("/login")
    public Result<Map<String,Object>> login(@RequestParam String username, @RequestParam String password){
        return  Result.success(userService.login(username,password));
    }

    @PostMapping("/updatepasswordforemail")
    public Result<String> updatePasswordForEmail(@RequestParam String email,@RequestParam String password){
        userService.updatePasswordForEmail(email,password);
        return Result.success("success");
    }
    @PostMapping("/updatefromcode")
    public Result<String> updateFromCode(@RequestParam String number,@RequestParam String code){
        userService.updatePasswordForCode(number,code);
        return Result.success("success");
    }
    @GetMapping("/getuserbyid")
    public Result<UserDto> getUserById(@RequestParam Long id){
        return Result.success(userService.getUserById(id));
    }

}
