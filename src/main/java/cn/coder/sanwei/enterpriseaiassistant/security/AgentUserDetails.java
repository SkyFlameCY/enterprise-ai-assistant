package cn.coder.sanwei.enterpriseaiassistant.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * AgentUserDetails
 *
 * @author caiyang
 * @date 2026/10/2
 */
@Getter
public class AgentUserDetails extends User {

    private final long userId;
    private final long tenantId;

    public AgentUserDetails(
            long userId,
            long tenantId,
            String username,
            String passwordHash,
            Collection<? extends GrantedAuthority> authorities
    ) {
        super(username, passwordHash, authorities);

        if (userId <=0 || tenantId <= 0) {
            throw new IllegalArgumentException("用户和租户编号必须为正数");
        }

        this.userId = userId;
        this.tenantId = tenantId;
    }
}
