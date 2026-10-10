package cn.coder.sanwei.enterpriseaiassistant.exception;

/**
 * ConversationConflictException
 *
 * @author caiyang
 * @date 2026/10/9
 */
public class ConversationConflictException extends RuntimeException {
    public ConversationConflictException(String message) {
        super(message);
    }
}
