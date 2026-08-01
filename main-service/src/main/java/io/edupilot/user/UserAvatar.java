package io.edupilot.user;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public record UserAvatar(Resource resource, MediaType mediaType, String extension) {
}
