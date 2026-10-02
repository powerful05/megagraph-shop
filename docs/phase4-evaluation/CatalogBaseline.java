import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.PublicProductResponse;
import com.megagraph.shop.guide.GuideCatalogSearch;
import com.megagraph.shop.guide.GuideIntent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Offline runner for the production search class. No service starts, keys, writes to DB, or model calls. */
public class CatalogBaseline {
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    static List<PublicProductResponse> products(JsonNode snapshot) throws Exception {
        return Arrays.asList(MAPPER.treeToValue(snapshot.get("products"), PublicProductResponse[].class));
    }
    static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    static double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) return 0;
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.max(0, (int)Math.ceil(sorted.size() * fraction) - 1));
    }
    static List<String> codes(List<PublicProductResponse> result) {
        // This v1 fixture has exactly one SKU per product; relevance unit is the product's single SKU code.
        return result.stream().map(p -> p.skus().getFirst().code()).toList();
    }
    static List<String> texts(JsonNode array) {
        var result = new ArrayList<String>();
        array.forEach(x -> result.add(x.asText()));
        return result;
    }
    static double dcg(List<String> ranked, Map<String,Integer> relevance) {
        double total = 0;
        for (int i=0; i<ranked.size(); i++) total += (Math.pow(2, relevance.getOrDefault(ranked.get(i), 0))-1) / (Math.log(i+2)/Math.log(2));
        return total;
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("snapshot queries output [fresh-public-snapshot|http://127.0.0.1:PORT]");
        Path snapshotFile=Path.of(args[0]), queryFile=Path.of(args[1]), output=Path.of(args[2]);
        JsonNode snapshot=MAPPER.readTree(snapshotFile.toFile()), dataset=MAPPER.readTree(queryFile.toFile());
        var catalog=products(snapshot);
        if (catalog.stream().anyMatch(p -> p.skus().size()!=1 || p.evidence()==null || !"LOCAL_TEST".equals(p.evidence().dataOrigin())))
            throw new IllegalArgumentException("v1 requires one-SKU LOCAL_TEST public products");
        var categories=new HashMap<Long,String>();
        snapshot.get("categoryNames").fields().forEachRemaining(e -> categories.put(Long.valueOf(e.getKey()), e.getValue().asText()));
        var search=new GuideCatalogSearch(categories);
        Map<Long,PublicProductResponse> fresh=new HashMap<>();
        boolean live=args.length==4 && args[3].startsWith("http:");
        boolean freshReplay=args.length==4 && !live;
        if(live && !args[3].matches("http://127\\.0\\.0\\.1:[0-9]+")) throw new IllegalArgumentException("Live check only allows loopback HTTP without credentials");
        if (freshReplay) for (var p: products(MAPPER.readTree(Path.of(args[3]).toFile()))) fresh.put(p.id(),p);
        int k=dataset.get("k").asInt(), limit=dataset.get("candidateLimit").asInt();
        if(k!=5 || limit!=20) throw new IllegalArgumentException("Frozen v1 requires K5/max20");
        var rows=new ArrayList<Map<String,Object>>();
        for (JsonNode query: dataset.get("queries")) {
            JsonNode j=query.get("intent");
            var intent=new GuideIntent(GuideIntent.DeliveryType.valueOf(j.get("deliveryType").asText()),
                    j.get("maxPrice").isNull()?null:new java.math.BigDecimal(j.get("maxPrice").asText()), j.get("currency").asText(),
                    texts(j.get("searchTerms")),texts(j.get("categoryKeywords")),texts(j.get("requiredColors")),texts(j.get("excludedColors")),
                    texts(j.get("requiredFabrics")),texts(j.get("excludedFabrics")),j.get("requiredSize").asText(),j.get("needsClarification").asBoolean());
            Map<String,Integer> relevance=new HashMap<>();
            query.get("relevanceBySkuCode").fields().forEachRemaining(e -> relevance.put(e.getKey(),e.getValue().asInt()));
            Set<String> catalogCodes=new HashSet<>(codes(catalog));
            if (!catalogCodes.containsAll(relevance.keySet())) throw new IllegalArgumentException("Snapshot missing judged SKU for "+query.get("id"));
            for(int warm=0;warm<40;warm++) search.search(catalog,intent,limit);
            var durations=new ArrayList<Double>();
            List<PublicProductResponse> candidates=List.of();
            for(int sample=0;sample<100;sample++) {
                long start=System.nanoTime(); candidates=search.search(catalog,intent,limit);
                durations.add((System.nanoTime()-start)/1_000_000.0);
            }
            var top=codes(candidates.stream().limit(k).toList());
            var ideal=relevance.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(k).map(Map.Entry::getKey).toList();
            long hits=top.stream().filter(code -> relevance.getOrDefault(code,0)>0).count();
            long rejected=catalog.stream().filter(p -> search.qualify(p,intent).isEmpty()).count();
            long finalRejected=0;
            var finalCheckDurations=new ArrayList<Double>();
            if(freshReplay || live) for(var p:candidates) {
                var current=fresh.get(p.id());
                if(live) {
                    long start=System.nanoTime();
                    var connection=(java.net.HttpURLConnection)new java.net.URI(args[3]+"/api/shop/v1/products/"+p.id()).toURL().openConnection(java.net.Proxy.NO_PROXY);
                    connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
                    try {
                        int status=connection.getResponseCode();
                        if(status==200) try(var body=connection.getInputStream()) {current=MAPPER.readValue(body,PublicProductResponse.class);}
                        else if(status==404) current=null;
                        else throw new IllegalStateException("Live public lookup failed: HTTP "+status);
                    } finally {connection.disconnect();}
                    finalCheckDurations.add((System.nanoTime()-start)/1_000_000.0);
                }
                if(current==null || !Objects.equals(current.evidence().productVersion(),p.evidence().productVersion()) || search.qualify(current,intent).isEmpty()) finalRejected++;
            }
            var row=new LinkedHashMap<String,Object>();
            row.put("id",query.get("id").asText());row.put("split",query.get("split").asText());row.put("top5",top);
            row.put("candidateCount",candidates.size());row.put("relevantProductCount",relevance.size());
            row.put("recallAt5",relevance.isEmpty()?null:(double)hits/relevance.size());
            row.put("recallAt5Ceiling",relevance.isEmpty()?null:(double)Math.min(k,relevance.size())/relevance.size());
            row.put("ndcgAt5",ideal.isEmpty()?null:dcg(top,relevance)/dcg(ideal,relevance));
            row.put("noResult",candidates.isEmpty());row.put("expectedEmpty",query.get("expectedEmpty").asBoolean());
            row.put("unexpectedNoResult",!relevance.isEmpty()&&candidates.isEmpty());
            row.put("constraintRejectedCount",rejected);row.put("catalogProductCount",catalog.size());
            row.put("offlineFreshSnapshotRejectedCount",freshReplay?finalRejected:null);
            row.put("finalLiveQualificationRejectedCount",live?finalRejected:null);
            row.put("liveLookupLatencySamples",finalCheckDurations);
            row.put("p50SearchMs",percentile(durations,.50));row.put("p95SearchMs",percentile(durations,.95));
            row.put("searchLatencySamples",durations);rows.add(row);
        }
        var summaries=new LinkedHashMap<String,Object>();
        for(String split:List.of("development","heldout")) {
            var subset=rows.stream().filter(r -> split.equals(r.get("split"))).toList();
            var positives=subset.stream().filter(r -> r.get("recallAt5")!=null).toList();
            var durations=new ArrayList<Double>();
            for(var r:subset) durations.addAll((List<Double>)r.get("searchLatencySamples"));
            var summary=new LinkedHashMap<String,Object>();
            summary.put("queryCount",subset.size());summary.put("positiveQueryCount",positives.size());
            summary.put("macroRecallAt5",positives.stream().mapToDouble(r -> (Double)r.get("recallAt5")).average().orElse(0));
            summary.put("macroNdcgAt5",positives.stream().mapToDouble(r -> (Double)r.get("ndcgAt5")).average().orElse(0));
            summary.put("noResultRate",(double)subset.stream().filter(r -> (Boolean)r.get("noResult")).count()/subset.size());
            summary.put("unexpectedNoResultRatePositiveQueries",(double)positives.stream().filter(r -> (Boolean)r.get("unexpectedNoResult")).count()/positives.size());
            long rejects=subset.stream().mapToLong(r -> ((Number)r.get("constraintRejectedCount")).longValue()).sum();
            long candidateCount=subset.stream().mapToLong(r -> ((Number)r.get("candidateCount")).longValue()).sum();
            summary.put("structuralConstraintRejectionRate",(double)rejects/(catalog.size()*subset.size()));
            summary.put("candidateCount",candidateCount);
            summary.put("offlineFreshSnapshotRejectionRate",freshReplay?(candidateCount==0?0:(double)subset.stream().mapToLong(r -> ((Number)r.get("offlineFreshSnapshotRejectedCount")).longValue()).sum()/candidateCount):null);
            summary.put("finalLiveQualificationFilterRate",live?(candidateCount==0?0:(double)subset.stream().mapToLong(r -> ((Number)r.get("finalLiveQualificationRejectedCount")).longValue()).sum()/candidateCount):null);
            var liveDurations=new ArrayList<Double>();
            for(var r:subset)liveDurations.addAll((List<Double>)r.get("liveLookupLatencySamples"));
            summary.put("p50LivePublicLookupMs",live?percentile(liveDurations,.50):null);summary.put("p95LivePublicLookupMs",live?percentile(liveDurations,.95):null);
            summary.put("p50OfflineSearchMs",percentile(durations,.50));summary.put("p95OfflineSearchMs",percentile(durations,.95));summaries.put(split,summary);
        }
        for(var row:rows) {row.remove("searchLatencySamples");row.remove("liveLookupLatencySamples");}
        var result=new LinkedHashMap<String,Object>();
        result.put("createdAt",java.time.Instant.now().toString());result.put("runner","production GuideCatalogSearch replay, no model calls");
        result.put("snapshotSha256",hash(snapshotFile));result.put("querySha256",hash(queryFile));
        result.put("databaseOrigin",snapshot.get("metadata").get("database").asText());
        result.put("freshSnapshotSha256",freshReplay?hash(Path.of(args[3])):null);
        result.put("liveFinalLookupBase",live?args[3]:null);
        result.put("k",k);result.put("candidateLimit",limit);result.put("independentJvmRuns",1);
        result.put("latencyScope","warmed Java search only; excludes database catalog reads, intent model, external projections, and live final check");
        result.put("splitSummary",summaries);result.put("queries",rows);
        Files.createDirectories(output.toAbsolutePath().getParent());MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),result);
        System.out.println(MAPPER.writeValueAsString(summaries));
    }
}
