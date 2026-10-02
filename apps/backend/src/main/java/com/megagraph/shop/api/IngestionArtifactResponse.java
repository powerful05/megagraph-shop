package com.megagraph.shop.api;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
public record IngestionArtifactResponse(String inputHash, String provider, String model,
        String templateVersion, String protocolVersion, int attempt, Long elapsedMs,
        String rawOutput, JsonNode parsedOutput, JsonNode evidence, List<String> unknowns,
        JsonNode usage, String errorCode) {
 public IngestionArtifactResponse {
  if(usage!=null && usage.isNull()) usage=null;
  if(parsedOutput!=null && parsedOutput.isNull()) parsedOutput=null;
  if(evidence!=null && evidence.isNull()) evidence=null;
 }
}
