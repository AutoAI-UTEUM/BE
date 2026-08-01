package io.edupilot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.classroom.ClassroomService;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(types = ClassroomService.class)
public @interface Epic10ServiceMocks {
}
