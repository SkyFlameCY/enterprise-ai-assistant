package cn.coder.sanwei.enterpriseaiassistant.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * BusinessTicket
 *
 * @author caiyang
 * @date 2026/10/8
 */
@Data
public class BusinessTicket {

    private Long id;

    private Long tenantId;

    private String ticketNo;

    private String projectCode;

    private String title;

    private String description;

    private String status;

    private String priority;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
