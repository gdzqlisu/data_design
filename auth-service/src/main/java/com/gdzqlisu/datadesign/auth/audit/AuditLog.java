package com.gdzqlisu.datadesign.auth.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditEvent event;

    private String provider;

    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "detail_json")
    private String detailJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    static AuditLog of(Long userId, AuditEvent event, String provider, String ip,
                       String userAgent, String detailJson, Instant createdAt) {
        AuditLog log = new AuditLog();
        log.userId = userId;
        log.event = event;
        log.provider = provider;
        log.ip = ip;
        log.userAgent = userAgent;
        log.detailJson = detailJson;
        log.createdAt = createdAt;
        return log;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public AuditEvent getEvent() {
        return event;
    }

    public String getProvider() {
        return provider;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
