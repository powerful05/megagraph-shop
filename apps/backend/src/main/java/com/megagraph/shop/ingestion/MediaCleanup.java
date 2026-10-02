package com.megagraph.shop.ingestion;

import com.megagraph.shop.media.LocalMediaStorage;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Standalone maintenance. Does not start Spring Boot, HTTP, migrations, seeds or schedulers. */
public final class MediaCleanup {
 private MediaCleanup() {}
 public static void main(String[] args) throws Exception {
  // The operator must use the protected configuration of the target Demo.
  // No credentials or storage keys are logged. Run during a maintenance window.
  String url=required("MEDIA_CLEANUP_DB_URL");
  String username=required("MEDIA_CLEANUP_DB_USERNAME");
  String password=System.getenv("MEDIA_CLEANUP_DB_PASSWORD");
  if(password==null) throw new IllegalStateException("MEDIA_CLEANUP_DB_PASSWORD must be configured");
  var dataSource=new DriverManagerDataSource(url,username,password);
  var db=new JdbcTemplate(dataSource);
  var storage=new LocalMediaStorage(required("MEDIA_CLEANUP_UPLOAD_DIR"));
  var cutoff=Instant.now().minusSeconds(86400);
  int deleted=0;
  for(String key:storage.keysOlderThan(cutoff)) {
   long references=db.queryForObject("SELECT (SELECT COUNT(*) FROM ingestion_job WHERE storage_key=?) + (SELECT COUNT(*) FROM product_image WHERE storage_key=?)",Long.class,key,key);
   if(references==0){ storage.delete(key);deleted++; }
  }
  System.out.println("Unreferenced media files removed after 24h grace: "+deleted);
 }
 private static String required(String name){String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalStateException(name+" must be configured");return value;}
}
