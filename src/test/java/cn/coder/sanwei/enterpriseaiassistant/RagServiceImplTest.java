package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.RagResponse;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeRetrievalService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.PermissionServiceImpl;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.RagServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceImplTest {

    @Mock private ChatClient chat;
    @Mock private KnowledgeRetrievalService retrieval;
    private RagServiceImpl rag;

    @BeforeEach
    void setUp() {
        rag = new RagServiceImpl(chat, retrieval, new PermissionServiceImpl());
    }

    static Stream<AgentExecutionContext> deniedIdentities() {
        return Stream.of(null,
                new AgentExecutionContext(103L, 1L, Set.of()),
                new AgentExecutionContext(303L, 1L, Set.of("knowledge:write")));
    }

    @ParameterizedTest
    @MethodSource("deniedIdentities")
    void shouldDenyBeforeRetrievalOrChatModel(AgentExecutionContext identity) {
        assertThrows(AccessDeniedException.class, () -> rag.ask("报销期限？", 5, 0.5, identity));
        verifyNoInteractions(retrieval, chat);
    }

    @Test
    void shouldForwardTrustedIdentityAndSkipChatWhenNoEvidenceExists() {
        var reader = new AgentExecutionContext(302L, 2L, Set.of("knowledge:read"));
        when(retrieval.retrieval("报销期限？", 5, 0.5, reader)).thenReturn(List.of());

        RagResponse result = rag.ask("报销期限？", 5, 0.5, reader);

        assertEquals("知识库中未找到相关信息，请补充问题或相关资料。", result.answer());
        assertTrue(result.sources().isEmpty());
        verify(retrieval).retrieval("报销期限？", 5, 0.5, reader);
        verifyNoMoreInteractions(retrieval);
        verifyNoInteractions(chat);
    }
}
