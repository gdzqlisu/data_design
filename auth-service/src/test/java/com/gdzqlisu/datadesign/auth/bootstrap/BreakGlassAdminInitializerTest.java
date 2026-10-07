package com.gdzqlisu.datadesign.auth.bootstrap;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import com.gdzqlisu.datadesign.auth.user.Role;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "auth.break-glass.username=break-glass-admin",
        "auth.break-glass.password-hash=$2a$10$wvDCi/W8YLzoJ/ZIZOLuKODuTUZ.WJpK3mLNaTOpEii6VG4G/81Dy"
})
class BreakGlassAdminInitializerTest extends IntegrationTestBase {

    @Autowired
    private UserRepository users;

    @Test
    void createsActiveAdminOnStartup() {
        User admin = users.findByDisplayName("break-glass-admin").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.isBreakGlass()).isTrue();
        assertThat(admin.getPasswordHash()).startsWith("$2a$");
    }

    @Test
    void repairsTamperedAccountOnNextStartup() {
        User admin = users.findByDisplayName("break-glass-admin").orElseThrow();
        admin.disable();
        admin.changeRole(Role.VIEWER);
        users.saveAndFlush(admin);

        User reloaded = users.findById(admin.getId()).orElseThrow();
        boolean changed = reloaded.repairAsBreakGlass(
                "$2a$10$wvDCi/W8YLzoJ/ZIZOLuKODuTUZ.WJpK3mLNaTOpEii6VG4G/81Dy");
        users.saveAndFlush(reloaded);

        User repaired = users.findById(admin.getId()).orElseThrow();
        assertThat(changed).isTrue();
        assertThat(repaired.getRole()).isEqualTo(Role.ADMIN);
        assertThat(repaired.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
}
