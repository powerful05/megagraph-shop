package com.megagraph.shop;
import static org.assertj.core.api.Assertions.assertThat;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
class MySqlPhase4UpgradeTest {
 @Test @EnabledIfEnvironmentVariable(named="MYSQL_PHASE4_UPGRADE_URL",matches=".+")
 void phaseThreeV9FactsSurviveAsyncIngestionMigration()throws Exception {
  String url=required("MYSQL_PHASE4_UPGRADE_URL"),user=required("MYSQL_PHASE4_UPGRADE_USERNAME"),password=required("MYSQL_PHASE4_UPGRADE_PASSWORD");
  try(var c=DriverManager.getConnection(url,user,password)) {
   assertThat(count(c,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")).as("Dedicated empty upgrade schema required").isZero();
   assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);assertThat(c.getMetaData().getDatabaseMinorVersion()).isGreaterThanOrEqualTo(4);
  }
  assertThat(Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target(MigrationVersion.fromVersion("9")).cleanDisabled(true).load().migrate().success).isTrue();
  try(var c=DriverManager.getConnection(url,user,password)) {
   execute(c,"INSERT INTO app_user(id,username,username_normalized,password_hash,display_name,status) VALUES(1,'v9-upgrade@example.test','v9-upgrade@example.test','test-only-hash','升级测试','ACTIVE')");
   execute(c,"INSERT INTO merchant(id,name,status) VALUES(1,'阶段3升级测试','ACTIVE')");
   execute(c,"INSERT INTO merchant_user(merchant_id,user_id,role) VALUES(1,1,'OWNER')");
   execute(c,"INSERT INTO product(id,merchant_id,title,draft_status,publish_status,version,supply_details,test_supply) VALUES(1,1,'阶段3测试商品','CONFIRMED','PUBLISHED',7,'{\"fabric\":\"待确认\"}',TRUE)");
   execute(c,"INSERT INTO product_sku(id,merchant_id,product_id,sku_code,sku_name,price,currency,available_stock,status) VALUES(1,1,1,'V9-UPGRADE','测试SKU',12.00,'CNY',6,'ENABLED')");
   execute(c,"INSERT INTO product_image(merchant_id,product_id,storage_key,original_filename,content_type,byte_size,image_role) VALUES(1,1,'test-only-v9.png','v9.png','image/png',12,'MAIN')");
   execute(c,"INSERT INTO sales_order(id,order_no,user_id,merchant_id,idempotency_key,status,currency,total_amount,recipient_name,recipient_phone,shipping_address,expires_at) VALUES(1,'4A-V9-UPGRADE',1,1,'v9-order','PENDING_PAYMENT','CNY',12.00,'测试','00000000000','测试地址',CURRENT_TIMESTAMP(3))");
   execute(c,"INSERT INTO sales_order_item(order_id,product_id,sku_id,merchant_id,product_title,sku_code,sku_name,unit_price,currency,quantity,line_amount) VALUES(1,1,1,1,'阶段3测试商品','V9-UPGRADE','测试SKU',12.00,'CNY',1,12.00)");
   execute(c,"INSERT INTO payment_order(order_id,provider,provider_payment_id,amount,currency,status) VALUES(1,'MOCK','v9-test-payment',12.00,'CNY','PENDING')");
   execute(c,"INSERT INTO ingestion_job(id,merchant_id,product_id,idempotency_key,status,original_filename,created_by,finished_at) VALUES(1,1,1,'v9-ingestion','SUCCEEDED','v9.png',1,CURRENT_TIMESTAMP(3))");
   execute(c,"INSERT INTO ingestion_step(job_id,merchant_id,step_type,status,attempt_count,result_json) VALUES(1,1,'MOCK_DRAFT_BUILD','SUCCEEDED',1,'{\"provider\":\"mock\"}')");
   execute(c,"INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload_json,status) VALUES('11111111-1111-4111-8111-111111111111','PRODUCT',1,7,'PRODUCT_PUBLISHED','{}','PENDING')");
   execute(c,"INSERT INTO SPRING_SESSION(PRIMARY_ID,SESSION_ID,CREATION_TIME,LAST_ACCESS_TIME,MAX_INACTIVE_INTERVAL,EXPIRY_TIME,PRINCIPAL_NAME) VALUES('22222222-2222-4222-8222-222222222222','33333333-3333-4333-8333-333333333333',1,1,28800,9999999999999,'v9-upgrade@example.test')");
   execute(c,"INSERT INTO SPRING_SESSION_ATTRIBUTES(SESSION_PRIMARY_ID,ATTRIBUTE_NAME,ATTRIBUTE_BYTES) VALUES('22222222-2222-4222-8222-222222222222','test-phase3-preserved',X'010203')");
   execute(c,"INSERT INTO guide_conversation(id,public_id,owner_user_id,title,next_sequence,event_version,created_at,updated_at) VALUES(1,'44444444-4444-4444-8444-444444444444',1,'阶段3会话',1,0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))");
  }
  var latest=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").cleanDisabled(true).load();assertThat(latest.migrate().success).isTrue();assertThat(latest.validateWithResult().validationSuccessful).isTrue();
  try(var c=DriverManager.getConnection(url,user,password)) {
   assertThat(count(c,"SELECT COUNT(*) FROM app_user WHERE username_normalized='v9-upgrade@example.test' AND status='ACTIVE'")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM product WHERE id=1 AND version=7 AND publish_status='PUBLISHED' AND test_supply=TRUE")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM product_sku WHERE id=1 AND price=12.00 AND available_stock=6")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM sales_order WHERE order_no='4A-V9-UPGRADE' AND total_amount=12.00 AND status='PENDING_PAYMENT'")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM sales_order_item WHERE order_id=1 AND quantity=1")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM payment_order WHERE provider_payment_id='v9-test-payment' AND amount=12.00")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME='v9-upgrade@example.test'")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='test-phase3-preserved' AND ATTRIBUTE_BYTES=X'010203'")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM guide_conversation WHERE public_id='44444444-4444-4444-8444-444444444444'")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM ingestion_job WHERE id=1 AND status='SUCCEEDED' AND product_id=1 AND version=0 AND attempt=0 AND storage_key IS NULL")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM ingestion_step WHERE job_id=1 AND status='SUCCEEDED' AND attempt_count=1")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM outbox_event WHERE event_type='PRODUCT_PUBLISHED' AND aggregate_version=7 AND status='PENDING'")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM ingestion_artifact")).isZero();
   assertThat(count(c,"SELECT COUNT(*) FROM sales_order WHERE id=1 AND delivery_type='PHYSICAL'")).isEqualTo(1);
   assertThat(count(c,"SELECT COUNT(*) FROM delivery_offer")).isZero();
   assertThat(count(c,"SELECT COUNT(*) FROM digital_asset")).isZero();
   assertThat(count(c,"SELECT COUNT(*) FROM delivery_purchase")).isZero();
   assertThat(count(c,"SELECT COUNT(*) FROM digital_entitlement")).isZero();
   assertThat(count(c,"SELECT COUNT(*) FROM flyway_schema_history WHERE version='12' AND success=TRUE")).isEqualTo(1);
   for(String table:new String[]{"appointment_offer","appointment_slot","appointment_purchase","appointment_reservation","appointment_account_lock","appointment_complete_key","appointment_merchant_lock"})assertThat(count(c,"SELECT COUNT(*) FROM "+table)).isZero();
  }
 }
 private static void execute(Connection c,String sql)throws SQLException {try(var s=c.createStatement()){s.executeUpdate(sql);}}
 private static long count(Connection c,String sql)throws SQLException {try(var s=c.createStatement();var rs=s.executeQuery(sql)){rs.next();return rs.getLong(1);}}
 private static String required(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required");return v;}
}
