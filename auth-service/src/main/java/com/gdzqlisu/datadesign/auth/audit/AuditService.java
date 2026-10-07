package com.gdzqlisu.datadesign.auth.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int USER_AGENT_MAX = 512;

    private final AuditLogRepository logs;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate auditTx;

    public AuditService(AuditLogRepository logs, ObjectMapper mapper, Clock clock,
                        PlatformTransactionManager transactionManager) {
        this.logs = logs;
        this.mapper = mapper;
        this.clock = clock;
        this.auditTx = new TransactionTemplate(transactionManager);
        this.auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 审计写入失败不影响主流程：登录不能因为记不上审计而失败。
     * 这里必须用编程式事务。写成 @Transactional(REQUIRES_NEW) 有两个坑：
     * 一是 recordFromRequest 内部调用 record 属于自调用，注解被代理绕过，
     * 事务会落回调用方（那条"失败不影响主流程"就名存实亡）；
     * 二是声明式事务的提交点在方法之外，提交阶段的异常 catch 不到。
     */
    public void record(Long userId, AuditEvent event, String provider,
                       String ip, String userAgent, Map<String, Object> detail) {
        String detailJson;
        try {
            detailJson = detail == null ? null : mapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            log.warn("审计明细序列化失败 event={} userId={}", event, userId, e);
            return;
        }
        try {
            auditTx.executeWithoutResult(status -> logs.save(
                    AuditLog.of(userId, event, provider, ip, userAgent, detailJson, clock.instant())));
        } catch (RuntimeException e) {
            log.warn("审计写入失败 event={} userId={}", event, userId, e);
        }
    }

    public void recordFromRequest(Long userId, AuditEvent event, String provider,
                                  HttpServletRequest request, Map<String, Object> detail) {
        record(userId, event, provider, clientIp(request), userAgent(request), detail);
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null) {
            return null;
        }
        return agent.length() <= USER_AGENT_MAX ? agent : agent.substring(0, USER_AGENT_MAX);
    }
}
