package cn.coder.sanwei.enterpriseaiassistant.dto;

/**
 * TicketDetail
 *
 * @author caiyang
 * @date 2026/10/3
 */
public record TicketDetail(
        String ticketNo,
        String projectCode,
        String title,
        String status,
        String priority,
        String description
) {
}
