package com.geekmall.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import com.geekmall.common.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
class ServiceCenterController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String internalKey;

    ServiceCenterController(JdbcTemplate jdbc, ObjectMapper mapper,
                            @Value("${geek.internal-key}") String internalKey) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.internalKey = internalKey;
    }

    @PostMapping("/service/sessions")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> openSession(@RequestBody(required = false) OpenSession body,
                                                 @AuthenticationPrincipal AuthPrincipal p,
                                                 HttpServletRequest r) {
        long skillId = body != null && body.skillGroupId() != null ? body.skillGroupId() : 91001L;
        long id = DistributedId.next();
        String no = "SS" + id;
        Instant now = Instant.now();
        Long agentId = pickAgent(skillId);
        String status = agentId == null ? "QUEUED" : "ASSIGNED";
        int queue = agentId == null ? nextQueue(skillId) : 0;
        jdbc.update("""
                INSERT INTO service_session(id,session_no,user_id,agent_id,skill_group_id,status,queue_position,context_json,created_at,assigned_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, id, no, p.userId(), agentId, skillId, status, queue,
                json(Map.of("source", body == null ? "APP" : Optional.ofNullable(body.source()).orElse("APP"))),
                Timestamp.from(now), agentId == null ? null : Timestamp.from(now), Timestamp.from(now));
        if (agentId != null) {
            appendMessage(id, "SYSTEM", 0, "TEXT", "客服已接入，会话编号 " + no);
        } else {
            appendMessage(id, "SYSTEM", 0, "TEXT", "当前排队中，请稍候");
        }
        return ok(sessionDetail(no, p.userId(), false), r);
    }

    @GetMapping("/service/sessions/{sessionNo}")
    @PreAuthorize("hasAnyRole('USER','SYSTEM_ADMIN','CS_AGENT')")
    ApiResponse<Map<String, Object>> session(@PathVariable String sessionNo,
                                             @AuthenticationPrincipal AuthPrincipal p,
                                             HttpServletRequest r) {
        boolean staff = p.hasRole("SYSTEM_ADMIN") || p.hasRole("CS_AGENT");
        return ok(sessionDetail(sessionNo, p.userId(), staff), r);
    }

    @GetMapping("/service/sessions/{sessionNo}/messages")
    @PreAuthorize("hasAnyRole('USER','SYSTEM_ADMIN','CS_AGENT')")
    ApiResponse<List<Map<String, Object>>> messages(@PathVariable String sessionNo,
                                                    @AuthenticationPrincipal AuthPrincipal p,
                                                    HttpServletRequest r) {
        Map<String, Object> session = loadSession(sessionNo);
        boolean staff = p.hasRole("SYSTEM_ADMIN") || p.hasRole("CS_AGENT");
        if (!staff && !Objects.equals(((Number) session.get("user_id")).longValue(), p.userId())) {
            throw new BusinessException("SC40301", "无权查看该会话");
        }
        long sid = ((Number) session.get("id")).longValue();
        return ok(jdbc.queryForList("""
                SELECT sender_type AS senderType, sender_id AS senderId, content_type AS contentType,
                       content, sent_at AS sentAt
                FROM service_message WHERE session_id=? ORDER BY sent_at
                """, sid), r);
    }

    @PostMapping("/service/sessions/{sessionNo}/messages")
    @PreAuthorize("hasAnyRole('USER','SYSTEM_ADMIN','CS_AGENT')")
    @Transactional
    ApiResponse<Map<String, Object>> send(@PathVariable String sessionNo,
                                          @Valid @RequestBody ChatBody body,
                                          @AuthenticationPrincipal AuthPrincipal p,
                                          HttpServletRequest r) {
        Map<String, Object> session = loadSession(sessionNo);
        boolean staff = p.hasRole("SYSTEM_ADMIN") || p.hasRole("CS_AGENT");
        long userId = ((Number) session.get("user_id")).longValue();
        if (!staff && userId != p.userId()) {
            throw new BusinessException("SC40301", "无权发送消息");
        }
        String senderType = staff ? "AGENT" : "USER";
        appendMessage(((Number) session.get("id")).longValue(), senderType, p.userId(), "TEXT", body.content());
        jdbc.update("UPDATE service_session SET updated_at=CURRENT_TIMESTAMP WHERE id=?", session.get("id"));
        return ok(Map.of("sessionNo", sessionNo, "status", "SENT"), r);
    }

    @PostMapping("/service/sessions/{sessionNo}/transfer")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','CS_AGENT')")
    @Transactional
    ApiResponse<Map<String, Object>> transfer(@PathVariable String sessionNo,
                                              @RequestParam long targetAgentId,
                                              @AuthenticationPrincipal AuthPrincipal p,
                                              HttpServletRequest r) {
        Map<String, Object> session = loadSession(sessionNo);
        jdbc.update("UPDATE service_session SET agent_id=?, status='ASSIGNED', queue_position=0, assigned_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                targetAgentId, session.get("id"));
        appendMessage(((Number) session.get("id")).longValue(), "SYSTEM", p.userId(), "TEXT", "会话已转接至坐席 " + targetAgentId);
        return ok(sessionDetail(sessionNo, 0, true), r);
    }

    @PostMapping("/service/sessions/{sessionNo}/close")
    @PreAuthorize("hasAnyRole('USER','SYSTEM_ADMIN','CS_AGENT')")
    @Transactional
    ApiResponse<Map<String, Object>> close(@PathVariable String sessionNo,
                                           @AuthenticationPrincipal AuthPrincipal p,
                                           HttpServletRequest r) {
        Map<String, Object> session = loadSession(sessionNo);
        boolean staff = p.hasRole("SYSTEM_ADMIN") || p.hasRole("CS_AGENT");
        if (!staff && ((Number) session.get("user_id")).longValue() != p.userId()) {
            throw new BusinessException("SC40301", "无权关闭会话");
        }
        jdbc.update("UPDATE service_session SET status='CLOSED', closed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                session.get("id"));
        return ok(sessionDetail(sessionNo, p.userId(), staff), r);
    }

    @PostMapping("/service/tickets")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> createTicket(@Valid @RequestBody TicketBody body,
                                                  @AuthenticationPrincipal AuthPrincipal p,
                                                  HttpServletRequest r) {
        long id = DistributedId.next();
        String no = "TK" + id;
        Instant now = Instant.now();
        Instant due = TicketSla.dueAt(body.priority(), now);
        jdbc.update("""
                INSERT INTO service_ticket(id,ticket_no,user_id,merchant_id,category,priority,status,title,content,sla_due_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'OPEN',?,?,?,?,?)
                """, id, no, p.userId(), body.merchantId(), body.category(), body.priority() == null ? "NORMAL" : body.priority(),
                body.title(), body.content(), Timestamp.from(due), Timestamp.from(now), Timestamp.from(now));
        logTicket(id, "CREATE", p.userId(), "用户创建工单");
        return ok(ticketDetail(no, p.userId(), false), r);
    }

    @GetMapping("/service/tickets/{ticketNo}")
    @PreAuthorize("hasAnyRole('USER','SYSTEM_ADMIN','CS_AGENT','MERCHANT')")
    ApiResponse<Map<String, Object>> ticket(@PathVariable String ticketNo,
                                            @AuthenticationPrincipal AuthPrincipal p,
                                            HttpServletRequest r) {
        boolean staff = p.hasRole("SYSTEM_ADMIN") || p.hasRole("CS_AGENT");
        return ok(ticketDetail(ticketNo, p.userId(), staff), r);
    }

    @PostMapping("/admin/service/tickets/{ticketNo}/assign")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','CS_AGENT')")
    @Transactional
    ApiResponse<Map<String, Object>> assign(@PathVariable String ticketNo,
                                            @RequestParam long assigneeId,
                                            @AuthenticationPrincipal AuthPrincipal p,
                                            HttpServletRequest r) {
        Map<String, Object> t = loadTicket(ticketNo);
        jdbc.update("UPDATE service_ticket SET assignee_id=?, status='PROCESSING', updated_at=CURRENT_TIMESTAMP WHERE id=?",
                assigneeId, t.get("id"));
        logTicket(((Number) t.get("id")).longValue(), "ASSIGN", p.userId(), "分配给 " + assigneeId);
        return ok(ticketDetail(ticketNo, 0, true), r);
    }

    @PostMapping("/admin/service/tickets/{ticketNo}/resolve")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','CS_AGENT')")
    @Transactional
    ApiResponse<Map<String, Object>> resolve(@PathVariable String ticketNo,
                                             @RequestParam @NotBlank String remark,
                                             @AuthenticationPrincipal AuthPrincipal p,
                                             HttpServletRequest r) {
        Map<String, Object> t = loadTicket(ticketNo);
        jdbc.update("UPDATE service_ticket SET status='RESOLVED', resolved_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                t.get("id"));
        logTicket(((Number) t.get("id")).longValue(), "RESOLVE", p.userId(), remark);
        return ok(ticketDetail(ticketNo, 0, true), r);
    }

    @GetMapping("/admin/service/tickets")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','CS_AGENT','OPS')")
    ApiResponse<List<Map<String, Object>>> adminTickets(@RequestParam(required = false) String status,
                                                        HttpServletRequest r) {
        String sql = """
                SELECT ticket_no AS ticketNo,user_id AS userId,category,priority,status,title,sla_due_at AS slaDueAt,created_at AS createdAt
                FROM service_ticket
                """ + (status == null ? "" : " WHERE status=?") + " ORDER BY created_at DESC LIMIT 100";
        return ok(status == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, status), r);
    }

    @GetMapping("/admin/service/sla-breach-summary")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS','CS_AGENT')")
    ApiResponse<Map<String, Object>> slaBreachSummary(HttpServletRequest r) {
        Integer breached = jdbc.queryForObject("""
                SELECT COUNT(*) FROM service_ticket
                WHERE status NOT IN ('RESOLVED','CLOSED') AND sla_due_at < CURRENT_TIMESTAMP(3)
                """, Integer.class);
        Integer openCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM service_ticket
                WHERE status NOT IN ('RESOLVED','CLOSED')
                """, Integer.class);
        List<Map<String, Object>> samples = jdbc.queryForList("""
                SELECT ticket_no AS ticketNo, priority, status, title, sla_due_at AS slaDueAt
                FROM service_ticket
                WHERE status NOT IN ('RESOLVED','CLOSED') AND sla_due_at < CURRENT_TIMESTAMP(3)
                ORDER BY sla_due_at ASC
                LIMIT 10
                """);
        int breach = breached == null ? 0 : breached;
        return ok(Map.of(
                "slaBreachCount", breach,
                "openTicketCount", openCount == null ? 0 : openCount,
                "source", "service_ticket",
                "samples", samples
        ), r);
    }

    @PostMapping("/messages/send")
    @PreAuthorize("hasAnyRole('USER','SYSTEM_ADMIN')")
    @Transactional
    ApiResponse<Map<String, Object>> sendMessage(@Valid @RequestBody NotifyBody body,
                                                 @AuthenticationPrincipal AuthPrincipal p,
                                                 HttpServletRequest r) {
        long receiver = body.receiverId() == null ? p.userId() : body.receiverId();
        if (!p.hasRole("SYSTEM_ADMIN") && receiver != p.userId()) {
            throw new BusinessException("SC40302", "只能给自己发送测试消息");
        }
        return ok(enqueueNotify(body.scene(), body.channel(), receiver, body.payload()), r);
    }

    @PostMapping("/internal/messages/send")
    @Transactional
    ApiResponse<Map<String, Object>> internalSend(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                                  @Valid @RequestBody NotifyBody body,
                                                  HttpServletRequest r) {
        requireInternal(key);
        long receiver = body.receiverId() == null ? 0L : body.receiverId();
        if (receiver <= 0) throw new BusinessException("SC40001", "receiverId 必填");
        return ok(enqueueNotify(body.scene(), body.channel(), receiver, body.payload()), r);
    }

    @GetMapping("/user/messages")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<List<Map<String, Object>>> myMessages(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(jdbc.queryForList("""
                SELECT task_no AS taskNo, scene, channel, status, payload_json AS payload, created_at AS createdAt
                FROM notification_task WHERE receiver_id=? ORDER BY created_at DESC LIMIT 50
                """, p.userId()), r);
    }

    private Map<String, Object> enqueueNotify(String scene, String channel, long receiver, Map<String, Object> payload) {
        String ch = channel == null ? "IN_APP" : channel;
        long id = DistributedId.next();
        String no = "NT" + id;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO notification_task(id,task_no,scene,channel,receiver_id,payload_json,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'PENDING',?,?)
                """, id, no, scene, ch, receiver, json(payload == null ? Map.of() : payload),
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("UPDATE notification_task SET status='SENT', updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        jdbc.update("""
                INSERT INTO notification_record(id,task_id,provider_message_id,result,detail,sent_at)
                VALUES(?,?,?,?,?,?)
                """, DistributedId.next(), id, "SIM-" + id, "SUCCESS", "模拟渠道发送成功", Timestamp.from(Instant.now()));
        return Map.of("taskNo", no, "status", "SENT", "channel", ch);
    }

    private Long pickAgent(long skillId) {
        var rows = jdbc.queryForList("""
                SELECT a.id FROM service_agent a
                WHERE a.skill_group_id=? AND a.status='ONLINE'
                AND (SELECT COUNT(1) FROM service_session s WHERE s.agent_id=a.id AND s.status IN ('ASSIGNED','ACTIVE')) < a.max_sessions
                ORDER BY a.id LIMIT 1
                """, skillId);
        return rows.isEmpty() ? null : ((Number) rows.getFirst().get("id")).longValue();
    }

    private int nextQueue(long skillId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM service_session WHERE skill_group_id=? AND status='QUEUED'", Integer.class, skillId);
        return n == null ? 1 : n + 1;
    }

    private void appendMessage(long sessionId, String senderType, long senderId, String contentType, String content) {
        jdbc.update("""
                INSERT INTO service_message(id,session_id,sender_type,sender_id,content_type,content,sent_at)
                VALUES(?,?,?,?,?,?,?)
                """, DistributedId.next(), sessionId, senderType, senderId, contentType, content, Timestamp.from(Instant.now()));
    }

    private Map<String, Object> loadSession(String no) {
        var rows = jdbc.queryForList("SELECT * FROM service_session WHERE session_no=?", no);
        if (rows.isEmpty()) throw new BusinessException("SC40401", "会话不存在");
        return rows.getFirst();
    }

    private Map<String, Object> sessionDetail(String no, long userId, boolean staff) {
        Map<String, Object> s = loadSession(no);
        if (!staff && ((Number) s.get("user_id")).longValue() != userId) {
            throw new BusinessException("SC40301", "无权查看该会话");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("sessionNo", s.get("session_no"));
        d.put("status", s.get("status"));
        d.put("queuePosition", s.get("queue_position"));
        d.put("agentId", s.get("agent_id"));
        d.put("skillGroupId", s.get("skill_group_id"));
        d.put("createdAt", s.get("created_at"));
        return d;
    }

    private Map<String, Object> loadTicket(String no) {
        var rows = jdbc.queryForList("SELECT * FROM service_ticket WHERE ticket_no=?", no);
        if (rows.isEmpty()) throw new BusinessException("SC40402", "工单不存在");
        return rows.getFirst();
    }

    private Map<String, Object> ticketDetail(String no, long userId, boolean staff) {
        Map<String, Object> t = loadTicket(no);
        if (!staff) {
            long owner = ((Number) t.get("user_id")).longValue();
            Long merchantId = t.get("merchant_id") == null ? null : ((Number) t.get("merchant_id")).longValue();
            boolean merchantOk = false;
            try {
                long mid = TenantContext.requireMerchantId();
                merchantOk = merchantId != null && merchantId == mid;
            } catch (Exception ignored) {
            }
            if (owner != userId && !merchantOk) {
                throw new BusinessException("SC40303", "无权查看该工单");
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ticketNo", t.get("ticket_no"));
        d.put("status", t.get("status"));
        d.put("category", t.get("category"));
        d.put("priority", t.get("priority"));
        d.put("title", t.get("title"));
        d.put("content", t.get("content"));
        d.put("slaDueAt", t.get("sla_due_at"));
        d.put("assigneeId", t.get("assignee_id"));
        d.put("createdAt", t.get("created_at"));
        boolean breached = TicketSla.breached(
                t.get("sla_due_at") == null ? null : ((Timestamp) t.get("sla_due_at")).toInstant(), Instant.now());
        d.put("slaBreached", breached && !"RESOLVED".equals(String.valueOf(t.get("status"))) && !"CLOSED".equals(String.valueOf(t.get("status"))));
        d.put("logs", jdbc.queryForList(
                "SELECT action,operator_id AS operatorId,content,created_at AS createdAt FROM service_ticket_log WHERE ticket_id=? ORDER BY created_at",
                t.get("id")));
        return d;
    }

    private void logTicket(long ticketId, String action, long operatorId, String content) {
        jdbc.update("INSERT INTO service_ticket_log(id,ticket_id,action,operator_id,content,created_at) VALUES(?,?,?,?,?,?)",
                DistributedId.next(), ticketId, action, operatorId, content, Timestamp.from(Instant.now()));
    }

    private void requireInternal(String key) {
        if (key == null || !key.equals(internalKey)) {
            throw new BusinessException("SC40300", "内部调用未授权");
        }
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> ApiResponse<T> ok(T d, HttpServletRequest r) {
        return ApiResponse.success(d, String.valueOf(r.getAttribute("requestId")));
    }

    record OpenSession(Long skillGroupId, String source) {}
    record ChatBody(@NotBlank String content) {}
    record TicketBody(@NotBlank String category,
                      @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT") String priority,
                      @NotBlank String title,
                      @NotBlank String content,
                      Long merchantId) {}
    record NotifyBody(@NotBlank String scene, String channel, Long receiverId, Map<String, Object> payload) {}
}
