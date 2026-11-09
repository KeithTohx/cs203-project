package csd.tripsense.alert;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import static org.assertj.core.api.Assertions.assertThat;

class AlertSchedulingConfigTest {
    @Test void alertSchedulersPreserveTheConfiguredApplicationExecutor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AlertSchedulingConfig.class)
                .withPropertyValues("spring.task.execution.pool.core-size=2",
                        "spring.task.execution.pool.max-size=4", "spring.task.execution.thread-name-prefix=app-test-")
                .run(context -> {
                    assertThat(context).hasBean("applicationTaskExecutor");
                    var executor=context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(2);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(4);
                    assertThat(executor.getThreadNamePrefix()).isEqualTo("app-test-");
                    assertThat(executor).isNotSameAs(context.getBean("taskScheduler"));
                    assertThat(executor).isNotSameAs(context.getBean("alertTaskScheduler"));
                });
    }

    @Test void applicationExecutorStillSupportsVirtualThreads() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AlertSchedulingConfig.class)
                .withPropertyValues("spring.threads.virtual.enabled=true", "spring.task.execution.thread-name-prefix=virtual-app-")
                .run(context -> {
                    assertThat(context).hasBean("applicationTaskExecutor");
                    var executor=context.getBean("applicationTaskExecutor", SimpleAsyncTaskExecutor.class);
                    assertThat(executor.getThreadNamePrefix()).isEqualTo("virtual-app-");
                    var virtual=new AtomicBoolean(); var completed=new CountDownLatch(1);
                    executor.execute(() -> { virtual.set(Thread.currentThread().isVirtual()); completed.countDown(); });
                    assertThat(completed.await(2,TimeUnit.SECONDS)).isTrue();
                    assertThat(virtual.get()).isTrue();
                });
    }

    @Test void explicitApplicationExecutorIsNotReplacedByAlertConfiguration() {
        var custom=new ThreadPoolTaskExecutor();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AlertSchedulingConfig.class)
                .withBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class, () -> custom)
                .run(context -> assertThat(context.getBean("applicationTaskExecutor")).isSameAs(custom));
    }

    @Test void blockedIngestionDoesNotOccupyAlertMaintenanceThreads() throws Exception {
        var config=new AlertSchedulingConfig();
        var ingestion=config.ingestionScheduler(); var alerts=config.alertScheduler();
        ingestion.initialize(); alerts.initialize();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var maintained=new CountDownLatch(1);
        try {
            ingestion.execute(() -> {
                entered.countDown();
                try { release.await(5,TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            alerts.execute(maintained::countDown);
            assertThat(maintained.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(AlertEmailDispatcher.class.getDeclaredMethod("retryPending").getAnnotation(Scheduled.class).scheduler())
                    .isEqualTo("alertTaskScheduler");
            assertThat(AlertEvaluationListener.class.getDeclaredMethod("reconcile").getAnnotation(Scheduled.class).scheduler())
                    .isEqualTo("alertTaskScheduler");
        } finally { release.countDown(); ingestion.shutdown(); alerts.shutdown(); }
    }
}
