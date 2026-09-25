package io.edupilot.usernote;

public record CreateResult<T>(T data, boolean created) {
}
