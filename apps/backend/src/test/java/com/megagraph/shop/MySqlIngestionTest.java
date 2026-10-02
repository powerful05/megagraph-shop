package com.megagraph.shop;
import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@EnabledIfEnvironmentVariable(named="MYSQL_INGESTION_URL",matches=".+")
class MySqlIngestionTest extends IngestionIntegrationTest {
 @DynamicPropertySource static void mysql(DynamicPropertyRegistry r)throws Exception {
  String url=required("MYSQL_INGESTION_URL"),user=required("MYSQL_INGESTION_USERNAME"),password=required("MYSQL_INGESTION_PASSWORD");
  try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")) {
   rs.next();assertThat(rs.getLong(1)).as("Dedicated empty ingestion schema required").isZero();assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);assertThat(c.getMetaData().getDatabaseMinorVersion()).isGreaterThanOrEqualTo(4);
  }
  r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->user);r.add("spring.datasource.password",()->password);r.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
 }
 static String required(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required");return v;}
}
