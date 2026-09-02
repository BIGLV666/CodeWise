package org.example.serviceuser.Config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.SysInitMapper;
import org.example.serviceuser.mapper.UserMapper;
import org.example.serviceuser.until.PasswordEncoding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Docker 一次性初始化：创建全局唯一的 root 管理员账号。
 *
 * <p>「仅执行一次」的保证依赖 sys_init 表的唯一索引 + INSERT IGNORE：
 * 并发多实例/重启时，只有第一个成功 INSERT 的实例会返回 1 并执行初始化，
 * 其余实例返回 0 直接跳过，天然幂等，不依赖 Redis/分布式锁。</p>
 *
 * <p>同时通过 {@code user.user_name} 唯一索引兜底，即使逻辑被绕过也无法重复插入。</p>
 */
@Slf4j
@Component
public class RootUserInitializer implements ApplicationRunner {

    /** 初始化项唯一标识，写入 sys_init.init_key。 */
    private static final String ROOT_INIT_KEY = "root-user";

    /** 管理员角色，与 user.role_id 语义一致（2-管理员）。 */
    private static final int ROLE_ADMIN = 2;

    @Autowired
    private SysInitMapper sysInitMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private org.example.serviceuser.config.RootBootstrapProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("Root bootstrap disabled, skip.");
            return;
        }
        String password = properties.getPassword();
        if (password == null || password.isBlank()) {
            log.warn("Root bootstrap enabled but password is not configured (codewise.bootstrap.root.password / ROOT_PASSWORD), skip.");
            return;
        }

        // 1. 确保标记表存在（幂等）。
        sysInitMapper.ensureTable();

        // 2. 唯一性登记：返回 0 说明已初始化过。
        int registered = sysInitMapper.tryRegister(ROOT_INIT_KEY);
        if (registered == 0) {
            log.info("Root user already initialized, skip.");
            return;
        }

        // 3. 只有成功登记的实例会走到这里，执行真正的初始化。
        try {
            createRootUser();
            log.info("Root user '{}' initialized successfully.", properties.getUsername());
        } catch (Exception ex) {
            // 本次登记成功但创建失败：回滚标记，下次启动可重试。
            sysInitMapper.unregister(ROOT_INIT_KEY);
            log.error("Root user initialization failed, marker rolled back: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    private void createRootUser() {
        String username = properties.getUsername();
        User existing = userMapper.selectOne(new QueryWrapper<User>().eq("user_name", username));
        if (existing != null) {
            log.info("Root user '{}' already exists, skip creation.", username);
            return;
        }

        User root = User.builder()
                .userName(username)
                .nickName(properties.getEffectiveNickname())
                .password(PasswordEncoding.encode(properties.getPassword()))
                .email(properties.getEmail())
                .phone(properties.getPhone())
                .roleId(ROLE_ADMIN)
                .status(1)
                // user 表 last_login_ip/last_login_time 为 NOT NULL，初始化时置占位值。
                .lastLoginIp("0.0.0.0")
                .lastLoginTime(LocalDateTime.now())
                .build();
        userMapper.insert(root);
    }
}
