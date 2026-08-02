package io.edupilot.exam;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ExamGradingConfig {

	@Bean(name = "examGradingExecutor")
	ThreadPoolTaskExecutor examGradingExecutor(ExamGradingProperties properties) {
		ExamGradingProperties.Executor configured = properties.executor();
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(configured.coreSize());
		executor.setMaxPoolSize(configured.maxSize());
		executor.setQueueCapacity(configured.queueCapacity());
		executor.setThreadNamePrefix("exam-grading-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
