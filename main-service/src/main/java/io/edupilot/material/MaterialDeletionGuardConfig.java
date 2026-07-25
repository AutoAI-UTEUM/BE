package io.edupilot.material;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MaterialDeletionGuardConfig {

	@Bean
	@ConditionalOnMissingBean(MaterialDeletionGuard.class)
	MaterialDeletionGuard materialDeletionGuard() {
		// TODO Epic 4에서 ACTIVE 학습 세션 조회 구현체로 교체한다.
		return materialId -> {
		};
	}
}
