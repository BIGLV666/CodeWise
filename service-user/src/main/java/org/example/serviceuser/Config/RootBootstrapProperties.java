package org.example.serviceuser.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * root 初始化用户配置。
 * <p>
 * 用于 Docker 部署时的一次性初始化，凭据全部由环境变量注入，避免硬编码密钥。
 * 未配置时跳过 root 用户创建。
 */
@Data
@Component
@ConfigurationProperties(prefix = "codewise.bootstrap.root")
public class RootBootstrapProperties {

    /** 是否启用 root 初始化，默认启用。 */
    private boolean enabled = true;

    /** root 用户名，默认 admin。 */
    private String username = "admin";

    /** root 密码，必须通过环境变量 ROOT_PASSWORD 注入，无默认值。 */
    private String password;

    /** root 显示昵称，默认与用户名一致。 */
    private String nickname;

    /** root 邮箱，可选。 */
    private String email;

    /** root 手机号，可选。 */
    private String phone;

    public String getEffectiveNickname() {
        return (nickname == null || nickname.isBlank()) ? username : nickname;
    }
}