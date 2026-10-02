package com.megagraph.shop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.megagraph.shop.api.*;
import com.megagraph.shop.identity.*;
import com.megagraph.shop.ingestion.*;
import com.megagraph.shop.media.*;
import com.megagraph.shop.payment.OutboxFaultInjector;
import com.megagraph.shop.security.MerchantActor;
import com.megagraph.shop.shared.ConflictException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.ingestion.provider-timeout-ms=100","app.ingestion.worker-enabled=false",
 "spring.datasource.url=jdbc:h2:mem:ingestion-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
 "app.media.upload-dir=${java.io.tmpdir}/megagraph-ingestion-tests-${random.uuid}"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IngestionIntegrationTest.FaultConfig.class)
class IngestionIntegrationTest {
 @Autowired IngestionService service;
 @Autowired IngestionWorker worker;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MockDraftGenerator generator;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
 @Autowired JdbcTemplate db;
 @Autowired UserAccountRepository users;
 @Autowired MerchantUserRepository memberships;
 @Autowired MerchantRepository merchants;
 @Autowired MediaStorage storage;
 @Autowired MockMvc mvc;
 @Autowired FaultConfig faults;
 MerchantActor actor;
 @TestConfiguration static class FaultConfig {
  final AtomicReference<String> fail=new AtomicReference<>();
  @Bean OutboxFaultInjector ingestionOutboxFaults(){ return event -> { if(event.equals(fail.get()) && fail.compareAndSet(event,null)) throw new IllegalStateException("Injected outbox failure"); }; }
 }
 @BeforeEach void prepare() {
  faults.fail.set(null);
  org.mockito.Mockito.reset(generator);
  db.update("UPDATE ingestion_job SET status='CANCELLED',lease_until=NULL WHERE status IN ('QUEUED','RUNNING','RETRY_WAIT')");
  UserAccount u=users.findByUsernameIgnoreCase("merchant@megagraph.local").orElseThrow();
  var member=memberships.findByUserId(u.getId()).getFirst();
  actor=new MerchantActor(u.getId(),member.getMerchantId(),MerchantRole.OWNER);
 }
 MockMultipartFile image(String filename){return new MockMultipartFile("image",filename,"image/png",TestImages.png());}
 IngestionResponse create(String scenario){return service.create(actor,UUID.randomUUID().toString(),image("测试.png"),scenario);}
 IngestionResponse state(long id){return service.get(actor,id);}
 void due(long id){db.update("UPDATE ingestion_job SET next_attempt_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id);}
 void expire(long id){db.update("UPDATE ingestion_job SET lease_until=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id);}
 @Test void queueReturnsBeforeDraftAndWorkerCreatesOnlyOneUnpublishedDraft() {
  var q=create("success");
  assertThat(q.status()).isEqualTo("QUEUED"); assertThat(q.productId()).isNull(); assertThat(q.attempt()).isZero();
  org.mockito.Mockito.doAnswer(call -> {
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).as("Provider must run outside DB transaction").isFalse();
      return call.callRealMethod();
  }).when(generator).generate(org.mockito.ArgumentMatchers.any(DraftInput.class));
  assertThat(worker.runOnce()).isTrue(); var done=state(q.id());
  assertThat(done.status()).isEqualTo("SUCCEEDED"); assertThat(done.artifact().inputHash()).hasSize(64);
  assertThat(done.artifact().unknowns()).contains("材质待商家确认");assertThat(done.artifact().usage()).isNull();
  assertThat(db.queryForMap("SELECT draft_status,publish_status FROM product WHERE id=?",done.productId())).containsEntry("draft_status","READY_FOR_CONFIRMATION").containsEntry("publish_status","UNPUBLISHED");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM product_sku WHERE product_id=?",Integer.class,done.productId())).isZero();
  assertThat(worker.runOnce()).isFalse();
  assertThatThrownBy(()->service.retry(actor,q.id(),done.version())).isInstanceOf(ConflictException.class);
 }
 @Test void concurrentSameKeyHasOneJobAndOneStoredInputWhileDifferentPayloadConflicts() throws Exception {
  String key=UUID.randomUUID().toString(); var pool=Executors.newFixedThreadPool(6);var latch=new CountDownLatch(1);
  try {
   List<Future<IngestionResponse>> calls=new ArrayList<>();
   for(int i=0;i<6;i++)calls.add(pool.submit(()->{latch.await();return service.create(actor,key,image("same.png"));}));
   latch.countDown(); Set<Long> ids=new HashSet<>();for(var f:calls)ids.add(f.get(10,TimeUnit.SECONDS).id());
   assertThat(ids).hasSize(1);assertThat(db.queryForObject("SELECT COUNT(*) FROM ingestion_job WHERE merchant_id=? AND idempotency_key=?",Integer.class,actor.merchantId(),key)).isEqualTo(1);
   assertThatThrownBy(()->service.create(actor,key,image("different.png"))).isInstanceOf(ConflictException.class).hasMessageContaining("不同图片");
   assertThatThrownBy(()->service.create(actor,key,image("same.png"),"fail")).isInstanceOf(ConflictException.class);
  } finally {pool.shutdownNow();}
 }
 @Test void concurrentClaimRunsEachJobAtMostOnceAndRequiresDatabaseState() throws Exception {
  var q=create("success");var pool=Executors.newFixedThreadPool(2);
  try {
   var a=pool.submit(()->service.claim());var b=pool.submit(()->service.claim());
   var claims=Arrays.asList(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
   assertThat(claims.stream().filter(Objects::nonNull).count()).isEqualTo(1);
   var c=claims.stream().filter(Objects::nonNull).findFirst().orElseThrow();assertThat(c.id()).isEqualTo(q.id());
   assertThat(service.complete(c,generator.generate(c.filename()),10)).isTrue();
   assertThat(service.complete(c,generator.generate(c.filename()),10)).isFalse();
   assertThat(service.artifacts(actor,q.id())).hasSize(1);
  } finally {pool.shutdownNow();}
 }
 @Test void restartedWorkerReclaimsExpiredLeaseAndFencesLateAttempt() {
  var q=create("success"); var old=service.claim();expire(q.id());
  assertThat(service.complete(old,generator.generate(old.filename()),10)).isFalse();
  var renewed=service.claim();assertThat(renewed.attempt()).isEqualTo(2);
  assertThat(service.complete(old,generator.generate(old.filename()),10)).isFalse();
  assertThat(service.complete(renewed,generator.generate(renewed.filename()),10)).isTrue();
  assertThat(service.artifacts(actor,q.id())).extracting(IngestionArtifactResponse::attempt).containsExactly(1,2);
  assertThat(service.artifacts(actor,q.id()).getFirst().errorCode()).isEqualTo("LEASE_EXPIRED");
 }
 @Test void cancelFencesRunningResultAndUsesOptimisticVersion() {
  var q=create("success");var c=service.claim(); var running=state(q.id());
  assertThatThrownBy(()->service.cancel(actor,q.id(),q.version())).isInstanceOf(ConflictException.class);
  var cancelled=service.cancel(actor,q.id(),running.version());assertThat(cancelled.status()).isEqualTo("CANCELLED");
  assertThat(service.complete(c,generator.generate(c.filename()),10)).isFalse();
  assertThat(service.fail(c,"WORKER_ERROR",true,10)).isFalse();assertThat(cancelled.productId()).isNull();
 }
 @Test void failureBackoffExhaustsThreeAttemptsAndExplicitRetryPreservesArtifacts() {
  var q=create("fail");
  for(int i=1;i<=3;i++) {
   assertThat(worker.runOnce()).isTrue();var r=state(q.id());assertThat(r.attempt()).isEqualTo(i);
   if(i<3){assertThat(r.status()).isEqualTo("RETRY_WAIT");assertThat(r.nextAttemptAt()).isAfter(Instant.now());assertThat(worker.runOnce()).isFalse();due(q.id());}
  }
  var dead=state(q.id());assertThat(dead.status()).isEqualTo("DEAD_LETTER");assertThat(service.artifacts(actor,q.id())).hasSize(3);
  var retry=service.retry(actor,q.id(),dead.version());assertThat(retry.status()).isEqualTo("QUEUED");assertThat(retry.attempt()).isEqualTo(3);
  assertThat(worker.runOnce()).isTrue();assertThat(state(q.id()).attempt()).isEqualTo(4);assertThat(service.artifacts(actor,q.id())).hasSize(4);
 }
 @Test void repeatedLeaseExpiryExhaustsBudgetAndWritesOutboxAtomically() {
  var q=create("success");for(int i=0;i<3;i++){assertThat(service.claim()).isNotNull();expire(q.id());}
  var before=state(q.id());faults.fail.set("INGESTION_DEAD_LETTER");assertThatThrownBy(()->service.claim()).hasMessage("Injected outbox failure");
  assertThat(state(q.id()).status()).isEqualTo("RUNNING");assertThat(service.artifacts(actor,q.id())).hasSize(2);
  assertThat(service.claim()).isNull();assertThat(state(q.id()).status()).isEqualTo("DEAD_LETTER");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='INGESTION' AND aggregate_id=? AND event_type='INGESTION_DEAD_LETTER'",Integer.class,q.id())).isEqualTo(1);
 }
 @Test void claimAndCancelOutboxRollbackPreserveStateAndAttemptHistory() {
  var q=create("success");faults.fail.set("INGESTION_RUNNING");
  assertThatThrownBy(()->service.claim()).hasMessage("Injected outbox failure");
  assertThat(state(q.id()).status()).isEqualTo("QUEUED");assertThat(state(q.id()).attempt()).isZero();
  var claim=service.claim();var running=state(q.id());faults.fail.set("INGESTION_CANCELLED");
  assertThatThrownBy(()->service.cancel(actor,q.id(),running.version())).hasMessage("Injected outbox failure");
  assertThat(state(q.id()).status()).isEqualTo("RUNNING");assertThat(service.artifacts(actor,q.id())).isEmpty();
  var cancelled=service.cancel(actor,q.id(),running.version());assertThat(cancelled.status()).isEqualTo("CANCELLED");
  assertThat(service.artifacts(actor,q.id())).extracting(IngestionArtifactResponse::errorCode).containsExactly("CANCELLED");
 }
 @Test void retryableProviderRecoversAndLegacyMissingInputRetryIsRejected() {
  var q=create("retryable");assertThat(worker.runOnce()).isTrue();assertThat(state(q.id()).status()).isEqualTo("RETRY_WAIT");
  due(q.id());assertThat(worker.runOnce()).isTrue();assertThat(state(q.id()).status()).isEqualTo("SUCCEEDED");
  assertThat(service.artifacts(actor,q.id())).hasSize(2);
  String key=UUID.randomUUID().toString();
  db.update("INSERT INTO ingestion_job(merchant_id,idempotency_key,status,original_filename,created_by) VALUES(?,?,'FAILED','legacy.png',?)",actor.merchantId(),key,actor.userId());
  long id=db.queryForObject("SELECT id FROM ingestion_job WHERE merchant_id=? AND idempotency_key=?",Long.class,actor.merchantId(),key);
  assertThatThrownBy(()->service.retry(actor,id,0)).isInstanceOf(ConflictException.class).hasMessageContaining("重新上传");
 }
 @Test void malformedIsPermanentAndTimeoutIsRetryable() {
  var bad=create("malformed");worker.runOnce();assertThat(state(bad.id()).status()).isEqualTo("DEAD_LETTER");assertThat(state(bad.id()).errorCode()).isEqualTo("OUTPUT_INVALID");assertThat(state(bad.id()).artifact().rawOutput()).contains("{invalid");
  var timeout=create("timeout");worker.runOnce();assertThat(state(timeout.id()).status()).isEqualTo("RETRY_WAIT");assertThat(state(timeout.id()).errorCode()).isEqualTo("PROVIDER_TIMEOUT");
 }
 @Test void completingOutboxRollbackLeavesNoProductArtifactOrSuccessfulJob() {
  var q=create("success");var c=service.claim();long products=db.queryForObject("SELECT COUNT(*) FROM product",Long.class);
  faults.fail.set("INGESTION_SUCCEEDED");assertThatThrownBy(()->service.complete(c,generator.generate(c.filename()),10)).hasMessage("Injected outbox failure");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM product",Long.class)).isEqualTo(products);assertThat(service.artifacts(actor,q.id())).isEmpty();assertThat(state(q.id()).status()).isEqualTo("RUNNING");
  assertThat(service.complete(c,generator.generate(c.filename()),10)).isTrue();
 }
 @Test void enqueueOutboxRollbackCompensatesOnlyNewUnreferencedFile() throws Exception {
  var reference=create("success");String key=db.queryForObject("SELECT storage_key FROM ingestion_job WHERE id=?",String.class,reference.id());
  Set<String> before=new HashSet<>(storage.keysOlderThan(Instant.now().plusSeconds(1)));
  faults.fail.set("INGESTION_QUEUED");assertThatThrownBy(()->create("success")).hasMessage("Injected outbox failure");
  assertThat(new HashSet<>(storage.keysOlderThan(Instant.now().plusSeconds(1)))).isEqualTo(before);assertThat(storage.load(key).exists()).isTrue();
 }
 @Test void orphanCleanupKeepsEveryReferencedJobAndImageAndHonorsGrace() throws Exception {
  var q=create("success");String key=db.queryForObject("SELECT storage_key FROM ingestion_job WHERE id=?",String.class,q.id());
  var orphan=storage.store(actor.merchantId(),"orphan.png","image/png",new java.io.ByteArrayInputStream(TestImages.png()));
  var old=java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(172800));
  java.nio.file.Files.setLastModifiedTime(storage.load(key).getFile().toPath(),old);java.nio.file.Files.setLastModifiedTime(storage.load(orphan.storageKey()).getFile().toPath(),old);
  assertThatThrownBy(()->service.cleanupOrphans(Instant.now())).isInstanceOf(IllegalArgumentException.class);
  service.cleanupOrphans(Instant.now().minusSeconds(86400));assertThat(storage.load(key).exists()).isTrue();assertThatThrownBy(()->storage.load(orphan.storageKey())).hasMessageContaining("不存在");
 }
 @Test void merchantPermissionsIsolateInputArtifactsAndMutations() throws Exception {
  var q=create("success");
  mvc.perform(get("/api/merchant/v1/ingestions/{id}/media",q.id()).with(httpBasic("merchant@megagraph.local","merchant123"))).andExpect(status().isOk());
  var other=merchants.save(new Merchant("隔离测试"));
  var stranger=new MerchantActor(actor.userId(),other.getId(),MerchantRole.OWNER);
  assertThatThrownBy(()->service.get(stranger,q.id())).hasMessageContaining("不存在");assertThatThrownBy(()->service.artifacts(stranger,q.id())).hasMessageContaining("不存在");assertThatThrownBy(()->service.inputMedia(stranger,q.id())).hasMessageContaining("不存在");
  String user="read-"+UUID.randomUUID();
  var account=users.save(new UserAccount(user,passwordEncoder.encode("test-pass"),"只读测试"));
  memberships.save(new MerchantUser(actor.merchantId(),account.getId(),MerchantRole.VIEWER));
  mvc.perform(post("/api/merchant/v1/ingestions/{id}/cancel",q.id()).with(httpBasic(user,"test-pass")).contentType("application/json").content("{\"version\":"+q.version()+"}")).andExpect(status().isForbidden());
  mvc.perform(get("/api/merchant/v1/ingestions/{id}",q.id()).with(httpBasic(user,"test-pass"))).andExpect(status().isOk());
  mvc.perform(multipart("/api/merchant/v1/ingestions").file(new MockMultipartFile("image","fake.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10})).header("Idempotency-Key",UUID.randomUUID()).with(httpBasic("merchant@megagraph.local","merchant123"))).andExpect(status().isUnprocessableEntity());
 }
}
