package cn.coder.sanwei.enterpriseaiassistant.enums;

/**
 * AgentMessageRole
 *
 * @author caiyang
 * @date 2026/10/10
 */
public enum AgentMessageRole {
    USER,
    ASSISTANT;

    public static boolean contains(String name) {
        try {
            AgentMessageRole.valueOf(name);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
