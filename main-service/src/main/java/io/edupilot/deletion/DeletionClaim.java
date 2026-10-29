package io.edupilot.deletion;

public record DeletionClaim(Long id, DeletionKind kind, String resourceKey, String token, long generation) {}
