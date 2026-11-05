package io.edupilot.composer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.user.UserRole;
import io.swagger.v3.oas.annotations.Hidden;

class ComposerPredictionControllerTest {
	final ComposerPredictionService service = mock(ComposerPredictionService.class);
	MockMvc mvc;
	@BeforeEach void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new ComposerPredictionController(service))
			.setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
				public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == AuthenticatedUser.class; }
				public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, WebDataBinderFactory b) {
					return new AuthenticatedUser(1L, UserRole.LEARNER);
				}
			}).build();
	}
	@Test void defaultPropertiesFailClosedAndControllerIsAbsent() {
		new ApplicationContextRunner().withUserConfiguration(Config.class)
			.withBean(ComposerPredictionService.class, () -> service).run(context -> {
				assertThat(context).doesNotHaveBean(ComposerPredictionController.class);
				assertThat(context.getBean(ComposerProperties.class).ready()).isFalse();
				assertThat(context.getBean(ComposerProperties.class).dailyCalls()).isZero();
			});
		assertThat(ComposerPredictionController.class.isAnnotationPresent(Hidden.class)).isTrue();
	}
	@Test void enablingOnlyTheRouteCannotActivateUnconfirmedPolicy() {
		new ApplicationContextRunner().withUserConfiguration(Config.class)
			.withBean(ComposerPredictionService.class, () -> service).withPropertyValues("edupilot.composer.enabled=true")
			.run(context -> {
				assertThat(context).hasSingleBean(ComposerPredictionController.class);
				assertThat(context.getBean(ComposerProperties.class).ready()).isFalse();
			});
	}
	@ParameterizedTest @ValueSource(strings = {"{}", "{\"sourceMessageId\":null}", "{\"sourceMessageId\":0}", "{\"sourceMessageId\":-1}"})
	void missingOrInvalidSavedSourceIsRejected(String json) throws Exception {
		mvc.perform(post("/api/sessions/10/composer-prediction").contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isBadRequest()); verifyNoInteractions(service);
	}
	@Test void usesAuthenticatedUserAndTheSavedSourceOnly() throws Exception {
		when(service.open(1L, 10L, 50L)).thenReturn(new SseEmitter(0L));
		mvc.perform(post("/api/sessions/10/composer-prediction").contentType(MediaType.APPLICATION_JSON)
			.content("{\"sourceMessageId\":50}")).andExpect(status().isOk());
		verify(service).open(1L, 10L, 50L);
	}
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ComposerProperties.class)
	@Import(ComposerPredictionController.class)
	static class Config { }
}
