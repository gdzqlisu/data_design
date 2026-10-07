package com.gdzqlisu.datadesign.auth.bootstrap;

import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserIdentity;
import com.gdzqlisu.datadesign.auth.user.UserIdentityRepository;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class BreakGlassAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassAdminInitializer.class);

    private final BreakGlassAdminProperties properties;
    private final UserRepository users;
    private final UserIdentityRepository identities;

    public BreakGlassAdminInitializer(BreakGlassAdminProperties properties,
                                      UserRepository users,
                                      UserIdentityRepository identities) {
        this.properties = properties;
        this.users = users;
        this.identities = identities;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.configured()) {
            log.warn("未配置破窗管理员（auth.break-glass.username / password-hash）；"
                    + "GitHub 不可用时将无法登录系统");
            return;
        }

        Optional<User> existing = users.findByDisplayName(properties.username());
        if (existing.isEmpty()) {
            User created = users.saveAndFlush(
                    User.newBreakGlass(properties.username(), properties.passwordHash()));
            identities.saveAndFlush(UserIdentity.local(created));
            log.info("已创建破窗管理员 {}（id={}）", properties.username(), created.getId());
            return;
        }

        User user = existing.get();
        if (user.repairAsBreakGlass(properties.passwordHash())) {
            users.saveAndFlush(user);
            log.warn("破窗管理员 {} 的账号状态与配置不一致，已按配置校正", properties.username());
        }
    }
}
