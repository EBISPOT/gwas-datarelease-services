package uk.ac.ebi.spot.gwas.service.loader;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.ac.ebi.spot.gwas.config.AppConfig;
import uk.ac.ebi.spot.gwas.constant.DataType;
import uk.ac.ebi.spot.gwas.dto.*;
import uk.ac.ebi.spot.gwas.util.MappingUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Service
public class FileCacheService {

    @Autowired
    private CytoGeneticBandService cytoGeneticBandService;
    @Autowired
    private VariationService variationService;
    @Autowired
    private ReportedGeneService reportedGeneService;
    @Autowired
    private AssemblyInfoService assemblyInfoService;
    @Autowired
    private OverlappingGeneService overlappingGeneService;

    @Autowired
    private AppConfig config;

    private static final Integer API_BATCH_SIZE = 200;
    private static final Integer DB_BATCH_SIZE = 1000;
    private static final Integer THREAD_SIZE = 15;

    public EnsemblData cacheEnsemblData(MappingDto mappingDto) throws ExecutionException, InterruptedException, IOException {
        List<String> snpRsIds = mappingDto.getSnpRsIds();
        List<String> reportedGenes = mappingDto.getReportedGenes();

        Path path = Paths.get(config.getCacheDir());
        Files.createDirectories(path);

        Map<String, GeneSymbol> reportedGeneMap = reportedGeneService.getReportedGenes(THREAD_SIZE, API_BATCH_SIZE, reportedGenes);
        Map<String, Variation> variantMap = variationService.getVariation(THREAD_SIZE, API_BATCH_SIZE, snpRsIds);
        variantMap = variationService.getVariationsWhoseRsidHasChanged(variantMap, snpRsIds);

        List<Variation> variants = new ArrayList<>();
        variantMap.forEach((k, v) -> {
            if (v.getMappings() != null) {
                variants.add(v);
            }
        });

        // Get CytoGenetic Bands
        List<String> locations = MappingUtil.getAllChromosomesAndPositions(variants);
        Map<String, List<OverlapRegion>> cytoGeneticBand = cytoGeneticBandService.getCytoGeneticBands(DataType.CYTOGENETIC_BAND, locations);

        // Get Chromosome End
        List<String> chromosomes = MappingUtil.getAllChromosomes(variants);
        Map<String, AssemblyInfo> assemblyInfos = assemblyInfoService.getAssemblyInfo(DataType.ASSEMBLY_INFO, chromosomes);

        // Get Overlapping genes
        Map<String, List<OverlapGene>> ensemblOverlappingGenes = overlappingGeneService.getOverlappingGenes(DataType.ENSEMBL_OVERLAP_GENES, config.getEnsemblSource(), locations);
        Map<String, List<OverlapGene>> ncbiOverlappingGenes = overlappingGeneService.getOverlappingGenes(DataType.NCBI_OVERLAP_GENES, config.getNcbiSource(), locations);

        // Get Upstream Genes
        List<String> upstreamLocations = MappingUtil.getUpstreamLocations(variants, config.getGenomicDistance());
        ensemblOverlappingGenes.putAll(overlappingGeneService.getOverlappingGenes(DataType.ENSEMBL_UPSTREAM_GENES, config.getEnsemblSource(), upstreamLocations));
        ncbiOverlappingGenes.putAll(overlappingGeneService.getOverlappingGenes(DataType.NCBI_UPSTREAM_GENES, config.getNcbiSource(), upstreamLocations));

        // Get Downstream Genes
        List<String> downStreamLocations = MappingUtil.getDownstreamLocations(variants, assemblyInfos, config.getGenomicDistance());
        ensemblOverlappingGenes.putAll(overlappingGeneService.getOverlappingGenes(DataType.ENSEMBL_DOWNSTREAM_GENES, config.getEnsemblSource(), downStreamLocations));
        ncbiOverlappingGenes.putAll(overlappingGeneService.getOverlappingGenes(DataType.NCBI_DOWNSTREAM_GENES, config.getNcbiSource(), downStreamLocations));

        return EnsemblData.builder()
                .variations(variantMap)
                .reportedGenes(reportedGeneMap)
                .cytoGeneticBand(cytoGeneticBand)
                .assemblyInfo(assemblyInfos)
                .ensemblOverlapGene(ensemblOverlappingGenes)
                .ncbiOverlapGene(ncbiOverlappingGenes)
                .build();
    }

}