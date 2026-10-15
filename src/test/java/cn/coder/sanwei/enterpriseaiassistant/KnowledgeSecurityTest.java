package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.config.SecurityConfig;
import cn.coder.sanwei.enterpriseaiassistant.config.DemoUserConfig;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.controller.KnowledgeController;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;
import cn.coder.sanwei.enterpriseaiassistant.dto.RagResponse;
import cn.coder.sanwei.enterpriseaiassistant.exception.GlobalExceptionHandler;
import cn.coder.sanwei.enterpriseaiassistant.security.AgentUserDetails;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeImportService;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeRetrievalService;
import cn.coder.sanwei.enterpriseaiassistant.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** 真实入口鉴权与 Controller，模拟业务服务；演示账号取自实际配置并使用测试密码。 */
@SpringJUnitConfig(KnowledgeSecurityTest.TestConfiguration.class)
@WebAppConfiguration
class KnowledgeSecurityTest {

    private static final String PASSWORD = "knowledge-test-only";
    private static final String QUESTION = "报销申请什么时候提交？";
    private static final String SEARCH_BODY = """
            {"question":"报销申请什么时候提交？","topK":5,"minSimilarity":0.5}
            """;
    private static final String IMPORT_BODY = """
            {"documentKey":"expense-policy","version":1,"title":"员工报销制度",
             "sourceRef":"模拟资料","text":"次月提交报销申请。"}
            """;

    @Autowired private WebApplicationContext webContext;
    @Autowired private KnowledgeImportService imports;
    @Autowired private KnowledgeRetrievalService retrieval;
    @Autowired private RagService rag;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(imports, retrieval, rag);
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    @Test
    void shouldForwardTrustedIdentityForSearchDespiteForgedBodyFields() throws Exception {
        when(retrieval.retrieval(eq(QUESTION), eq(5), eq(0.5), any())).thenReturn(List.of());
        String forged = """
                {"question":"报销申请什么时候提交？","topK":5,"minSimilarity":0.5,
                 "tenantId":2,"userId":999,"permissions":["knowledge:write"]}
                """;

        mvc.perform(post("/api/knowledge/search").contextPath("/api")
                        .with(httpBasic("reader", PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isOk());

        verify(retrieval).retrieval(QUESTION, 5, 0.5,
                new AgentExecutionContext(301L, 1L, Set.of("knowledge:read")));
        verifyNoInteractions(imports, rag);
    }

    @Test
    void shouldAllowReaderToAskWithTrustedIdentity() throws Exception {
        when(rag.ask(eq(QUESTION), eq(5), eq(0.5), any()))
                .thenReturn(new RagResponse("次月提交。", List.of()));

        mvc.perform(post("/api/knowledge/ask").contextPath("/api")
                        .with(httpBasic("reader", PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(SEARCH_BODY))
                .andExpect(status().isOk());

        verify(rag).ask(QUESTION, 5, 0.5,
                new AgentExecutionContext(301L, 1L, Set.of("knowledge:read")));
        verifyNoInteractions(imports, retrieval);
    }

    @Test
    void shouldAllowWriterAndMapImportBusinessFields() throws Exception {
        when(imports.importText(any(), anyString(), any())).thenReturn(77L);

        mvc.perform(post("/api/knowledge/import/text").contextPath("/api")
                        .with(httpBasic("writer", PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(IMPORT_BODY))
                .andExpect(status().isOk());

        var document = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(imports).importText(document.capture(), eq("次月提交报销申请。"),
                eq(new AgentExecutionContext(303L, 1L, Set.of("knowledge:write"))));
        assertEquals("expense-policy", document.getValue().getDocumentKey());
        assertEquals(1, document.getValue().getVersion());
        assertEquals("员工报销制度", document.getValue().getTitle());
        verifyNoInteractions(retrieval, rag);
    }

    @ParameterizedTest
    @MethodSource("knowledgeAccounts")
    void shouldAdmitConfiguredKnowledgeAccountAndForwardTrustedIdentity(
            String username, long userId, long tenantId, Set<String> permissions) throws Exception {
        when(retrieval.retrieval(eq(QUESTION), eq(5), eq(0.5), any())).thenReturn(List.of());

        mvc.perform(post("/api/knowledge/search").contextPath("/api")
                        .with(httpBasic(username, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(SEARCH_BODY))
                .andExpect(status().isOk());

        verify(retrieval).retrieval(QUESTION, 5, 0.5,
                new AgentExecutionContext(userId, tenantId, permissions));
        verifyNoInteractions(imports, rag);
    }

    static Stream<Arguments> knowledgeAccounts() {
        return Stream.of(
                Arguments.of("knowledge-reader-a", 301L, 1L, Set.of("knowledge:read")),
                Arguments.of("knowledge-reader-b", 302L, 2L, Set.of("knowledge:read")),
                Arguments.of("knowledge-editor-a", 303L, 1L, Set.of("knowledge:read", "knowledge:write")),
                Arguments.of("knowledge-editor-b", 304L, 2L, Set.of("knowledge:read", "knowledge:write"))
        );
    }

    @ParameterizedTest
    @CsvSource({"knowledge-editor-a, 303, 1", "knowledge-editor-b, 304, 2"})
    void shouldAllowConfiguredEditorToImportInTrustedTenant(
            String username, long userId, long tenantId) throws Exception {
        when(imports.importText(any(), anyString(), any())).thenReturn(77L);

        mvc.perform(post("/api/knowledge/import/text").contextPath("/api")
                        .with(httpBasic(username, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(IMPORT_BODY))
                .andExpect(status().isOk());

        verify(imports).importText(any(KnowledgeDocument.class), eq("次月提交报销申请。"),
                eq(new AgentExecutionContext(userId, tenantId,
                        Set.of("knowledge:read", "knowledge:write"))));
        verifyNoInteractions(retrieval, rag);
    }

    @ParameterizedTest
    @ValueSource(strings = {"knowledge-reader-a", "knowledge-reader-b"})
    void shouldDenyConfiguredReaderBeforeImport(String username) throws Exception {
        mvc.perform(post("/api/knowledge/import/text").contextPath("/api")
                        .with(httpBasic(username, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(IMPORT_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(imports, retrieval, rag);
    }

    @ParameterizedTest
    @CsvSource({"reader, /knowledge/import/text", "writer, /knowledge/search",
            "writer, /knowledge/ask", "viewer, /knowledge/search", "business, /knowledge/search"})
    void shouldDenyWrongPermissionBeforeBusinessService(String username, String path) throws Exception {
        mvc.perform(post("/api" + path).contextPath("/api")
                        .with(httpBasic(username, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(path.endsWith("text") ? IMPORT_BODY : SEARCH_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(imports, retrieval, rag);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/knowledge/import/text", "/knowledge/search", "/knowledge/ask"})
    void shouldRequireLoginWithValidCsrf(String path) throws Exception {
        mvc.perform(post("/api" + path).contextPath("/api").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(path.endsWith("text") ? IMPORT_BODY : SEARCH_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(imports, retrieval, rag);
    }

    @ParameterizedTest
    @CsvSource({"writer, /knowledge/import/text", "reader, /knowledge/search", "reader, /knowledge/ask"})
    void shouldKeepCsrfProtection(String username, String path) throws Exception {
        mvc.perform(post("/api" + path).contextPath("/api")
                        .with(httpBasic(username, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(path.endsWith("text") ? IMPORT_BODY : SEARCH_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(imports, retrieval, rag);
    }

    @Test
    void shouldMapServiceAccessDeniedToForbidden() throws Exception {
        when(imports.importText(any(), anyString(), any()))
                .thenThrow(new AccessDeniedException("没有知识库导入权限"));

        mvc.perform(post("/api/knowledge/import/text").contextPath("/api")
                        .with(httpBasic("writer", PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(IMPORT_BODY))
                .andExpect(status().isForbidden());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, KnowledgeController.class, GlobalExceptionHandler.class})
    static class TestConfiguration {
        @Bean KnowledgeImportService imports() { return mock(KnowledgeImportService.class); }
        @Bean KnowledgeRetrievalService retrieval() { return mock(KnowledgeRetrievalService.class); }
        @Bean RagService rag() { return mock(RagService.class); }

        @Bean
        UserDetailsService users(PasswordEncoder encoder) {
            String hash = encoder.encode(PASSWORD);
            UserDetailsService demoUsers = new DemoUserConfig().demoUserDetailsService(encoder, PASSWORD);
            return username -> switch (username) {
                case "reader" -> user(301L, username, hash, "knowledge:read");
                case "writer" -> user(303L, username, hash, "knowledge:write");
                case "viewer" -> user(103L, username, hash);
                case "business" -> user(101L, username, hash, "ticket:read", "project:read");
                default -> demoUsers.loadUserByUsername(username);
            };
        }

        private AgentUserDetails user(long userId, String username, String hash, String... scopes) {
            var authorities = java.util.Arrays.stream(scopes).map(SimpleGrantedAuthority::new).toList();
            return new AgentUserDetails(userId, 1L, username, hash, authorities);
        }
    }
}
