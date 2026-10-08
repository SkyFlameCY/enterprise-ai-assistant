package cn.coder.sanwei.enterpriseaiassistant.context;

import java.util.Set;

/**
 * AgentExecutionContext
 *
 * @author caiyang
 * @date 2026/10/2
 */
public record AgentExecutionContext(
        long userId,
        long tenantId,
        Set<String> permissions
) {

    public AgentExecutionContext {
        if (userId <=0 || tenantId <= 0) {
            throw new IllegalArgumentException("用户和租户编号必须为正数");
        }

        if (permissions == null) {
            throw new IllegalArgumentException("权限集合不能为空");
        }

        permissions = Set.copyOf(permissions);
    }
}
