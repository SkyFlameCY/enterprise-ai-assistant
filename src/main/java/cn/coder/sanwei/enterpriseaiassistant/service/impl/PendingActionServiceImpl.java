package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.config.TimeConfig;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.CreateTicketParams;
import cn.coder.sanwei.enterpriseaiassistant.dto.PendingActionPreview;
import cn.coder.sanwei.enterpriseaiassistant.dto.ProjectSummary;
import cn.coder.sanwei.enterpriseaiassistant.entity.PendingAgentAction;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationStateException;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcActionAuditRepository;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcBusinessRepository;
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

    private final JdbcActionAuditRepository actionAuditRepository;

    private final ObjectMapper objectMapper;

    private final JdbcBusinessRepository businessRepository;

    public PendingActionServiceImpl(
            JdbcPendingActionRepository pendingActionRepository,
            ConversationService conversationService,
            TimeConfig timeConfig,
            JdbcActionAuditRepository actionAuditRepository,
            ObjectMapper objectMapper,
            JdbcBusinessRepository businessRepository
    ) {
        this.pendingActionRepository = pendingActionRepository;
        this.conversationService = conversationService;
        this.actionAuditRepository = actionAuditRepository;
        this.timeConfig = timeConfig;
        this.objectMapper = objectMapper;
        this.businessRepository = businessRepository;
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

        if (StrUtil.isBlank(params.projectCode())
                || StrUtil.isBlank(params.title())
                || StrUtil.isBlank(params.description())
                || StrUtil.isBlank(params.priority())) {
            throw new IllegalArgumentException("项目编号、标题、描述、优先级不能为空");
        }

        CreateTicketParams normalized = new CreateTicketParams(
                params.projectCode().strip(),
                params.title().strip(),
                params.description().strip(),
                params.priority().strip()
        );

        if (normalized.projectCode().length() > 64) {
            throw new IllegalArgumentException("项目编号不能超过 64 个字符");
        }

        if (normalized.title().length() > 255) {
            throw new IllegalArgumentException("标题不能超过 255 个字符");
        }

        if (normalized.description().length() > 4000) {
            throw new IllegalArgumentException("描述不能超过 4000 个字符");
        }

        if (!List.of("LOW", "MEDIUM", "HIGH").contains(normalized.priority())) {
            throw new IllegalArgumentException("优先级必须为 LOW/MEDIUM/HIGH");
        }

        conversationService.requireActiveOwnedConversation(conversationId, context);
        ProjectSummary project = businessRepository.findProject(context.tenantId(), normalized.projectCode());
        if (project == null || !Objects.equals(project.status(), "ACTIVE")) {
            throw new IllegalArgumentException("对应项目不存在或状态不为 ACTIVE");
        }

        OffsetDateTime now = OffsetDateTime.now(timeConfig.clock());

        String hashString = paramsToJson(normalized);

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
        OffsetDateTime expiresAt = now.plusMinutes(10);
        action.setExpiresAt(expiresAt);
        UUID actionId = pendingActionRepository.insertDraftIfAbsent(action);
        if (actionId == null) {
            PendingAgentAction dbAction = pendingActionRepository.findByRequestAndHash(action.getTenantId(), action.getUserId(), requestId, action.getActionType(), action.getPreviewHash());
            if (dbAction == null
                    || dbAction.getConversationId() != conversationId
                    || !Objects.equals(dbAction.getStatus(), "DRAFT") || !dbAction.getExpiresAt().isAfter(now)) {
                throw new AiServiceException("工单草稿未正确插入");
            }
            return new PendingActionPreview(
                    dbAction.getId(),
                    dbAction.getActionType(),
                    dbAction.getStatus(),
                    jsonToParams(dbAction.getParamsJson()),
                    dbAction.getPreviewHash(),
                    dbAction.getExpiresAt()
            );
        }

        int row = actionAuditRepository.insert(actionId, action.getTenantId(), action.getUserId(), "DRAFT_CREATED", action.getPreviewHash(), null);
        if (row != 1) {
            throw new ConversationStateException("创建工单草稿审计失败");
        }

        return new PendingActionPreview(
                actionId,
                "CREATE_TICKET",
                "DRAFT",
                normalized,
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
            int row = pendingActionRepository.activeDraft(action.getId(), conversationId, requestId, context.tenantId(), context.userId(), turnNo, OffsetDateTime.now(timeConfig.clock()));
            if (row != 1) {
                throw new ConversationStateException("更新出错");
            }
            int auditRow = actionAuditRepository.insert(action.getId(), action.getTenantId(), action.getUserId(), "PREVIEW_READY", action.getPreviewHash(), null);
            if (auditRow != 1) {
                throw new ConversationStateException("创建工单草稿激活审计失败");
            }

            CreateTicketParams createTicketParams = jsonToParams(action.getParamsJson());
            result.add(new PendingActionPreview(action.getId(), action.getActionType(), "PENDING_CONFIRMATION", createTicketParams, action.getPreviewHash(), action.getExpiresAt()));
        }

        return List.copyOf(result);
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

        try {
            return objectMapper.writeValueAsString(hashMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Json 转换错误");
        }
    }

    private CreateTicketParams jsonToParams(String jsonString) {
        if (StrUtil.isBlank(jsonString)) {
            throw new IllegalArgumentException("params 不能为空");
        }

        try {
            return objectMapper.readValue(jsonString, CreateTicketParams.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("草稿参数 JSON 无效", e);
        }
    }
}
