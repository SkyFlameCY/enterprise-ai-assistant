package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.config.DemoUserConfig;
import cn.coder.sanwei.enterpriseaiassistant.config.SecurityConfig;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.controller.AgentController;
import cn.coder.sanwei.enterpriseaiassistant.controller.SecurityController;
import cn.coder.sanwei.enterpriseaiassistant.exception.GlobalExceptionHandler;
import cn.coder.sanwei.enterpriseaiassistant.security.AgentUserDetails;
import cn.coder.sanwei.enterpriseaiassistant.service.AgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * 使用真实安全过滤器和控制器，只模拟 AgentService。
 * 不启动模型、数据库，也不读取任何真实账号密码。
 */
@SpringJUnitConfig(AgentSecurityTest.TestConfiguration.class)
@WebAppConfiguration
class AgentSecurityTest {

    private static final String TEST_PASSWORD = "test-only-password";
    private static final String MESSAGE = "查询 EA-2026 项目";
    private static final String REQUEST_BODY = "{\"message\":\"" + MESSAGE + "\"}";

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private AgentService agentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(agentService);
        mockMvc = webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    @ParameterizedTest
    @MethodSource("readers")
    void shouldAdmitEitherReadPermissionAndForwardTrustedIdentity(
            String username, long userId, long tenantId, Set<String> permissions) throws Exception {
        // 准备：只模拟回答；身份和权限由真实 Basic 登录流程产生。
        when(agentService.chat(eq(MESSAGE), any(AgentExecutionContext.class)))
                .thenReturn("项目查询结果");

        // 执行：携带合法 CSRF 和账号密码请求 Agent 接口。
        mockMvc.perform(post("/api/agent/chat").contextPath("/api")
                        .with(httpBasic(username, TEST_PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("项目查询结果"));

        // 验证：入口准入成功，并且传入的是服务端登录身份。
        var context = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(agentService).chat(eq(MESSAGE), context.capture());
        assertEquals(new AgentExecutionContext(userId, tenantId, permissions), context.getValue());
        verifyNoMoreInteractions(agentService);
    }

    static Stream<Arguments> readers() {
        return Stream.of(
                Arguments.of("tenant-a", 101L, 1L, Set.of("ticket:read", "project:read")),
                Arguments.of("tenant-b", 102L, 2L, Set.of("ticket:read", "project:read")),
                Arguments.of("project-reader", 201L, 1L, Set.of("project:read")),
                Arguments.of("ticket-reader", 202L, 2L, Set.of("ticket:read"))
        );
    }

    @Test
    void shouldDenyViewerBeforeCallingAgent() throws Exception {
        mockMvc.perform(post("/api/agent/chat").contextPath("/api")
                        .with(httpBasic("viewer", TEST_PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(agentService);
    }

    @Test
    void shouldRequireAuthenticationBeforeCallingAgent() throws Exception {
        mockMvc.perform(post("/api/agent/chat").contextPath("/api").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(agentService);
    }

    @Test
    void shouldRejectWrongPasswordBeforeCallingAgent() throws Exception {
        mockMvc.perform(post("/api/agent/chat").contextPath("/api")
                        .with(httpBasic("tenant-a", "wrong-password")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(agentService);
    }

    @Test
    void shouldKeepCsrfProtectionForAuthenticatedReader() throws Exception {
        mockMvc.perform(post("/api/agent/chat").contextPath("/api")
                        .with(httpBasic("tenant-a", TEST_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(agentService);
    }

    @Test
    void shouldRejectInvalidCsrfToken() throws Exception {
        mockMvc.perform(post("/api/agent/chat").contextPath("/api")
                        .with(httpBasic("tenant-a", TEST_PASSWORD)).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(agentService);
    }

    @Test
    void shouldKeepUnrelatedEndpointsDenied() throws Exception {
        mockMvc.perform(get("/api/knowledge/search").contextPath("/api")
                        .with(httpBasic("tenant-a", TEST_PASSWORD)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(agentService);
    }

    @Test
    void shouldAcceptRealCsrfTokenObtainedFromPublicEndpoint() throws Exception {
        var csrfResponse = mockMvc.perform(get("/api/security/csrf").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        var csrfJson = new ObjectMapper().readTree(csrfResponse.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) csrfResponse.getRequest().getSession(false);
        assertNotNull(session);

        when(agentService.chat(eq(MESSAGE), any(AgentExecutionContext.class)))
                .thenReturn("项目查询结果");
        mockMvc.perform(post("/api/agent/chat").contextPath("/api").session(session)
                        .with(httpBasic("tenant-a", TEST_PASSWORD))
                        .header(csrfJson.get("headerName").asText(), csrfJson.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isOk());

        verify(agentService).chat(MESSAGE,
                new AgentExecutionContext(101L, 1L, Set.of("ticket:read", "project:read")));
        verifyNoMoreInteractions(agentService);
    }

    @Test
    void shouldNotAcceptIdentityAndPermissionsForgedInRequestBody() throws Exception {
        when(agentService.chat(eq(MESSAGE), any(AgentExecutionContext.class)))
                .thenReturn("项目查询结果");

        // 请求体伪造租户和权限；实际身份仍必须取自登录账号。
        mockMvc.perform(post("/api/agent/chat").contextPath("/api")
                        .with(httpBasic("tenant-a", TEST_PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {
                                  "message": "查询 EA-2026 项目",
                                  "userId": 999,
                                  "tenantId": 2,
                                  "permissions": ["project:write"]
                                }
                                """))
                .andExpect(status().isOk());

        verify(agentService).chat(MESSAGE,
                new AgentExecutionContext(101L, 1L, Set.of("ticket:read", "project:read")));
        verifyNoMoreInteractions(agentService);
    }

    @Test
    void shouldRejectBlankMessageWithoutCallingAgent() throws Exception {
        mockMvc.perform(post("/api/agent/chat").contextPath("/api")
                        .with(httpBasic("tenant-a", TEST_PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verifyNoInteractions(agentService);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, AgentController.class, SecurityController.class,
            GlobalExceptionHandler.class})
    static class TestConfiguration {

        @Bean
        AgentService agentService() {
            return mock(AgentService.class);
        }

        @Bean
        UserDetailsService users(PasswordEncoder passwordEncoder) {
            UserDetailsService demoUsers = new DemoUserConfig()
                    .demoUserDetailsService(passwordEncoder, TEST_PASSWORD);
            String hash = passwordEncoder.encode(TEST_PASSWORD);

            // 两个单权限账号只存在于测试，生产配置未增加任何账号。
            return username -> switch (username) {
                case "project-reader" -> new AgentUserDetails(201L, 1L, username, hash,
                        List.of(new SimpleGrantedAuthority("project:read")));
                case "ticket-reader" -> new AgentUserDetails(202L, 2L, username, hash,
                        List.of(new SimpleGrantedAuthority("ticket:read")));
                default -> demoUsers.loadUserByUsername(username);
            };
        }
    }
}
