package org.example.serviceuser.Fegin;

import org.example.serviceapi.dto.Result;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    @PostMapping("/info/list")
    public Result<Map<Long, org.example.serviceapi.dto.user.UserDto>> getUserList(@RequestBody List<Long> userIds) {
        return Result.success(userService.BatchSelectUser(userIds));
    }
}
