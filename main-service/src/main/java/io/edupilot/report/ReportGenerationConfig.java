package io.edupilot.report;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ReportGenerationConfig {

	@Bean(name = "reportGenerationExecutor")
	ThreadPoolTaskExecutor reportGenerationExecutor(
		ReportGenerationProperties properties
	) {
		ReportGenerationProperties.Executor configured = properties.executor();
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(configured.coreSize());
		executor.setMaxPoolSize(configured.maxSize());
		executor.setQueueCapacity(configured.queueCapacity());
		executor.setThreadNamePrefix("report-generation-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
