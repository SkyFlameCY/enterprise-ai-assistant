package cn.coder.sanwei.enterpriseaiassistant.dto;

import jakarta.validation.constraints.*;

/**
 * KnowledgeImportRequest
 *
 * @author caiyang
 * @date 2026/10/1
 */
public record KnowledgeImportRequest(

        @NotBlank(message = "documentKey 不能为空")
        @Size(max = 128, message = "documentKey 最长 128 个字符")
        String documentKey,

        @NotNull(message = "version 不能为空")
        @Min(value = 1, message = "version 至少为 1")
        Integer version,

        @NotBlank(message = "title 不能为空")
        @Size(max = 255, message = "title 最长 255 个字符")
        String title,

        @Size(max = 1024, message = "sourceRef 最长 1024 个字符")
        String sourceRef,

        @NotBlank(message = "text 不能为空")
        @Size(max = 20000, message = "text 最长 20000 个字符")
        String text
) {
}
