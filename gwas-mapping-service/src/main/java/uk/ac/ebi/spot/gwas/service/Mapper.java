package uk.ac.ebi.spot.gwas.service;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.ac.ebi.spot.gwas.dto.*;
import uk.ac.ebi.spot.gwas.model.*;
import uk.ac.ebi.spot.gwas.util.MappingUtil;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
@Service
public class Mapper {


    @Value("${mapping.genomic_distance}")
    private int genomicDistance; // 100kb

    @Value("${mapping.ensembl_source}")
    private String ensemblSource;

    @Value("${mapping.ncbi_source}")
    private String ncbiSource;

    @Value("${mapping.method}")
    private String mappingMethod;

    private EnsemblData ensemblData;

    private String eRelease;

    public Collection<Location> getMappings(Variation variant) {
        Map<String, List<OverlapRegion>> cytoGeneticBand = ensemblData.getCytoGeneticBand();
        Collection<Location> locations = new ArrayList<>();
        List<Mapping> mappings = variant.getMappings();
        for (Mapping mapping : mappings) {
            String chromosome = mapping.getSeqRegionName();
            Integer position = mapping.getStart();

            if (Optional.ofNullable(chromosome).isPresent()) {
                String chrLocation = String.format("%s:%s-%s", chromosome, position, position);
                List<OverlapRegion> overlapRegions = cytoGeneticBand.get(chrLocation);

                Region region = new Region();
                if (!overlapRegions.isEmpty() && !Optional.ofNullable(overlapRegions.get(0).getError()).isPresent()) {
                    String cytogeneticBand = overlapRegions.get(0).getId();
                    Matcher matcher1 = Pattern.compile("^[0-9]+|[XY]$").matcher(chromosome); // Chromosomes
                    Matcher matcher2 = Pattern.compile("^MT$").matcher(chromosome);          // Mitochondria
                    if (matcher1.matches() || matcher2.matches()) {
                        region.setName(chromosome + cytogeneticBand);
                    }
                }
                Location location = new Location(chromosome, position, region);
                locations.add(location);
            }
        }
        return locations;
    }

    private MappingDto addGenomicContext(List<OverlapGene> geneList,
                                         Location snpLocation,
                                         String source,
                                         String type,
                                         EnsemblMappingResult mappingResult) {
        String closestGene = "";
        int closestDistance = 0;
        boolean intergenic = !type.equals("overlap");
        boolean upstream = type.equals("upstream");
        boolean downstream = type.equals("downstream");

        Integer position = snpLocation.getChromosomePosition();

        SingleNucleotidePolymorphism snpTmp = new SingleNucleotidePolymorphism();
        snpTmp.setRsId(mappingResult.getRsId());
        if (mappingResult.getRsId() == null) {
            throw new IllegalArgumentException("error, no RS ID found for location " + snpLocation.getId());
        }

        List<GenomicContext> genomicContexts = new ArrayList<>();

        // Get closest gene
        if (intergenic) {
            int pos = position;

            for (OverlapGene gene : geneList) {

                String geneName = gene.getExternalName();

                // If the source is NCBI, we parse the ID from the description:
                String geneId = source.equals(ncbiSource) ? MappingUtil.parseNCBIid(gene.getDescription(), geneName) : gene.getId();

                // Skip overlapping genes which also overlap upstream and/or downstream of the variant
                if (source.equals(ncbiSource)) {
                    if (geneName == null || mappingResult.getNcbiOverlappingGene().contains(geneName)) {
                        continue;
                    }
                } else {
                    if (geneName == null || mappingResult.getEnsemblOverlappingGene().contains(geneName)) {
                        continue;
                    }
                }

                int distance = 0;
                if (type.equals("upstream")) {
                    distance = pos - gene.getEnd();
                } else if (type.equals("downstream")) {
                    distance = gene.getStart() - pos;
                }

                if ((distance < closestDistance && distance > 0) || closestDistance == 0) {
                    closestGene = geneId;
                    closestDistance = distance;
                }
            }
        }

        for (OverlapGene gene : geneList) {
            String geneName = gene.getExternalName();

            // If the source is NCBI, we parse the ID from the description:
            String geneId = source.equals(ncbiSource) ? MappingUtil.parseNCBIid(gene.getDescription(), geneName) : gene.getId();
            String ncbiId = (source.equals(ncbiSource)) ? geneId : null;
            String ensemblId = (source.equals(ensemblSource)) ? geneId : null;
            int distance = 0;

            // Skip overlapping genes which also overlap upstream and/or downstream of the variant
            if (intergenic) {
                if (source.equals(ncbiSource)) {
                    if (geneName == null || mappingResult.getNcbiOverlappingGene().contains(geneName)) {
                        continue;
                    }
                } else {
                    if (geneName == null || mappingResult.getEnsemblOverlappingGene().contains(geneName)) {
                        continue;
                    }
                }

                int pos = position;
                if (type.equals("upstream")) {
                    distance = pos - gene.getEnd();
                } else if (type.equals("downstream")) {
                    distance = gene.getStart() - pos;
                }
            }
            Long dist = (long) distance;

            EntrezGene entrezGene = new EntrezGene();
            entrezGene.setEntrezGeneId(ncbiId);
            Collection<EntrezGene> entrezGenes = new ArrayList<>();
            entrezGenes.add(entrezGene);

            EnsemblGene ensemblGene = new EnsemblGene();
            ensemblGene.setEnsemblGeneId(ensemblId);
            Collection<EnsemblGene> ensemblGenes = new ArrayList<>();
            ensemblGenes.add(ensemblGene);

            // Check if the gene corresponds to the closest gene
            Gene geneObject = new Gene(geneName, entrezGenes, ensemblGenes);
            boolean isClosestGene = closestGene.equals(geneId) && !closestGene.equals("");
            genomicContexts.add(new GenomicContext(intergenic, upstream,
                                                   downstream, dist,
                                                   snpTmp, geneObject,
                                                   snpLocation, source,
                                                   mappingMethod, isClosestGene));
        }

        boolean closestFound = !closestGene.equals("");

        return MappingDto.builder()
                .closestFound(closestFound)
                .genomicContexts(genomicContexts).build();
    }

    private List<OverlapGene> getNearestGene(String chromosome,
                                             Integer snpPosition,
                                             Integer position,
                                             int boundary,
                                             String type, String source,
                                             EnsemblMappingResult mappingResult) {
        int position1 = position;
        int position2 = position;
        int snpPos = snpPosition;
        int newPos = position1;

        List<OverlapGene> closestGene = new ArrayList<>();
        int closestDistance = 0;
        if (type.equals("upstream")) {
            position1 = position2 - genomicDistance;
            position1 = (position1 < 0) ? boundary : position1;
            newPos = position1;
        } else {
            if (type.equals("downstream")) {
                position2 = position1 + genomicDistance;
                position2 = Math.min(position2, boundary);
                newPos = position2;
            }
        }

        Map<String, List<OverlapGene>> overlapGeneData =
                (source.equals(ncbiSource) ? ensemblData.getNcbiOverlapGene() : ensemblData.getEnsemblOverlapGene());

        String location = String.format("%s:%s-%s", chromosome, position1, position2);
        List<OverlapGene> overlapGenes = overlapGeneData.get(location);

        boolean geneError = false;
        if (overlapGenes != null && !overlapGenes.isEmpty()) {
            if (Optional.ofNullable(overlapGenes.get(0).getError()).isPresent()) {
                geneError = true;
            } else {
                for (OverlapGene overlapGene : overlapGenes) {
                    String geneName = overlapGene.getExternalName();

                    // Skip overlapping genes which also overlap upstream and/or downstream of the variant
                    if (source.equals(ncbiSource)) {
                        if (geneName == null || mappingResult.getNcbiOverlappingGene().contains(geneName)) {
                            continue;
                        }
                    } else {
                        if (geneName == null || mappingResult.getEnsemblOverlappingGene().contains(geneName)) {
                            continue;
                        }
                    }

                    int distance = 0;
                    if (type.equals("upstream")) {
                        distance = snpPos - overlapGene.getEnd();
                    } else if (type.equals("downstream")) {
                        distance = overlapGene.getStart() - snpPos;
                    }

                    if ((distance < closestDistance && distance > 0) || closestDistance == 0) {
                        closestGene = Collections.singletonList(overlapGene);
                        closestDistance = distance;
                    }
                }
                // Recursive code to find the nearest upstream or downstream gene
                if (closestGene.isEmpty() && newPos != boundary) {
                    closestGene = this.getNearestGene(chromosome, snpPosition, newPos, boundary, type, source, mappingResult);
                }
            }
        } else {
            // Recursive code to find the nearest upstream or downstream gene
            if (newPos != boundary) {
                closestGene = this.getNearestGene(chromosome, snpPosition, newPos, boundary, type, source, mappingResult);
            }
        }

        return closestGene;
    }

    // Check that the reported gene symbols exist and that they are located in the same chromosome as the variant
    public String checkReportedGenes(Collection<String> reportedGenes, Collection<Location> locations) {

        Map<String, GeneSymbol> reportedGenesMap = ensemblData.getReportedGenes();
        String pipelineError = "";

        for (String reportedGene : reportedGenes) {

            reportedGene = reportedGene.replace(" ", "");
            List<String> reportedGenesToIgnore = Arrays.asList("NR", "intergenic", "genic");

            if (!reportedGenesToIgnore.contains(reportedGene)) {
                GeneSymbol reportedGeneApiResult = reportedGenesMap.get(reportedGene);
                if (reportedGeneApiResult != null) {

                    if (reportedGeneApiResult.getError() != null && !reportedGeneApiResult.getError().isEmpty()) {
                        pipelineError = reportedGeneApiResult.getError();
                    } else if (reportedGeneApiResult.getSeqRegionName() != null) { // Check if the gene is in the same chromosome as the variant
                        if (!locations.isEmpty()) {
                            String geneChromosome = reportedGeneApiResult.getSeqRegionName();
                            int sameChromosome = 0;
                            for (Location location : locations) {
                                String snpChromosome = location.getChromosomeName();
                                if (geneChromosome.equals(snpChromosome)) {
                                    sameChromosome = 1;
                                    break;
                                }
                            }
                            if (sameChromosome == 0) {
                                pipelineError = String.format("Reported gene %s is on a different chromosome (chr %s)", reportedGene, geneChromosome);
                            }
                        } else {
                            pipelineError = String.format("Can't compare the %s location in Ensembl: no mapping available for the variant", reportedGene);
                        }
                    }
                    else {
                        pipelineError = String.format("Can't find a location in Ensembl for the reported gene %s", reportedGene);
                    }
                } else {
                    pipelineError = String.format("Reported gene check for %s returned no result", reportedGene);
                }
            }
        }
        return pipelineError;
    }
}
