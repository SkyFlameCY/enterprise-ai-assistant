package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.config.TimeConfig;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.CreateTicketParams;
import cn.coder.sanwei.enterpriseaiassistant.dto.PendingActionPreview;
import cn.coder.sanwei.enterpriseaiassistant.entity.PendingAgentAction;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcPendingActionRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationService;
import cn.coder.sanwei.enterpriseaiassistant.service.PendingActionService;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * PendingActionServiceImpl
 *
 * @author caiyang
 * @date 2026/10/23
 */
@Service
public class PendingActionServiceImpl implements PendingActionService {

    private final JdbcPendingActionRepository pendingActionRepository;

    private final ConversationService conversationService;

    private final TimeConfig timeConfig;

    public PendingActionServiceImpl(
            JdbcPendingActionRepository pendingActionRepository,
            ConversationService conversationService,
            TimeConfig timeConfig
    ) {
        this.pendingActionRepository = pendingActionRepository;
        this.conversationService = conversationService;
        this.timeConfig = timeConfig;
    }

    @Override
    @Transactional
    public PendingActionPreview prepareCreateTicketDraft(long conversationId, UUID requestId, CreateTicketParams params, AgentExecutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("身份上下文不能为空");
        }

        if (!context.permissions().contains("ticket:create")) {
            throw new AccessDeniedException("没有工单创建权限");
        }

        if (conversationId <= 0 || requestId == null || params == null) {
            throw new IllegalArgumentException("conversationId 必须为正数且requestId 非空，params 不为空");
        }

        if (StrUtil.isBlank(params.projectCode()) || params.projectCode().length() > 64) {
            throw new IllegalArgumentException("projectCode 必须非空且大小不超过 64 个字符");
        }

        if (StrUtil.isBlank(params.title()) || params.title().length() > 255) {
            throw new IllegalArgumentException("title 必须非空且大小不超过 255 个字符");
        }

        if (StrUtil.isBlank(params.description()) || params.description().length() > 4000) {
            throw new IllegalArgumentException("description 必须非空且大小不超过 4000 个字符");
        }

        if (StrUtil.isBlank(params.priority().trim()) || !List.of("LOW", "MEDIUM", "HIGH").contains(params.priority().trim())) {
            throw new IllegalArgumentException("priority 必须非空且必须为 LOW/MEDIUM/HIGH");
        }

        conversationService.requireActiveOwnedConversation(conversationId, context);

        String hashString = paramsToJson(params);

        String previewHash = Hashing.sha256().hashString(hashString, StandardCharsets.UTF_8).toString();
        PendingAgentAction action = new PendingAgentAction();
        action.setId(UUID.randomUUID());
        action.setTenantId(context.tenantId());
        action.setUserId(context.userId());
        action.setConversationId(conversationId);
        action.setRequestId(requestId);
        action.setParamsJson(hashString);
        action.setPreviewHash(previewHash);
        action.setActionType("CREATE_TICKET");
        action.setStatus("DRAFT");
        OffsetDateTime expiresAt = OffsetDateTime.now(timeConfig.clock()).plusMinutes(10);
        action.setExpiresAt(expiresAt);
        UUID actionId = pendingActionRepository.insertDraftIfAbsent(action);

        return new PendingActionPreview(
                actionId,
                "CREATE_TICKET",
                "DRAFT",
                params,
                previewHash,
                expiresAt
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PendingActionPreview> activeDraftsForTurn(long conversationId, UUID requestId, long turnNo, AgentExecutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("身份上下文不能为空");
        }

        if (conversationId <= 0 || requestId == null || turnNo <= 0) {
            throw new IllegalArgumentException("conversationId 必须为正数且requestId 非空，turnNo 必须为正数");
        }

        List<PendingAgentAction> draft = pendingActionRepository.findDraftByRequest(conversationId, requestId, context.tenantId(), context.userId());
        if (CollectionUtil.isEmpty(draft)) {
            return List.of();
        }

        // 有草稿再检查创建权限以及有效期
        if (!context.permissions().contains("ticket:create")) {
            throw new AccessDeniedException("没有工单创建权限");
        }

        List<PendingActionPreview> result = new ArrayList<>();
        for (PendingAgentAction action : draft) {
            int row = pendingActionRepository.activeDraft(action.getId(), conversationId, requestId, context.tenantId(), context.userId(), turnNo, OffsetDateTime.now());
            if (row != 1) {
                throw new AiServiceException("更新出错");
            }
        }


        return null;
    }

    private String paramsToJson(CreateTicketParams params) {
        if (params == null
                || StrUtil.isBlank(params.projectCode())
                || StrUtil.isBlank(params.description())
                || StrUtil.isBlank(params.title())
                || StrUtil.isBlank(params.priority())) {
            throw new IllegalArgumentException("CreateTicketParams 相应参数不能为空");
        }

        Map<String, Object> hashMap = new LinkedHashMap<>();
        hashMap.put("projectCode", params.projectCode());
        hashMap.put("title", params.title());
        hashMap.put("description", params.description());
        hashMap.put("priority", params.priority());

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.writeValueAsString(hashMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Json 转换错误");
        }
    }
}
