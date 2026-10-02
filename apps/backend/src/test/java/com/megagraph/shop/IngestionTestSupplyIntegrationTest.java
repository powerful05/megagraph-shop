package com.megagraph.shop;
import static org.assertj.core.api.Assertions.assertThat;
import com.megagraph.shop.catalog.*;
import com.megagraph.shop.identity.*;
import com.megagraph.shop.ingestion.*;
import com.megagraph.shop.security.MerchantActor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
@SpringBootTest(properties={"app.ingestion.test-supply=true","app.ingestion.worker-enabled=false",
 "spring.datasource.url=jdbc:h2:mem:ingestion-test-supply;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
 "app.media.upload-dir=${java.io.tmpdir}/megagraph-ingestion-test-supply-${random.uuid}"})
@ActiveProfiles("test")
class IngestionTestSupplyIntegrationTest {
 @Autowired IngestionService service;
 @Autowired IngestionWorker worker;
 @Autowired ProductService products;
 @Autowired UserAccountRepository users;
 @Autowired MerchantUserRepository memberships;
 @Test void demoGeneratedDraftHasTestOriginAndStillNeedsMerchantConfirmationAndPublication() {
  var user=users.findByUsernameIgnoreCase("merchant@megagraph.local").orElseThrow();
  var member=memberships.findByUserId(user.getId()).getFirst();var actor=new MerchantActor(user.getId(),member.getMerchantId(),MerchantRole.OWNER);
  var queued=service.create(actor,UUID.randomUUID().toString(),new MockMultipartFile("image","demo-test.png","image/png",TestImages.png()));
  assertThat(queued.status()).isEqualTo("QUEUED");assertThat(queued.productId()).isNull();assertThat(worker.runOnce()).isTrue();
  var done=service.get(actor,queued.id());assertThat(done.status()).isEqualTo("SUCCEEDED");
  var draft=products.get(actor,done.productId());assertThat(draft.evidence().dataOrigin()).isEqualTo("LOCAL_TEST");
  assertThat(draft.draftStatus()).isEqualTo("READY_FOR_CONFIRMATION");assertThat(draft.publishStatus()).isEqualTo("UNPUBLISHED");assertThat(draft.evidence().confirmedAt()).isNull();
 }
}
