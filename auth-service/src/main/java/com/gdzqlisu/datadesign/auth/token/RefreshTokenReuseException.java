package com.gdzqlisu.datadesign.auth.token;

/**
 * 已轮换过的令牌被再次使用，按令牌被盗处理：调用方应撤销该用户整条刷新链并记审计。
 */
public class RefreshTokenReuseException extends RuntimeException {

    private final long userId;

    public RefreshTokenReuseException(long userId) {
        super("刷新令牌被重复使用，已撤销该用户的全部刷新令牌");
        this.userId = userId;
    }

    public long getUserId() {
        return userId;
    }
}
