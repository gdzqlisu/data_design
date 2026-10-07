package com.gdzqlisu.datadesign.auth.user;

public record LoginOutcome(User user, boolean newlyCreated) {
}
