package com.megagraph.shop.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.*;
import com.megagraph.shop.catalog.*;
import com.megagraph.shop.media.*;
import com.megagraph.shop.payment.CommerceOutboxWriter;
import com.megagraph.shop.security.MerchantActor;
import com.megagraph.shop.shared.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IngestionService {
    public record Claim(long id, long merchantId, int attempt, Instant leaseUntil, String filename,
                        String inputHash, String storageKey, String contentType, String scenario) {}
    private final IngestionJobRepository jobs;
    private final ProductRepository products;
    private final ProductImageRepository images;
    private final ImageUploadValidator validator;
    private final MediaStorage storage;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final CommerceOutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Environment environment;
    private final long leaseSeconds;
    private final long backoffSeconds;
    private final boolean testSupply;
    private final Set<String> pendingFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public IngestionService(IngestionJobRepository jobs, ProductRepository products, ProductImageRepository images,
            ImageUploadValidator validator, MediaStorage storage, JdbcTemplate db, ObjectMapper json,
            CommerceOutboxWriter outbox, PlatformTransactionManager manager, Environment environment,
            @Value("${app.ingestion.lease-seconds:60}") long leaseSeconds,
            @Value("${app.ingestion.backoff-seconds:2}") long backoffSeconds,
            @Value("${app.ingestion.test-supply:false}") boolean testSupply) {
        this.jobs=jobs; this.products=products; this.images=images; this.validator=validator; this.storage=storage;
        this.db=db; this.json=json; this.outbox=outbox; this.environment=environment;
        if(testSupply && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local","test")))
            throw new IllegalStateException("Ingestion test supply requires local/test profile");
        this.testSupply=testSupply || environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local & supply-demo"));
        this.leaseSeconds=leaseSeconds; this.backoffSeconds=backoffSeconds;
        this.tx=new TransactionTemplate(manager); this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public IngestionResponse create(MerchantActor actor, String key, MultipartFile image) { return create(actor,key,image,"success"); }
    public IngestionResponse create(MerchantActor actor, String key, MultipartFile image, String scenario) {
        if (key==null || key.isBlank() || key.length()>128) throw new ValidationException("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key不能为空且最多128字符");
        if (!Set.of("success","slow","timeout","malformed","retryable","fail").contains(scenario)) throw new ValidationException("MOCK_SCENARIO_INVALID","不支持的Mock情景");
        if (!scenario.equals("success") && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local","test"))) throw new ValidationException("MOCK_SCENARIO_DISABLED","当前环境不允许Mock故障注入");
        validator.validate(image);
        String filename=safeFilename(image.getOriginalFilename());
        String mime=image.getContentType().toLowerCase(Locale.ROOT);
        byte[] bytes;
        try { bytes=image.getBytes(); } catch(IOException e) { throw new ValidationException("IMAGE_READ_FAILED","无法读取图片"); }
        String inputHash=hash(bytes), payloadHash=hash((inputHash+"\n"+filename+"\n"+mime+"\n"+scenario).getBytes(StandardCharsets.UTF_8));
        String normalizedKey=key.trim();
        IngestionResponse existing=tx.execute(s -> existing(actor.merchantId(),normalizedKey,payloadHash));
        if (existing!=null) return existing;
        StoredMedia media;
        try { media=storage.store(actor.merchantId(),filename,mime,new java.io.ByteArrayInputStream(bytes)); }
        catch(IOException e) { throw new ValidationException("IMAGE_STORE_FAILED","图片保存失败，请重试"); }
        pendingFiles.add(media.storageKey());
        try {
            return tx.execute(s -> {
                IngestionResponse duplicate=existing(actor.merchantId(),normalizedKey,payloadHash);
                if (duplicate!=null) return duplicate;
                IngestionJob job=new IngestionJob(actor.merchantId(),normalizedKey,filename,actor.userId());
                job.input(inputHash,payloadHash,media,scenario); jobs.saveAndFlush(job);
                db.update("INSERT INTO ingestion_step(job_id,merchant_id,step_type,status,attempt_count) VALUES(?,?,'DRAFT_BUILD','PENDING',0)",job.getId(),actor.merchantId());
                outbox.save("INGESTION",job.getId(),0L,"INGESTION_QUEUED",Map.of("jobId",job.getId(),"merchantId",actor.merchantId()));
                return response(job);
            });
        } catch(DataIntegrityViolationException e) {
            IngestionResponse duplicate=tx.execute(s -> existing(actor.merchantId(),normalizedKey,payloadHash));
            if (duplicate==null) throw e;
            return duplicate;
        } finally {
            pendingFiles.remove(media.storageKey());
            // A concurrent winner has its own UUID key. Never remove any referenced key.
            if (!referenced(media.storageKey())) try { storage.delete(media.storageKey()); } catch(IOException ignored) { /* orphan scan retries safely */ }
        }
    }
    private IngestionResponse existing(long merchant,String key,String payloadHash) {
        return jobs.findByMerchantIdAndIdempotencyKey(merchant,key).map(j -> {
            if (!Objects.equals(payloadHash,j.getPayloadHash())) throw new ConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT","同一幂等键已用于不同图片或参数");
            return response(j);
        }).orElse(null);
    }
    public IngestionResponse get(MerchantActor actor,Long id) { return tx.execute(s -> response(owned(actor,id))); }
    public List<IngestionResponse> list(MerchantActor actor) {
        return tx.execute(s -> db.queryForList("SELECT id FROM ingestion_job WHERE merchant_id=? ORDER BY id DESC LIMIT 100",Long.class,actor.merchantId()).stream().map(id -> response(owned(actor,id))).toList());
    }
    public List<IngestionArtifactResponse> artifacts(MerchantActor actor,long id) {
        return tx.execute(state -> {
            owned(actor,id);
            return db.queryForList("SELECT artifact_json FROM ingestion_artifact WHERE job_id=? AND merchant_id=? ORDER BY attempt",String.class,id,actor.merchantId()).stream().map(value -> {
                try { return json.readValue(value,IngestionArtifactResponse.class); }
                catch(IOException e) { throw new IllegalStateException("Stored artifact invalid"); }
            }).toList();
        });
    }
    public IngestionResponse cancel(MerchantActor actor,Long id,long version) { return transition(actor,id,version,false); }
    public IngestionResponse retry(MerchantActor actor,Long id,long version) { return transition(actor,id,version,true); }
    private IngestionResponse transition(MerchantActor actor,Long id,long version,boolean retry) {
        return tx.execute(s -> {
            lock(id); IngestionJob j=owned(actor,id);
            if (j.getVersion()!=version) throw new ConflictException("VERSION_CONFLICT","任务已更新，请刷新");
            Set<IngestionStatus> allowed=retry?Set.of(IngestionStatus.DEAD_LETTER,IngestionStatus.FAILED):Set.of(IngestionStatus.QUEUED,IngestionStatus.RUNNING,IngestionStatus.RETRY_WAIT);
            if (!allowed.contains(j.getStatus())) throw new ConflictException("INGESTION_STATE_CONFLICT",retry?"仅失败任务可以重试":"当前任务不能取消");
            if(retry && j.getStorageKey()==null) throw new ConflictException("INGESTION_INPUT_MISSING","旧任务缺少输入图片，请重新上传");
            if(!retry && j.getStatus()==IngestionStatus.RUNNING) {
                String inputHash=db.queryForObject("SELECT input_hash FROM ingestion_job WHERE id=?",String.class,id);
                saveArtifact(new Claim(id,actor.merchantId(),j.getAttempt(),j.getLeaseUntil(),j.getOriginalFilename(),inputHash,j.getStorageKey(),j.getContentType(),"success"),
                    new IngestionArtifactResponse(inputHash,"mock","filename-draft-v2","merchant-draft-v1","draft-json-v1",j.getAttempt(),null,null,null,null,List.of("任务已取消，迟到结果不可采用"),null,"CANCELLED"));
            }
            db.update("UPDATE ingestion_job SET status=?,version=version+1,lease_until=NULL,next_attempt_at=NULL,error_code=NULL,error_summary=NULL,finished_at=?,retry_count=? WHERE id=?",retry?"QUEUED":"CANCELLED",retry?null:Timestamp.from(Instant.now()),0,id);
            db.update("UPDATE ingestion_step SET status=?,error_code=NULL,error_summary=NULL WHERE job_id=?",retry?"PENDING":"SKIPPED",id);
            outbox.save("INGESTION",id,version+1,retry?"INGESTION_RETRIED":"INGESTION_CANCELLED",Map.of("jobId",id));
            return fresh(id,actor);
        });
    }
    private IngestionResponse fresh(long id,MerchantActor actor) {
        // JDBC state transitions use their own short transaction; avoid a stale managed entity.
        return responseRow(id,actor.merchantId());
    }
    public Claim claim() { return claim(Instant.now()); }
    public Claim claim(Instant now) {
        List<Long> candidates=db.queryForList("SELECT id FROM ingestion_job WHERE storage_key IS NOT NULL AND (status='QUEUED' OR (status='RETRY_WAIT' AND next_attempt_at<=?) OR (status='RUNNING' AND lease_until<=?)) ORDER BY id LIMIT 20",Long.class,Timestamp.from(now),Timestamp.from(now));
        for (Long id:candidates) {
            Claim claim=tx.execute(s -> {
                lock(id);
                Map<String,Object> row=db.queryForMap("SELECT * FROM ingestion_job WHERE id=?",id);
                String state=(String)row.get("status");
                Instant lease=instant(row.get("lease_until")),next=instant(row.get("next_attempt_at"));
                if (!(state.equals("QUEUED") || state.equals("RETRY_WAIT") && next!=null && !next.isAfter(now) || state.equals("RUNNING") && lease!=null && !lease.isAfter(now))) return null;
                int count=((Number)row.get("retry_count")).intValue();
                if(state.equals("RUNNING")) {
                    int oldAttempt=((Number)row.get("attempt")).intValue();
                    var expired=new Claim(id,((Number)row.get("merchant_id")).longValue(),oldAttempt,lease,(String)row.get("original_filename"),(String)row.get("input_hash"),(String)row.get("storage_key"),(String)row.get("content_type"),(String)row.get("mock_scenario"));
                    saveArtifact(expired,new IngestionArtifactResponse(expired.inputHash(),"mock","filename-draft-v2","merchant-draft-v1","draft-json-v1",oldAttempt,null,null,null,null,List.of("租约过期，结果不可采用"),null,"LEASE_EXPIRED"));
                }
                if (count>=3) {
                    db.update("UPDATE ingestion_job SET status='DEAD_LETTER',version=version+1,lease_until=NULL,error_code='LEASE_EXHAUSTED',error_summary='任务执行租约已耗尽',finished_at=? WHERE id=?",Timestamp.from(now),id);
                    db.update("UPDATE ingestion_step SET status='FAILED',error_code='LEASE_EXHAUSTED',error_summary='任务执行租约已耗尽' WHERE job_id=?",id);
                    long version=((Number)row.get("version")).longValue()+1;
                    outbox.save("INGESTION",id,version,"INGESTION_DEAD_LETTER",Map.of("jobId",id,"errorCode","LEASE_EXHAUSTED"));
                    return null;
                }
                int attempt=((Number)row.get("attempt")).intValue()+1;
                Instant until=now.plusSeconds(leaseSeconds);
                db.update("UPDATE ingestion_job SET status='RUNNING',attempt=?,retry_count=retry_count+1,version=version+1,lease_until=?,next_attempt_at=NULL,started_at=?,finished_at=NULL,error_code=NULL,error_summary=NULL WHERE id=?",attempt,Timestamp.from(until),Timestamp.from(now),id);
                db.update("UPDATE ingestion_step SET status='RUNNING',attempt_count=?,started_at=?,finished_at=NULL,error_code=NULL,error_summary=NULL WHERE job_id=?",attempt,Timestamp.from(now),id);
                outbox.save("INGESTION",id,((Number)row.get("version")).longValue()+1,"INGESTION_RUNNING",Map.of("jobId",id,"attempt",attempt));
                return new Claim(id,((Number)row.get("merchant_id")).longValue(),attempt,until,(String)row.get("original_filename"),(String)row.get("input_hash"),(String)row.get("storage_key"),(String)row.get("content_type"),(String)row.get("mock_scenario"));
            });
            if (claim!=null) return claim;
        }
        return null;
    }
    public boolean complete(Claim claim,DraftSuggestion suggestion,long elapsedMs) {
        // Parse before entering the write transaction. A provider can never supply price/stock/status.
        if (suggestion==null || suggestion.title()==null || suggestion.title().isBlank() || suggestion.title().length()>200 || suggestion.subtitle()==null || suggestion.subtitle().length()>300) throw new IngestionProviderException("OUTPUT_INVALID",false);
        com.fasterxml.jackson.databind.JsonNode evidence;
        try { evidence=json.readTree(suggestion.evidenceJson()); if(evidence==null || !evidence.isObject()) throw new IllegalArgumentException(); }
        catch(Exception e) { throw new IngestionProviderException("OUTPUT_INVALID",false); }
        var parsed=json.valueToTree(Map.of("title",suggestion.title(),"subtitle",suggestion.subtitle()));
        String raw=serialize(Map.of("title",suggestion.title(),"subtitle",suggestion.subtitle(),"evidence",evidence));
        IngestionArtifactResponse artifact=new IngestionArtifactResponse(claim.inputHash(),"mock","filename-draft-v2","merchant-draft-v1","draft-json-v1",claim.attempt(),elapsedMs,raw,parsed,evidence,List.of("材质待商家确认","性能待商家确认","规格待商家确认"),null,null);
        return tx.execute(s -> {
            lock(claim.id()); if(!valid(claim,Instant.now())) return false;
            Product p=products.save(new Product(claim.merchantId(),suggestion.title()));
            p.revise(suggestion.title(),suggestion.subtitle(),null);
            if(testSupply) p.markTestSupply();
            products.flush();
            images.save(new ProductImage(claim.merchantId(),p.getId(),claim.storageKey(),claim.filename(),claim.contentType(),db.queryForObject("SELECT byte_size FROM ingestion_job WHERE id=?",Long.class,claim.id()),ImageRole.MAIN,0));
            saveArtifact(claim,artifact);
            db.update("UPDATE ingestion_job SET status='SUCCEEDED',product_id=?,version=version+1,lease_until=NULL,next_attempt_at=NULL,finished_at=? WHERE id=?",p.getId(),Timestamp.from(Instant.now()),claim.id());
            db.update("UPDATE ingestion_step SET status='SUCCEEDED',result_json=?,finished_at=? WHERE job_id=?",serialize(parsed),Timestamp.from(Instant.now()),claim.id());
            outbox.save("INGESTION",claim.id(),db.queryForObject("SELECT version FROM ingestion_job WHERE id=?",Long.class,claim.id()),"INGESTION_SUCCEEDED",Map.of("jobId",claim.id(),"productId",p.getId(),"merchantId",claim.merchantId()));
            return true;
        });
    }
    public boolean fail(Claim claim,String code,boolean retryable,long elapsedMs) { return fail(claim,code,retryable,elapsedMs,null); }
    public boolean fail(Claim claim,String code,boolean retryable,long elapsedMs,DraftSuggestion rawSuggestion) {
        return tx.execute(s -> {
            lock(claim.id()); if(!valid(claim,Instant.now())) return false;
            int count=db.queryForObject("SELECT retry_count FROM ingestion_job WHERE id=?",Integer.class,claim.id());
            String state=retryable && count<3?"RETRY_WAIT":"DEAD_LETTER";
            Instant next=state.equals("RETRY_WAIT")?Instant.now().plusSeconds(backoffSeconds*(1L<<(count-1))):null;
            String safeCode=Set.of("OUTPUT_INVALID","PROVIDER_TIMEOUT","PROVIDER_RETRYABLE","PROVIDER_UNAVAILABLE","INPUT_MEDIA_CHANGED","WORKER_ERROR").contains(code)?code:"WORKER_ERROR";
            String summary=safeCode.equals("OUTPUT_INVALID")?"AI输出无法解析，需人工检查后重试":"AI生成暂未完成，请查看任务状态后重试";
            db.update("UPDATE ingestion_job SET status=?,version=version+1,lease_until=NULL,next_attempt_at=?,error_code=?,error_summary=?,finished_at=? WHERE id=?",state,next==null?null:Timestamp.from(next),safeCode,summary,next==null?Timestamp.from(Instant.now()):null,claim.id());
            db.update("UPDATE ingestion_step SET status=?,error_code=?,error_summary=?,finished_at=? WHERE job_id=?",state.equals("RETRY_WAIT")?"RETRY_WAIT":"FAILED",safeCode,summary,Timestamp.from(Instant.now()),claim.id());
            saveArtifact(claim,new IngestionArtifactResponse(claim.inputHash(),"mock","filename-draft-v2","merchant-draft-v1","draft-json-v1",claim.attempt(),elapsedMs,rawSuggestion==null?null:serialize(rawSuggestion),null,null,List.of("生成失败，事实尚未确认"),null,safeCode));
            outbox.save("INGESTION",claim.id(),db.queryForObject("SELECT version FROM ingestion_job WHERE id=?",Long.class,claim.id()),"INGESTION_"+state,Map.of("jobId",claim.id(),"errorCode",safeCode));
            return true;
        });
    }
    public MediaPayload inputMedia(MerchantActor actor,long id) {
        IngestionJob j=tx.execute(s -> owned(actor,id));
        if(j.getStorageKey()==null) throw new NotFoundException("MEDIA_NOT_FOUND","原任务未保存输入图片");
        return new MediaPayload(storage.load(j.getStorageKey()),j.getContentType());
    }
    public int cleanupOrphans(Instant cutoff) throws IOException {
        if(cutoff.isAfter(Instant.now().minusSeconds(86400))) throw new IllegalArgumentException("Orphan grace period must be at least 24 hours");
        int deleted=0;
        for(String key:storage.keysOlderThan(cutoff)) if(!pendingFiles.contains(key) && !referenced(key)) {storage.delete(key);deleted++;}
        return deleted;
    }
    private boolean referenced(String key) { return db.queryForObject("SELECT (SELECT COUNT(*) FROM ingestion_job WHERE storage_key=?) + (SELECT COUNT(*) FROM product_image WHERE storage_key=?)",Long.class,key,key)>0; }
    private void saveArtifact(Claim c,IngestionArtifactResponse a) { db.update("INSERT INTO ingestion_artifact(job_id,merchant_id,attempt,artifact_json) VALUES(?,?,?,?)",c.id(),c.merchantId(),c.attempt(),serialize(a)); }
    private void lock(long id) { if(db.queryForList("SELECT id FROM ingestion_job WHERE id=? FOR UPDATE",Long.class,id).isEmpty()) throw new NotFoundException("INGESTION_NOT_FOUND","入库任务不存在"); }
    private boolean valid(Claim c,Instant now) { return db.queryForObject("SELECT COUNT(*) FROM ingestion_job WHERE id=? AND merchant_id=? AND status='RUNNING' AND attempt=? AND lease_until>? AND product_id IS NULL",Integer.class,c.id(),c.merchantId(),c.attempt(),Timestamp.from(now))==1; }
    private IngestionJob owned(MerchantActor a,long id) { return jobs.findByIdAndMerchantId(id,a.merchantId()).orElseThrow(() -> new NotFoundException("INGESTION_NOT_FOUND","入库任务不存在")); }
    private IngestionResponse response(IngestionJob j) { return responseRow(j.getId(),j.getMerchantId()); }
    private IngestionResponse responseRow(long id,long merchant) {
        Map<String,Object> r=db.queryForMap("SELECT * FROM ingestion_job WHERE id=? AND merchant_id=?",id,merchant);
        List<IngestionStepResponse> steps=db.query("SELECT * FROM ingestion_step WHERE job_id=? ORDER BY id",(rs,n)->new IngestionStepResponse(rs.getString("step_type"),rs.getString("status"),rs.getInt("attempt_count"),rs.getString("error_code"),rs.getString("error_summary")),id);
        List<String> artifacts=db.queryForList("SELECT artifact_json FROM ingestion_artifact WHERE job_id=? AND merchant_id=? ORDER BY attempt DESC LIMIT 1",String.class,id,merchant);
        IngestionArtifactResponse artifact=null;
        if(!artifacts.isEmpty()) try { artifact=json.readValue(artifacts.getFirst(),IngestionArtifactResponse.class); } catch(IOException e) { throw new IllegalStateException("Stored artifact invalid"); }
        return new IngestionResponse(((Number)r.get("id")).longValue(),r.get("product_id")==null?null:((Number)r.get("product_id")).longValue(),(String)r.get("status"),(String)r.get("original_filename"),instant(r.get("created_at")),instant(r.get("finished_at")),steps,((Number)r.get("version")).longValue(),((Number)r.get("attempt")).intValue(),instant(r.get("lease_until")),instant(r.get("next_attempt_at")),(String)r.get("error_code"),(String)r.get("error_summary"),artifact);
    }
    private static Instant instant(Object o) { if(o==null)return null; if(o instanceof Timestamp t)return t.toInstant(); if(o instanceof java.time.LocalDateTime t)return t.toInstant(java.time.ZoneOffset.UTC); if(o instanceof java.time.OffsetDateTime t)return t.toInstant(); throw new IllegalStateException("Unexpected database timestamp"); }
    private String serialize(Object o) { try{return json.writeValueAsString(o);}catch(IOException e){throw new IllegalStateException("Cannot encode artifact");} }
    private static String hash(byte[] bytes) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    private static String safeFilename(String f) { if(f==null || f.isBlank())return "product-image"; String n=f.replace('\\','/'); String base=n.substring(n.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","").trim(); if(base.isEmpty())return "product-image"; return base.length()>255?base.substring(base.length()-255):base; }
}
