package cn.coder.sanwei.enterpriseaiassistant.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * BusinessProject
 *
 * @author caiyang
 * @date 2026/10/8
 */
@Data
public class BusinessProject {

    private Long id;

    private Long tenantId;

    private String projectCode;

    private String name;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
