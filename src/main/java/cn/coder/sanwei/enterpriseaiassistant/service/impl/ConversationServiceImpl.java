package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentConversation;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationConflictException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationNotAccessibleException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationStateException;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcConversationRepository;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationService;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ConversationServiceImpl
 *
 * @author caiyang
 * @date 2026/10/9
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private final JdbcConversationRepository conversationRepository;
    private final JdbcMessageRepository messageRepository;

    public ConversationServiceImpl(JdbcConversationRepository conversationRepository,
                                   JdbcMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public AgentConversation requireActiveOwnedConversation(long conversationId, AgentExecutionContext context) {
        if (conversationId <= 0 || context == null) {
            throw new IllegalArgumentException("conversationId 必须为正数，且身份上下文不能为空");
        }

        AgentConversation conversation = conversationRepository.findOwnedConversation(conversationId, context.tenantId(), context.userId());
        if (conversation == null) {
            throw new ConversationNotAccessibleException("未找到可访问的会话");
        }

        if (ObjectUtil.notEqual(conversation.getStatus(), "ACTIVE")) {
            throw new ConversationStateException("会话当前状态不允许继续对话");
        }

        return conversation;
    }

    @Override
    @Transactional
    public long saveCompletedTurn(long conversationId, long expectedVersion, String userContent, String assistantContent, AgentExecutionContext context) {
        if (conversationId <= 0 || context == null
                || expectedVersion < 0 || expectedVersion == Long.MAX_VALUE
                || StrUtil.isBlank(userContent) || StrUtil.isBlank(assistantContent)) {
            throw new IllegalArgumentException("conversationId 必须为正数，且身份上下文不得为空，且 expectedVersion 必须非负且不等于 Long.MAX_VALUE ，且 userContent 和 assistantContent 非 null 且非空白");
        }

        requireActiveOwnedConversation(conversationId, context);

        int flag = conversationRepository.advanceVersionIfActiveOwned(conversationId, context.tenantId(), context.userId(), expectedVersion);
        if (flag != 1) {
            throw new ConversationConflictException("会话已发生变化，请重新发起对话");
        }

        long maxTurnNo = messageRepository.findMaxOwnedTurnNo(conversationId, context.tenantId(), context.userId());
        long nextTurnNo;
        try {
            nextTurnNo = Math.addExact(maxTurnNo, 1L);
        } catch (ArithmeticException e) {
            throw new ConversationStateException("会话轮次已达上限，无法继续保存");
        }

        int result = messageRepository.insertTurn(conversationId, nextTurnNo, userContent, assistantContent);
        if (result != 2) {
            throw new IllegalStateException("整轮消息写入数量异常");
        }
        return nextTurnNo;
    }

    @Override
    public long createConversation(AgentExecutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("身份上下文不能为空");
        }

        Long conversationId = conversationRepository.createConversation(context.tenantId(), context.userId(), "新会话");
        if (conversationId == null || conversationId <=0 ) {
            throw new IllegalStateException("会话创建异常");
        }
        return conversationId;
    }
}
