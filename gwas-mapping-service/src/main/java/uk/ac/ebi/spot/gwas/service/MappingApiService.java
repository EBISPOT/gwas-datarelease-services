package uk.ac.ebi.spot.gwas.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import uk.ac.ebi.spot.gwas.constant.Location;
import uk.ac.ebi.spot.gwas.dto.Variation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Data
@Service
public class MappingApiService {

    private Integer ensemblCount = 0;
    private ObjectMapper mapper = new ObjectMapper();
    private Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private RestTemplate restTemplate;

    @Value("${mapping.ncbi_source}")
    private String ncbiSource;

    @Value("${mapping.ncbi_logic_name}")
    private String ncbiLogicName;

    @Value("${mapping.ncbi_db_type}")
    private String ncbiDbType;


    public Map<String, Variation> variationGet(String snpRsId) throws InterruptedException {
        Map<String, Variation> variationMap = new HashMap<>();
        String uri = String.format("%s/%s", Location.VARIATION, snpRsId);

        Variation variation = this.getRequest(uri)
                .map(response -> mapper.convertValue(response.getBody(), Variation.class))
                .orElseGet(Variation::new);
        variationMap.put(snpRsId, variation);
        return variationMap;
    }

    @Async("asyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletableFuture<Map<String, Variation>> variationPost(List<String> snpRsIds) {
        List<Object> cleaned = snpRsIds.stream().map(String::trim).collect(Collectors.toList());
        log.info("Start getting next {} snp rsIds from Ensembl", cleaned.size());
        Object response = postRequest(Collections.singletonMap("ids", cleaned), Location.VARIATION);
        Map<String, Variation> variantMap = mapper.convertValue(response, new TypeReference<Map<String, Variation>>() {});

        setEnsemblCount(cleaned.size());
        log.info("Finished getting {} snp rsIds from Ensembl", getEnsemblCount());
        return CompletableFuture.completedFuture(variantMap);
    }

    public Object postRequest(Map<String, Object> request, String uri) {
        Object response = null;
        try {
            response = restTemplate.postForObject(new URI(uri), request, Object.class);
        } catch (URISyntaxException | HttpStatusCodeException e) {
            log.error("Error: {} for SNP RsIds: {} retrying ...", e.getMessage(), request);
        }
        return response;
    }

    public Optional<ResponseEntity<Object>> getRequest(String uri) throws InterruptedException {
        ResponseEntity<Object> response = null;
        try {
            response = restTemplate.getForEntity(uri, Object.class);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().equals(HttpStatus.TOO_MANY_REQUESTS)) {
                log.info("warning: too many request {} retrying ...", uri);
                Thread.sleep(500);
                return this.getRequest(uri);
            }else{
                response = new ResponseEntity<>(Collections.singletonMap("error", e.getResponseBodyAsString()), HttpStatus.OK);
            }
        }
        return Optional.of(response);
    }
}