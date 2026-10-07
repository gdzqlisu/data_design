package com.gdzqlisu.datadesign.auth.user;

/**
 * 本次只启用 ADMIN 与 MEMBER。STRATEGIST 与 VIEWER 是预留值，
 * 将来加角色只加枚举值，不改表结构。
 */
public enum Role {
    ADMIN,
    MEMBER,
    STRATEGIST,
    VIEWER
}
