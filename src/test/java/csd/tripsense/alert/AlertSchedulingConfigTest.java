package csd.tripsense.alert;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import static org.assertj.core.api.Assertions.assertThat;

class AlertSchedulingConfigTest {
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
