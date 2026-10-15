package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PermissionServiceImplTest {

    private final PermissionServiceImpl permissions = new PermissionServiceImpl();
    private final AgentExecutionContext reader =
            new AgentExecutionContext(301L, 1L, Set.of("knowledge:read"));

    @Test
    void shouldDenyMissingIdentity() {
        assertFalse(permissions.hasAnyPermissions(null, "knowledge:read"));
    }

    @ParameterizedTest
    @MethodSource("emptyRequirements")
    void shouldDenyMissingRequiredPermissions(String[] required) {
        assertFalse(permissions.hasAnyPermissions(reader, required));
    }

    static Stream<Arguments> emptyRequirements() {
        return Stream.of(Arguments.of((Object) null), Arguments.of((Object) new String[0]));
    }

    @Test
    void shouldAcceptAnyMatchingPermission() {
        assertTrue(permissions.hasAnyPermissions(reader, "knowledge:write", "knowledge:read"));
    }

    @Test
    void shouldKeepReadAndWritePermissionsSeparate() {
        assertFalse(permissions.hasAnyPermissions(reader, "knowledge:write"));
    }

    @Test
    void shouldDenyIdentityWithoutPermissions() {
        var viewer = new AgentExecutionContext(103L, 1L, Set.of());
        assertFalse(permissions.hasAnyPermissions(viewer, "knowledge:read", "knowledge:write"));
    }
}
