package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.config.AgentMemoryProperties;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentMessage;
import cn.coder.sanwei.enterpriseaiassistant.memory.ConversationHistoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实历史组装组件：角色、顺序、预算、当前输入一次；不调用模型或数据库。 */
class ConversationHistoryBuilderTest {

    private ConversationHistoryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ConversationHistoryBuilder(properties(16000));
    }

    @Test
    void shouldAppendCurrentInputExactlyOnceWhenHistoryIsEmpty() {
        List<Message> result = builder.build(List.of(), "查询项目");

        assertEquals(List.of(MessageType.USER), roles(result));
        assertEquals(List.of("查询项目"), texts(result));
    }

    @Test
    void shouldPreserveRolesChronologicalOrderAndLiteralContent() {
        String current = "  它的工单呢？\n继续查询  ";
        List<AgentMessage> history = List.of(row(1, "USER", "第一问"), row(1, "ASSISTANT", "第一答"),
                row(7, "USER", "第七问"), row(7, "ASSISTANT", "第七答"));

        List<Message> result = builder.build(history, current);

        assertEquals(List.of(MessageType.USER, MessageType.ASSISTANT, MessageType.USER,
                MessageType.ASSISTANT, MessageType.USER), roles(result));
        assertEquals(List.of("第一问", "第一答", "第七问", "第七答", current), texts(result));
        assertEquals("第一问", history.getFirst().getContent());
        assertEquals(1L, history.getFirst().getTurnNo());
    }

    @Test
    void shouldDiscardOldWholeTurnsAndReserveSpaceForCurrentInput() {
        var limited = new ConversationHistoryBuilder(properties(4000));
        String current = "问".repeat(1000);
        List<AgentMessage> history = List.of(row(1, "USER", "旧问".repeat(500)),
                row(1, "ASSISTANT", "旧答".repeat(500)), row(2, "USER", "新問".repeat(500)),
                row(2, "ASSISTANT", "新答".repeat(500)));

        List<Message> result = limited.build(history, current);

        assertEquals(List.of(history.get(2).getContent(), history.get(3).getContent(), current), texts(result));
        assertTrue(totalChars(result) <= 4000);
        assertEquals(4, history.size(), "预算只影响提示词，不能删数据库历史或输入列表");
    }

    @Test
    void shouldKeepAWholePairAtExactBudgetBoundary() {
        var limited = new ConversationHistoryBuilder(properties(4000));
        String current = "问".repeat(1000);
        List<Message> result = limited.build(List.of(row(1, "USER", "u".repeat(1500)),
                row(1, "ASSISTANT", "a".repeat(1500))), current);

        assertEquals(3, result.size());
        assertEquals(4000, totalChars(result));
    }

    @Test
    void shouldNotSkipOversizedLatestTurnAndReuseAnOlderTurn() {
        var limited = new ConversationHistoryBuilder(properties(4000));
        List<AgentMessage> history = List.of(row(1, "USER", "旧问"), row(1, "ASSISTANT", "旧答"),
                row(2, "USER", "u".repeat(2000)), row(2, "ASSISTANT", "a".repeat(2000)));

        assertEquals(List.of("当前问"), texts(limited.build(history, "当前问")));
    }

    @Test
    void shouldLeaveOnlyCurrentInputWhenItConsumesWholeBudget() {
        var limited = new ConversationHistoryBuilder(properties(4000));
        String current = "问".repeat(4000);

        assertEquals(List.of(current), texts(limited.build(
                List.of(row(1, "USER", "旧问"), row(1, "ASSISTANT", "旧答")), current)));
    }

    @Test
    void shouldRejectCurrentInputThatAloneExceedsContextBudget() {
        var limited = new ConversationHistoryBuilder(properties(4000));

        assertThrows(IllegalArgumentException.class, () -> limited.build(List.of(), "问".repeat(4001)));
    }

    @Test
    void shouldRejectNullHistoryAsInvalidInternalStateRatherThanDereferencingIt() {
        assertThrowsExactly(IllegalStateException.class, () -> builder.build(null, "当前问"));
    }

    @ParameterizedTest
    @MethodSource("invalidHistory")
    void shouldRejectMalformedHistoryWithoutSilentlyReassigningRoles(List<AgentMessage> history) {
        assertThrowsExactly(IllegalStateException.class, () -> builder.build(history, "当前问"));
    }

    static Stream<Arguments> invalidHistory() {
        AgentMessage foreign = row(1, "ASSISTANT", "答");
        foreign.setConversationId(88L);
        return Stream.of(
                Arguments.of(List.of(row(1, "USER", "问"))),
                Arguments.of(Arrays.asList(null, row(1, "ASSISTANT", "答"))),
                Arguments.of(Arrays.asList(row(1, "USER", "问"), null)),
                Arguments.of(List.of(row(1, "USER", "问"), foreign)),
                Arguments.of(List.of(row(1, "USER", "问"), row(2, "ASSISTANT", "答"))),
                Arguments.of(List.of(row(1, "USER", "问"), row(1, "ASSISTANT", "答"),
                        row(1, "USER", "重复问"), row(1, "ASSISTANT", "重复答"))),
                Arguments.of(List.of(row(2, "USER", "问"), row(2, "ASSISTANT", "答"),
                        row(1, "USER", "倒序问"), row(1, "ASSISTANT", "倒序答")))
        );
    }

    @ParameterizedTest
    @CsvSource({"USER, USER", "ASSISTANT, ASSISTANT", "ASSISTANT, USER", "SYSTEM, ASSISTANT",
            "USER, TOOL", "user, ASSISTANT"})
    void shouldRequireExactUserThenAssistantPositions(String firstRole, String secondRole) {
        assertThrowsExactly(IllegalStateException.class, () -> builder.build(
                List.of(row(1, firstRole, "第一条"), row(1, secondRole, "第二条")), "当前问"));
    }

    @ParameterizedTest
    @MethodSource("blankBodies")
    void shouldRejectEitherBlankHistoricalBody(String first, String second) {
        assertThrowsExactly(IllegalStateException.class, () -> builder.build(
                List.of(row(1, "USER", first), row(1, "ASSISTANT", second)), "当前问"));
    }

    static Stream<Arguments> blankBodies() {
        return Stream.of(null, "", "\n\t").flatMap(blank -> Stream.of(
                Arguments.of(blank, "答"), Arguments.of("问", blank)));
    }

    @Test
    void shouldRejectChangingConversationBetweenOtherwiseValidPairs() {
        List<AgentMessage> history = new ArrayList<>(List.of(row(1, "USER", "问"), row(1, "ASSISTANT", "答"),
                row(2, "USER", "另一会话问"), row(2, "ASSISTANT", "另一会话答")));
        history.get(2).setConversationId(88L);
        history.get(3).setConversationId(88L);

        assertThrowsExactly(IllegalStateException.class, () -> builder.build(history, "当前问"));
    }

    private static AgentMemoryProperties properties(int maxChars) {
        var properties = new AgentMemoryProperties();
        properties.setTurnLimit(5);
        properties.setMaxContextChars(maxChars);
        properties.setMaxAssistantChars(8000);
        return properties;
    }

    private static AgentMessage row(long turn, String role, String content) {
        AgentMessage row = new AgentMessage();
        row.setConversationId(77L);
        row.setTurnNo(turn);
        row.setRole(role);
        row.setContent(content);
        return row;
    }

    private static List<MessageType> roles(List<Message> messages) {
        return messages.stream().map(Message::getMessageType).toList();
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }

    private static long totalChars(List<Message> messages) {
        return messages.stream().mapToLong(message -> message.getText().length()).sum();
    }
}
