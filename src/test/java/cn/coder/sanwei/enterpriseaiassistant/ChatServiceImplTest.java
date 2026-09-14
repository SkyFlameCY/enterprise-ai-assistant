package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.service.ChatService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * ChatServiceImplTest
 *
 * @author caiyang
 * @date 2026/9/10
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        PromptTemplate promptTemplate = new PromptTemplate(new ClassPathResource("prompts/chat-user-prompt.txt"));

        chatService = new ChatServiceImpl(chatClient, promptTemplate);
    }

    @Test
    void shouldReturnAiAnswer() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("你好，我是企业智能助手");

        String result = chatService.chat("你好");

        assertThat(result).isEqualTo("你好，我是企业智能助手");
    }

    @Test
    void shouldRejectBlankMessage() {
        assertThatThrownBy(() -> chatService.chat(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldBuildChatUserPrompt() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("这是答案");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);

        chatService.chat("报销流程是什么？");

        verify(chatClient).prompt(captor.capture());

        String actualPrompt = captor.getValue().getUserMessage().getText();

        assertThat(actualPrompt)
                .contains("请回答下面的用户问题。")
                .contains("用户问题：")
                .contains("报销流程是什么？")
                .doesNotContain("{question}");
    }
}
