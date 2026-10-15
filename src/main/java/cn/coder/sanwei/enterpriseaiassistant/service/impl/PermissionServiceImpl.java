package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.service.PermissionService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import org.springframework.stereotype.Service;

/**
 * PermissionServiceImpl
 *
 * @author caiyang
 * @date 2026/10/14
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    @Override
    public boolean hasAnyPermissions(AgentExecutionContext context, String... permissions) {
        if (context == null || ArrayUtil.isEmpty(permissions)) {
            return false;
        }

        // 遍历判断每个权限，如果有一个满足，说明有权限
        for (String permission : permissions) {
            if (CollUtil.contains(context.permissions(), permission)) {
                return true;
            }
        }

        return false;
    }
}
