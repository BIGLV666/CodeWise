package org.example.servicegateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关全局异常处理（响应式）。
 *
 * <p>网关是 Spring Cloud Gateway（WebFlux），servlet 的 {@code @RestControllerAdvice}
 * 不生效，需实现 {@link ErrorWebExceptionHandler}。本类统一把路由失败 / 下游异常
 * 渲染为平台一致的 {@code {code, message, data}} JSON 体，取代 WebFlux 默认的
 * {@code {timestamp, path, status, error, ...}} 结构，前端与 agent 均可按统一契约解析。</p>
 *
 * <p>消息按 HTTP 状态码映射为固定文案，不回传异常明细（内部实现不进响应），
 * 真实异常只进服务端日志。鉴权 401 由 {@link AuthGlobalFilter} 直接短路返回，
 * 不经过本处理器。</p>
 */
@Slf4j
@Order(-2)
@Component
public class GlobalErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        HttpStatus status = resolveStatus(exchange, ex);
        String message = messageFor(status);
        log.error("网关转发异常: status={}, path={}, error={}",
                status.value(), exchange.getRequest().getPath(), ex.getMessage(), ex);

        // 消息为固定文案（不含异常明细），无需转义；UTF-8 直接写出
        byte[] bytes = ("{\"code\":" + status.value()
                + ",\"message\":\"" + message
                + "\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /** 状态码推导：显式 ResponseStatusException → 已设状态码 → 兜底 500。 */
    private HttpStatus resolveStatus(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof ResponseStatusException rse && rse.getStatusCode() != null) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status != null) {
                return status;
            }
        }
        HttpStatusCode already = exchange.getResponse().getStatusCode();
        if (already != null) {
            HttpStatus status = HttpStatus.resolve(already.value());
            if (status != null) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** 按状态码给出固定文案，不外泄异常/内部信息。 */
    private String messageFor(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return "接口不存在";
        }
        if (status.is5xxServerError()) {
            return "系统繁忙，请稍后重试";
        }
        String reason = status.getReasonPhrase();
        return reason != null ? reason : "请求失败";
    }
}
