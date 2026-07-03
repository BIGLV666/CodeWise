package org.example.serviceuser.Fegin;

import org.example.serviceapi.dto.Result;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class User {
    @Autowired
    private UserService userService;
    @GetMapping("/info/{userId}")
    public Result<UserDto> getUserInfo(@PathVariable Long userId) {
        UserDto user = userService.getUserById(userId);
        return Result.success(user);
    }
}
