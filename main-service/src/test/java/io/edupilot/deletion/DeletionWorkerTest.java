package io.edupilot.deletion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.ai.AiClient;
import io.edupilot.material.storage.FileStorage;

class DeletionWorkerTest {
 DeletionProperties policy(boolean enabled){return new DeletionProperties(enabled,enabled?"SYNTHETIC":null,null,null,0,null,Duration.ofMinutes(2),Duration.ofSeconds(1),2,25);}
 @Test void disabledWorkerNeverReadsTasksOrCallsProviders() {
  var store=mock(DeletionTaskStore.class);var files=mock(FileStorage.class);var ai=mock(AiClient.class);
  new DeletionWorker(store,files,ai,policy(false),Runnable::run).tick();verifyNoInteractions(store,files,ai);
 }
 @Test void saturatedExecutorLeavesDurableTaskUnclaimedForNextPoll() {
  var store=mock(DeletionTaskStore.class);var files=mock(FileStorage.class);var ai=mock(AiClient.class);
  when(store.candidates()).thenReturn(List.of(1L));
  new DeletionWorker(store,files,ai,policy(true),task->{throw new RejectedExecutionException();}).tick();
  verify(store,never()).claim(any());verifyNoInteractions(files,ai);
 }
 @Test void failedProviderLogsOnlyTaskNumberWithoutFileKeyBodyOrException() {
  var store=mock(DeletionTaskStore.class);var files=mock(FileStorage.class);var ai=mock(AiClient.class);
  var claim=new DeletionClaim(1L,DeletionKind.EXTERNAL_AI,"sensitive-synthetic-file-key","synthetic-lease",0);
  when(store.claim(1L)).thenReturn(claim);when(store.mayExecute(claim)).thenReturn(true);
  doThrow(new IllegalStateException("sensitive-synthetic-provider-body")).when(ai).deleteFile(claim.resourceKey());
  Logger logger=(Logger)LoggerFactory.getLogger(DeletionWorker.class);var capture=new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
  capture.start();logger.addAppender(capture);
  try{new DeletionWorker(store,files,ai,policy(true),Runnable::run).process(1L);}
  finally{logger.detachAppender(capture);capture.stop();}
  assertThat(capture.list).singleElement().satisfies(event->{
   assertThat(event.getThrowableProxy()).isNull();assertThat(event.getFormattedMessage()).doesNotContain("sensitive");
   assertThat(event.getKeyValuePairs()).singleElement().satisfies(pair->{
    assertThat(pair.key).isEqualTo("deletionTaskId");assertThat(String.valueOf(pair.value)).isEqualTo("1");
   });
   assertThat(event.getKeyValuePairs().toString()).doesNotContain("sensitive");
  });verify(store).fail(claim);verify(store,never()).complete(any());
 }
}
