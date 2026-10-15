package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;

/**
 * PermissionService
 * 权限 Service 接口
 * @author caiyang
 * @date 2026/10/14
 */
public interface PermissionService {

    /**
     * 判断是否有权限，任一一个即可
     * @param context 用户上下文
     * @param permissions 权限
     * @return 是否
     */
    boolean hasAnyPermissions(AgentExecutionContext context, String ...permissions);
}
