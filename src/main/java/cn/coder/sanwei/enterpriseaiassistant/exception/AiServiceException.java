package cn.coder.sanwei.enterpriseaiassistant.exception;

/**
 * AiServiceException
 *
 * @author caiyang
 * @date 2026/9/10
 */
public class AiServiceException extends RuntimeException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
