package cn.coder.sanwei.enterpriseaiassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CreateTicketParams
 *
 * @author caiyang
 * @date 2026/10/21
 */
public record CreateTicketParams(
        @NotBlank(message = "projectCode 必须非空白")
        @Size(max = 64, message = "projectCode 最多 64 个字符")
        String projectCode,
        @NotBlank(message = "title 必须非空白")
        @Size(max = 255, message = "title 最多 255 个字符")
        String title,
        @NotBlank(message = "description 必须非空白")
        @Size(max = 4000, message = "description 最多 4000 个字符")
        String description,
        @NotBlank
        String priority
) {
}
