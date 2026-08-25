package io.edupilot.material;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MaterialAsyncConfig {

	@Bean(name = "materialExtractionExecutor")
	Executor materialExtractionExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("material-extract-");
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(100);
		executor.initialize();
		return executor;
	}

	@Bean(name = "materialXaiFileBackfillExecutor")
	Executor materialXaiFileBackfillExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("material-xai-backfill-");
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(10);
		executor.initialize();
		return executor;
	}
}
