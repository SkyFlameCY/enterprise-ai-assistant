package cn.coder.sanwei.enterpriseaiassistant.config;

import cn.coder.sanwei.enterpriseaiassistant.security.AgentUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * DemoUserConfig
 *
 * @author caiyang
 * @date 2026/10/2
 */
@Configuration
@Profile("local")
public class DemoUserConfig {

    @Bean
    public UserDetailsService demoUserDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${DEMO_SECURITY_PASSWORD}") String demoPassword
    ) {
        if (demoPassword.isBlank()) {
            throw new IllegalArgumentException("演示密码不能为空");
        }

        String passwordHash = passwordEncoder.encode(demoPassword);
        var readPermission = List.of(
                new SimpleGrantedAuthority("ticket:read"),
                new SimpleGrantedAuthority("project:read")
        );

        return username -> switch (username) {
            case "tenant-a" -> new AgentUserDetails(101L, 1L, username, passwordHash, readPermission);
            case "tenant-b" -> new AgentUserDetails(102L, 2L, username, passwordHash, readPermission);
            case "viewer" -> new AgentUserDetails(103L, 1L, username, passwordHash, List.of());
            default -> throw new UsernameNotFoundException("用户不存在");
        };
    }
}
