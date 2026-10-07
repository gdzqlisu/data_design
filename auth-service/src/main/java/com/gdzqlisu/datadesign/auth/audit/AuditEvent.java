package com.gdzqlisu.datadesign.auth.audit;

public enum AuditEvent {
    LOGIN_SUCCESS,
    LOGIN_PENDING,
    LOGIN_REJECTED,
    LOGIN_DISABLED,
    LOGIN_FAILED,
    APPROVED,
    REJECTED,
    ROLE_CHANGED,
    USER_DISABLED,
    LOGOUT,
    TOKEN_REVOKED,
    BREAK_GLASS_LOGIN
}
