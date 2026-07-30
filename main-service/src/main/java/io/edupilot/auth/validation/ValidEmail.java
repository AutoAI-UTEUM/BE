package io.edupilot.auth.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Documented
@Constraint(validatedBy = {})
@NotBlank(message = "이메일은 필수입니다.")
@Email(message = "이메일 형식을 확인해 주세요.")
@Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
@Target({
	ElementType.FIELD,
	ElementType.METHOD,
	ElementType.PARAMETER,
	ElementType.ANNOTATION_TYPE,
	ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEmail {

	String message() default "이메일을 확인해 주세요.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
