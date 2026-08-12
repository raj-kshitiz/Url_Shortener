package com.example.urlshortener.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Bean("clickEventExecutor")
    public Executor clickEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // corePoolSize is the number that actually does the work — see below.
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);

        // Bounded, deliberately. An unbounded queue turns a slow Mongo into an
        // OutOfMemoryError: the app looks healthy right up until the heap dies.
        executor.setQueueCapacity(10_000);

        // Queue full means Mongo is badly degraded. Drop the analytics event
        // rather than slow down every redirect. See "the rejection policy is a
        // real decision" below.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());

        // Drain the queue on a normal shutdown instead of discarding it.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);

        executor.setThreadNamePrefix("click-");
        executor.initialize();
        return executor;
    }

    // An @Async void method that throws is otherwise swallowed: no stack trace,
    // no counter, no evidence. This is the only place you'd ever learn that
    // every click write has been failing for a week.
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Async {} failed, args={}", method.getName(), params, ex);
    }
}