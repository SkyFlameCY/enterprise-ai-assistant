package cn.coder.sanwei.enterpriseaiassistant.dto;

/**
 * TicketSummary
 *
 * @author caiyang
 * @date 2026/10/2
 */
public record TicketSummary(
        String ticketNo,
        String projectCode,
        String title,
        String status,
        String priority
) {
}
