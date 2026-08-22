package org.example.servicecommon.config;

import feign.RequestInterceptor;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 统一 Feign 请求头拦截器自动装配。
 *
 * <p>历史上 ai/community/judge/question/review 五个服务各自维护一份
 * 逐字节相同的 {@code FeignRequestInterceptor}，且内部 Token 硬编码。
 * 现统一收敛到 service-common：身份三件套（X-User-Id/X-User-Name/X-Real-IP）
 * 从 {@link UserContext} 透传，内部 Token 改读
 * {@code codewise.internal-token} 配置（默认值与 UserAuthInterceptor
 * 现行校验值一致，保证行为兼容；彻底出源码属 P0-1 范围另行处理）。</p>
 *
 * <p>各服务本地的拦截器副本应删除；如某服务需要额外头，可在其本地
 * 再声明一个 RequestInterceptor Bean（多个拦截器会叠加执行）。</p>
 */
@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
public class FeignHeaderAutoConfiguration {

    /**
     * 注册全局 Feign 请求头拦截器。
     *
     * @param internalToken 内部通信 Token，来自配置 codewise.internal-token
     * @return 拦截器实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "commonFeignRequestInterceptor")
    public RequestInterceptor commonFeignRequestInterceptor(
            @Value("${codewise.internal-token:codewise-secret-2026}") String internalToken) {
        return template -> {
            template.header("X-Internal-Token", internalToken);

            Long userId = UserContext.getUserId();
            if (userId != null) {
                template.header("X-User-Id", String.valueOf(userId));
            }
            String userName = UserContext.getUserName();
            if (userName != null) {
                template.header("X-User-Name", userName);
            }
            String ip = UserContext.getIp();
            if (ip != null) {
                template.header("X-Real-IP", ip);
            }
        };
    }
}
