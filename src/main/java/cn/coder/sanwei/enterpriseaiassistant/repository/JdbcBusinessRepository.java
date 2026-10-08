package cn.coder.sanwei.enterpriseaiassistant.repository;

import cn.coder.sanwei.enterpriseaiassistant.dto.ProjectSummary;
import cn.coder.sanwei.enterpriseaiassistant.dto.TicketDetail;
import cn.coder.sanwei.enterpriseaiassistant.dto.TicketSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JdbcBusinessRepository
 *
 * @author caiyang
 * @date 2026/10/8
 */
@Repository
public class JdbcBusinessRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcBusinessRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ProjectSummary findProject(long tenantId, String projectCode) {
        String sql = "SELECT project_code, name, status " +
                "FROM business_project " +
                "WHERE tenant_id = ? " +
                "AND project_code = ?";

        List<ProjectSummary> rows = jdbcTemplate.query(
                sql,
                ((rs, rowNum) -> new ProjectSummary(
                        rs.getString("project_code"),
                        rs.getString("name"),
                        rs.getString("status")
                )),
                tenantId,
                projectCode);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<TicketSummary> findTickets(long tenantId, String projectCode, String status) {
        String sql = "SELECT ticket_no, project_code, title, status, priority " +
                "FROM business_ticket " +
                "WHERE tenant_id = ? " +
                "AND project_code = ? " +
                "AND status = ? " +
                "ORDER BY id ASC " +
                "LIMIT 10";

        return jdbcTemplate.query(
                sql,
                ((rs, rowNum) -> new TicketSummary(
                        rs.getString("ticket_no"),
                        rs.getString("project_code"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getString("priority")
                )),
                tenantId,
                projectCode,
                status
        );
    }

    public TicketDetail findTicketDetail(long tenantId, String ticketNo) {
        String sql = "SELECT ticket_no, project_code, title, status, priority, description " +
                "FROM business_ticket " +
                "WHERE tenant_id = ? " +
                "AND ticket_no = ? ";
        List<TicketDetail> rows = jdbcTemplate.query(
                sql,
                ((rs, rowNum) -> new TicketDetail(
                        rs.getString("ticket_no"),
                        rs.getString("project_code"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getString("priority"),
                        rs.getString("description")
                )),
                tenantId,
                ticketNo
        );

        return rows.isEmpty() ? null : rows.getFirst();
    }
}
