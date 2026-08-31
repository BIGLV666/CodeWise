package org.example.serviceuser.aspect;

import lombok.extern.slf4j.Slf4j;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import io.github.biglv666.apigovernance.async.spi.AsyncEventEnricher;
import org.example.serviceapi.dto.Result;
import org.example.serviceuser.dto.UserDto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@Slf4j
public class AsyncEnricher {
    @Bean
    public AsyncEventEnricher login(){
        log.info("Registered user-login AsyncEventEnricher");
        return (builder, invocation) -> {
            if (!"user-login".equals(invocation.getAction())
                    || invocation.getPhase() != AsyncPhase.AFTER_SUCCESS) {
                return;
            }

            log.info("Enriching async event: action={}, phase={}",
                    invocation.getAction(), invocation.getPhase());
            if (!(invocation.getResult() instanceof Result<?> result)
                    || !(result.getData() instanceof Map<?, ?> data)
                    || !(data.get("user") instanceof UserDto user)) {
                log.warn("Cannot extract user information from user-login result");
                return;
            }

            builder.put("userId", Long.valueOf(user.getUserId()))
                    .put("username", user.getUserName())
                    .put("loginTime", LocalDateTime.now().toString());

            if (RequestContextHolder.getRequestAttributes()
                    instanceof ServletRequestAttributes attributes) {
                String ip = attributes.getRequest().getHeader("X-Real-IP");
                if (ip == null || ip.isBlank()) {
                    ip = attributes.getRequest().getRemoteAddr();
                }
                builder.put("ipAddress", ip)
                        .put("userAgent", attributes.getRequest().getHeader("User-Agent"));
            }
        };
    }
}
