package csd.tripsense.alert;

import csd.tripsense.impact.EvaluationProgress;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertProgressReporterTest {
    @Test void metadataFailuresNeverEscapeIntoTheAssessmentCaller() {
        var progress=mock(EvaluationProgress.class);
        var reporter=new AlertProgressReporter(progress);
        when(progress.begin(1L,2L,true,List.of(3L))).thenThrow(new IllegalStateException("database unavailable"));
        doThrow(new IllegalStateException("database unavailable")).when(progress).resolved(1L,2L,3L);
        doThrow(new IllegalStateException("database unavailable")).when(progress).finish("batch",true);
        doThrow(new IllegalStateException("database unavailable")).when(progress).reconcile();
        assertThat(reporter.begin(1L,2L,true,List.of(3L))).isNull();
        assertThatCode(() -> reporter.resolved(1L,2L,3L)).doesNotThrowAnyException();
        assertThatCode(() -> reporter.finish("batch",true)).doesNotThrowAnyException();
        assertThatCode(reporter::reconcile).doesNotThrowAnyException();
    }
    @Test void missingBatchIsNotFinishedAfterFailedRegistration() {
        var progress=mock(EvaluationProgress.class);
        new AlertProgressReporter(progress).finish(null,true);
        verifyNoInteractions(progress);
    }
}
