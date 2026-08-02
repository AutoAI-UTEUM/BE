package io.edupilot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomWeekService;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.session.LearningProgressService;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(types = {
	ClassroomService.class,
	ClassroomWeekService.class,
	MaterialAccessService.class,
	LearningProgressService.class,
	ClassroomWeekMaterialRepository.class
})
public @interface Epic10ServiceMocks {
}
