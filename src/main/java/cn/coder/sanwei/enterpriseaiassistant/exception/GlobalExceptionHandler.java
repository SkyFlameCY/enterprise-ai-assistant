package cn.coder.sanwei.enterpriseaiassistant.exception;

import cn.coder.sanwei.enterpriseaiassistant.error.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * GlobalExceptionHandler
 *
 * @author caiyang
 * @date 2026/9/10
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handlerValidation(MethodArgumentNotValidException e) {

        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数不合法");

        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", message));
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiError> handlerAiService(AiServiceException e) {

        log.error("AI service call failed", e);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("AI_SERVICE_ERROR", "AI 服务暂时不可用"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handlerUnknown(Exception e) {

        log.error("Unhandled service error", e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "服务器内部错误"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handlerAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("ACCESS_DENIED", "没有访问该功能的权限"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handlerUnreadableRequest(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "请求体缺失、JSON 格式错误或字段类型不正确"));
    }

    @ExceptionHandler(DocumentAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleDocumentAlreadyExists(DocumentAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DOCUMENT_ALREADY_EXISTS", e.getMessage()));
    }

    @ExceptionHandler(ConversationNotAccessibleException.class)
    public ResponseEntity<ApiError> handlerConversationNotAccessible(ConversationNotAccessibleException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("CONVERSATION_NOT_ACCESSIBLE", e.getMessage()));
    }

    @ExceptionHandler(ConversationStateException.class)
    public ResponseEntity<ApiError> handlerConversationState(ConversationStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CONVERSATION_STATE_ERROR", e.getMessage()));
    }

    @ExceptionHandler(ConversationConflictException.class)
    public ResponseEntity<ApiError> handlerConversationConflict(ConversationConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CONVERSATION_CONFLICT", e.getMessage()));
    }
}
