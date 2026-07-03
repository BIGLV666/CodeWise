package org.example.serviceapi.feign;

import org.example.serviceapi.dto.UserDto;
import org.example.serviceapi.dto.Result;




import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// 不写 url，使用 Nacos 负载均衡
@FeignClient(name = "service-user")
public interface UserFeignClient {

    @GetMapping("/api/user/info/{userId}")
    Result<UserDto> getUserInfo(@PathVariable("userId") Long userId);
}