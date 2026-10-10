package cn.coder.sanwei.enterpriseaiassistant.memory;

import cn.coder.sanwei.enterpriseaiassistant.config.AgentMemoryProperties;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentMessage;
import cn.coder.sanwei.enterpriseaiassistant.enums.AgentMessageRole;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ConversationHistoryBuilder
 *
 * @author caiyang
 * @date 2026/10/9
 */
@Component
public class ConversationHistoryBuilder {

    private final AgentMemoryProperties properties;

    public ConversationHistoryBuilder(AgentMemoryProperties properties) {
        this.properties = properties;
    }

    public List<Message> build(List<AgentMessage> orderedHistory, String currentMessage) {
        if (currentMessage == null) {
            throw new IllegalStateException("currentMessage 不能为空");
        }

        // 1. 先给当前输入预留长度
        long remaining = (long) properties.getMaxContextChars() - (long) currentMessage.length();
        if (remaining < 0) {
            throw new IllegalArgumentException("模型可使用上下文剩余长度不足");
        }

        List<Message> result = new ArrayList<>();

        // 2. 校验并按整轮（USER + ASSISTANT）配对
        List<MessagePair> pairs = validateAndPair(orderedHistory);

        // 3. 从最新的一对往前，整对能放下才保留，遇到第一对放不下停止
        List<MessagePair> keptDesc = new ArrayList<>();
        for (int i = pairs.size() - 1; i >= 0; i--) {
            MessagePair pair = pairs.get(i);
            long pairLen = (long) pair.userContent.length() + (long) pair.assistantContent.length();
            if (pairLen <= remaining) {
                remaining -= pairLen;
                keptDesc.add(pair);
            } else {
                break;
            }
        }

        // 4. 恢复升序
        CollectionUtil.reverse(keptDesc);

        // 5. 构造 Spring AI 消息：USER -> UserMessage, ASSISTANT -> AssistantMessage
        for (MessagePair pair : keptDesc) {
            result.add(new UserMessage(pair.userContent));
            result.add(new AssistantMessage(pair.assistantContent));
        }

        // 6. 追加当前 CurrentMessage
        result.add(new UserMessage(currentMessage));

        return result;
    }

    /**
     * 校验升序历史，并配对成（USER + ASSISTANT）的整轮列表
     */
    private List<MessagePair> validateAndPair(List<AgentMessage> history) {
        if (history == null) {
            throw new IllegalStateException("历史消息列表不能为空");
        }

        int size = history.size();
        if (size % 2 != 0) {
            throw new IllegalStateException("History must contain complete USER/ASSISTANT pairs, got odd size=" + size);
        }

        List<MessagePair> pairs = new ArrayList<>(size / 2);
        Long lastConversationId = null;
        Long lastTurnNo = null;

        for (int i = 0; i < size; i += 2) {
            AgentMessage user = history.get(i);
            AgentMessage assistant = history.get(i + 1);

            // null 项校验
            if (user == null || assistant == null) {
                throw new IllegalStateException("History contains null entry near index " + i);
            }

            // 角色校验
            if (!AgentMessageRole.USER.name().equals(user.getRole())) {
                throw new IllegalStateException(
                        "Expected USER at index " + i + " but got role=" + user.getRole());
            }

            if (!AgentMessageRole.ASSISTANT.name().equals(assistant.getRole())) {
                throw new IllegalStateException(
                        "Expected ASSISTANT at index " + (i + 1)
                                + " but got role=" + assistant.getRole());
            }

            // 空白正文校验
            String userContent = user.getContent();
            String assistantContent = assistant.getContent();
            if (StrUtil.isBlank(userContent) || StrUtil.isBlank(assistantContent)) {
                throw new IllegalStateException("Blank ASSISTANT content at index " + (i + 1));
            }

            // 同一对必须同属于一个会话、同轮次
            Long userConversationId = user.getConversationId();
            Long assistantConversationId = assistant.getConversationId();
            if (!Objects.equals(userConversationId, assistantConversationId)) {
                throw new IllegalStateException("Session mismatch inside pair at index " + i);
            }

            Long userTurnNo = user.getTurnNo();
            Long assistantTurnNo = assistant.getTurnNo();
            if (!Objects.equals(userTurnNo, assistantTurnNo)) {
                throw new IllegalStateException("Turn mismatch inside pair at index " + i);
            }

            // 顺序校验：会话不变、轮次严格递增
            long currentTurnNo = userTurnNo;
            if (lastTurnNo != null) {
                if (!Objects.equals(lastConversationId, userConversationId)) {
                    throw new IllegalStateException("Session changed inside history at index " + i);
                }
                if (currentTurnNo <= lastTurnNo) {
                    throw new IllegalStateException(
                            "Turn order not ascending at index " + i
                                    + ", previous=" + lastTurnNo + ", current=" + currentTurnNo);
                }
            }

            lastConversationId = userConversationId;
            lastTurnNo = currentTurnNo;

            pairs.add(new MessagePair(userContent, assistantContent));
        }

        return pairs;
    }

    private static final class MessagePair {
        final String userContent;
        final String assistantContent;

        MessagePair(String userContent, String assistantContent) {
            this.userContent = userContent;
            this.assistantContent = assistantContent;
        }
    }
}
