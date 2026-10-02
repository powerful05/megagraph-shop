package com.megagraph.shop.ingestion;
import com.megagraph.shop.media.MediaStorage;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class IngestionWorker {
 private final IngestionService service;
 private final DraftGenerator generator;
 private final MediaStorage storage;
 private final boolean enabled;
 private final long timeoutMs;
 private final ExecutorService calls=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),Thread.ofPlatform().daemon().name("ingestion-provider-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
 public IngestionWorker(IngestionService service,DraftGenerator generator,MediaStorage storage,
  @Value("${app.ingestion.worker-enabled:true}") boolean enabled,
  @Value("${app.ingestion.provider-timeout-ms:5000}") long timeoutMs,
  @Value("${app.ingestion.provider:mock}") String provider) {
  if(!provider.equals("mock")) throw new IllegalStateException("Merchant ingestion only supports validated Mock provider; real visual providers are unavailable");
  if(timeoutMs<1) throw new IllegalStateException("Provider timeout must be positive");
  this.service=service;this.generator=generator;this.storage=storage;this.enabled=enabled;this.timeoutMs=timeoutMs;
 }
 @Scheduled(fixedDelayString="${app.ingestion.scan-ms:500}",initialDelayString="${app.ingestion.initial-delay-ms:1000}")
 public void scheduled() { if(enabled) runOnce(); }
 public boolean runOnce() {
  var claim=service.claim(); if(claim==null)return false;
  long start=System.nanoTime();
  Future<DraftSuggestion> future=null;
  DraftSuggestion suggestion=null;
  try {
   byte[] bytes;
   try(var stream=storage.load(claim.storageKey()).getInputStream()){bytes=stream.readAllBytes();}
   String actualHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
   if(!actualHash.equals(claim.inputHash()))throw new IngestionProviderException("INPUT_MEDIA_CHANGED",false);
   var input=new DraftInput(claim.filename(),claim.inputHash(),bytes,claim.contentType(),claim.scenario(),claim.attempt());
   future=calls.submit(()->generator.generate(input));
   suggestion=future.get(timeoutMs,TimeUnit.MILLISECONDS);
   service.complete(claim,suggestion,elapsed(start));
  } catch(TimeoutException e) {
   if(future!=null)future.cancel(true); service.fail(claim,"PROVIDER_TIMEOUT",true,elapsed(start));
  } catch(InterruptedException e) {
   if(future!=null)future.cancel(true); Thread.currentThread().interrupt(); service.fail(claim,"WORKER_ERROR",true,elapsed(start));
  } catch(Exception e) {
   Throwable cause=e instanceof ExecutionException?e.getCause():e;
   if(cause instanceof IngestionProviderException p) service.fail(claim,p.code(),p.retryable(),elapsed(start),suggestion);
   else service.fail(claim,"WORKER_ERROR",true,elapsed(start));
  }
  return true;
 }
 private long elapsed(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
 @PreDestroy void close(){calls.shutdownNow();}
}
