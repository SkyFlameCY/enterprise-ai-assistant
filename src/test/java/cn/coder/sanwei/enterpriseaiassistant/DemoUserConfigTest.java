package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.config.DemoUserConfig;
import cn.coder.sanwei.enterpriseaiassistant.config.SecurityConfig;
import cn.coder.sanwei.enterpriseaiassistant.security.AgentUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 演示账号配置测试：使用测试专用密码，不读取本地密码或启动应用。
 */
class DemoUserConfigTest {

    private static final String TEST_PASSWORD = "test-only-password";

    private PasswordEncoder passwordEncoder;
    private UserDetailsService users;

    @BeforeEach
    void setUp() {
        passwordEncoder = new SecurityConfig(mock(ApplicationContext.class)).passwordEncoder();
        users = new DemoUserConfig().demoUserDetailsService(passwordEncoder, TEST_PASSWORD);
    }

    @ParameterizedTest
    @CsvSource({"tenant-a, 101, 1", "tenant-b, 102, 2"})
    void shouldProvideBothReadPermissionsWithTrustedIdentity(
            String username, long userId, long tenantId) {
        // 准备：从真实账号配置加载用户，而不是手工拼权限。
        AgentUserDetails user = (AgentUserDetails) users.loadUserByUsername(username);

        // 验证：两个租户都有两种读取权限，但身份各不相同。
        assertAll(
                () -> assertEquals(userId, user.getUserId()),
                () -> assertEquals(tenantId, user.getTenantId()),
                () -> assertEquals(username, user.getUsername()),
                () -> assertEquals(Set.of("ticket:read", "project:read"), authorities(user)),
                () -> assertNotEquals(TEST_PASSWORD, user.getPassword()),
                () -> assertTrue(passwordEncoder.matches(TEST_PASSWORD, user.getPassword()))
        );
    }

    @Test
    void shouldKeepViewerWithoutToolPermissions() {
        AgentUserDetails user = (AgentUserDetails) users.loadUserByUsername("viewer");

        assertAll(
                () -> assertEquals(103L, user.getUserId()),
                () -> assertEquals(1L, user.getTenantId()),
                () -> assertTrue(user.getAuthorities().isEmpty())
        );
    }

    @ParameterizedTest
    @MethodSource("knowledgeAccounts")
    void shouldProvideDedicatedKnowledgeIdentityAndExactPermissions(
            String username, long userId, long tenantId, Set<String> permissions) {
        AgentUserDetails user = (AgentUserDetails) users.loadUserByUsername(username);

        assertAll(
                () -> assertEquals(userId, user.getUserId()),
                () -> assertEquals(tenantId, user.getTenantId()),
                () -> assertEquals(username, user.getUsername()),
                () -> assertEquals(permissions, authorities(user)),
                () -> assertNotEquals(TEST_PASSWORD, user.getPassword()),
                () -> assertTrue(passwordEncoder.matches(TEST_PASSWORD, user.getPassword()))
        );
    }

    static Stream<Arguments> knowledgeAccounts() {
        return Stream.of(
                Arguments.of("knowledge-reader-a", 301L, 1L, Set.of("knowledge:read")),
                Arguments.of("knowledge-reader-b", 302L, 2L, Set.of("knowledge:read")),
                Arguments.of("knowledge-editor-a", 303L, 1L, Set.of("knowledge:read", "knowledge:write")),
                Arguments.of("knowledge-editor-b", 304L, 2L, Set.of("knowledge:read", "knowledge:write"))
        );
    }

    @Test
    void shouldRejectUnknownUsername() {
        assertThrows(UsernameNotFoundException.class,
                () -> users.loadUserByUsername("unknown"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void shouldRejectBlankDemoPassword(String password) {
        assertThrows(IllegalArgumentException.class,
                () -> new DemoUserConfig().demoUserDetailsService(passwordEncoder, password));
    }

    @Test
    void shouldReturnFreshUserAfterCredentialsWereErased() {
        AgentUserDetails first = (AgentUserDetails) users.loadUserByUsername("tenant-a");
        first.eraseCredentials();

        AgentUserDetails second = (AgentUserDetails) users.loadUserByUsername("tenant-a");

        assertNotSame(first, second);
        assertNull(first.getPassword());
        assertTrue(passwordEncoder.matches(TEST_PASSWORD, second.getPassword()));
        assertEquals(Set.of("ticket:read", "project:read"), authorities(second));
    }

    @Test
    void shouldRestrictDemoAccountConfigurationToLocalProfile() {
        Profile profile = DemoUserConfig.class.getAnnotation(Profile.class);

        assertNotNull(profile);
        assertArrayEquals(new String[]{"local"}, profile.value());
    }

    private Set<String> authorities(AgentUserDetails user) {
        return user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
