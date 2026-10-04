package io.edupilot.user;

/** Current database authorization state, without loading a possibly stale managed User. */
public record UserAccessState(UserRole role, UserStatus status) {
}
