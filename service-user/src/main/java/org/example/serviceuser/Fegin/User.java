package org.example.serviceuser.Fegin;

import io.github.biglv666.apigovernance.annotation.Skip;
import org.example.serviceapi.dto.Result;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 服务间内部接口（{@code service-api} 的 {@code UserFeignClient}），只有 Feign 会调用。
 *
 * <p>必须标注 {@link Skip}：这些接口被每个服务的管理鉴权、批量导入等链路高频调用，
 * 若参与用户维度限流，几十次之后就会被 429 拒绝；而调用方（如 {@code AdminAuthAspect}）
 * 拿不到用户数据时只能报「登录状态已失效」，把限流伪装成掉线。</p>
 */
@RestController
@RequestMapping("/api/user")
public class User {
    @Autowired
    private UserService userService;
    @GetMapping("/info/{userId}")
    @Skip(reason = "服务间内部接口（UserFeignClient），不参与用户维度限流")
    public Result<UserDto> getUserInfo(@PathVariable Long userId) {
        UserDto user = userService.getUserById(userId);
        return Result.success(user);
    }
    @PostMapping("/info/list")
    @Skip(reason = "服务间内部接口（UserFeignClient），不参与用户维度限流")
    public Result<Map<Long, org.example.serviceapi.dto.user.UserDto>> getUserList(@RequestBody List<Long> userIds) {
        return Result.success(userService.BatchSelectUser(userIds));
    }
}
