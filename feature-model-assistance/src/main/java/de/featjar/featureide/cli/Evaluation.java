package de.featjar.featureide.cli;

import de.featjar.base.FeatJAR;
import de.featjar.base.cli.ACommand;
import de.featjar.base.cli.Option;
import de.featjar.base.cli.OptionList;
import de.featjar.base.cli.Options;
import de.featjar.base.data.Pair;
import de.featjar.feature.model.IFeature;
import de.featjar.feature.model.IFeatureModel;
import de.featjar.featureide.FeatJARWrapper;
import de.featjar.featureide.FeatureModelAnalyzer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;


/*
Run argument (for me):
    run --args="evaluate-simplifier --input '../feature-model-assistance/src/main/resources/uvlModelsInput/' --output '../feature-model-assistance/src/main/resources/uvlModelsOutputEvaluation/' --coreDead true --atomic_sets true"
 */
/** Evaluates {@link FeatureModelSimplifyerCommand} on a directory of UVL models. */
public class Evaluation extends ACommand {

    public static final Option<Boolean> CORE_DEAD_OPTION = Options.newOption("coreDead", Options.BooleanParser, "true")
            .setDescription("Enable core and dead feature removal (default: true)");

    public static final Option<Boolean> ATOMIC_SETS_OPTION = Options.newOption(
                    "atomic_sets", Options.BooleanParser, "true")
            .setDescription("Enable atomic-set feature removal (default: true)");

    private static final String STATS_FILE_NAME = "stats.csv";
    private static final List<String> STATS_HEADER = List.of(
            "input_file",
            "output_file",
            "original_features",
            "simplified_features",
            "feature_reduction_ratio",
            "original_constraints",
            "simplified_constraints",
            "constraint_reduction_ratio",
            "removed_core_features",
            "removed_dead_features",
            "removed_atomic_set_features");

    record Statistics(
            String inputFile,
            String outputFile,
            int originalFeatures,
            int simplifiedFeatures,
            double featureReductionRatio,
            int originalConstraints,
            int simplifiedConstraints,
            double constraintReductionRatio,
            int removedCoreFeatures,
            int removedDeadFeatures,
            int removedAtomicSetFeatures) {}

    @Override
    public Optional<String> getDescription() {
        return Optional.of("Evaluates feature-model simplification for all UVL files in a directory.");
    }

    @Override
    public Optional<String> getShortName() {
        return Optional.of("evaluate-simplifier");
    }

    @Override
    public int run(OptionList optionParser) {
        try {
            evaluate(
                    optionParser.get(INPUT_OPTION),
                    optionParser.get(OUTPUT_OPTION),
                    optionParser.get(CORE_DEAD_OPTION),
                    optionParser.get(ATOMIC_SETS_OPTION));
            return FeatJAR.EXIT_SUCCESS;
        } catch (Exception e) {
            FeatJAR.log().error(e);
            return FeatJAR.ERROR_COMPUTING_RESULT;
        }
    }

    List<Statistics> evaluate(
            Path inputDirectory, Path outputDirectory, boolean simplifyCoreAndDeadFeatures, boolean simplifyAtomicSets)
            throws IOException {
        requireInputDirectory(inputDirectory);
        requireOutputDirectory(outputDirectory);
        requireSeparateOutputDirectory(inputDirectory, outputDirectory);

        List<Path> inputFiles;
        try (Stream<Path> paths = Files.walk(inputDirectory)) {
            inputFiles = paths.filter(Files::isRegularFile)
                    .filter(Evaluation::isUVLFile)
                    .sorted(Comparator.comparing(path -> toPortablePath(inputDirectory.relativize(path))))
                    .toList();
        }

        Files.createDirectories(outputDirectory);
        FeatJARWrapper featJARWrapper = new FeatJARWrapper();
        FeatureModelSimplifyerCommand simplifier = new FeatureModelSimplifyerCommand();
        List<Statistics> statistics = new ArrayList<>(inputFiles.size());

        for (Path inputFile : inputFiles) {
            Path relativeInputPath = inputDirectory.relativize(inputFile);
            Path outputFile = outputDirectory.resolve(relativeInputPath);
            Path outputParent = outputFile.getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }

            FeatJAR.log().message("Evaluating " + inputFile.toAbsolutePath());
            statistics.add(evaluateSingleFile(
                    featJARWrapper,
                    simplifier,
                    inputFile,
                    outputFile,
                    relativeInputPath,
                    simplifyCoreAndDeadFeatures,
                    simplifyAtomicSets));
        }

        writeStatistics(outputDirectory.resolve(STATS_FILE_NAME), statistics);
        FeatJAR.log()
                .message("Evaluation statistics stored at: "
                        + outputDirectory.resolve(STATS_FILE_NAME).toAbsolutePath());
        return List.copyOf(statistics);
    }

    private Statistics evaluateSingleFile(
            FeatJARWrapper featJARWrapper,
            FeatureModelSimplifyerCommand simplifier,
            Path inputFile,
            Path outputFile,
            Path relativeInputPath,
            boolean simplifyCoreAndDeadFeatures,
            boolean simplifyAtomicSets)
            throws IOException {
        IFeatureModel inputModel = loadFeatureModel(featJARWrapper, inputFile);
        FeatureModelAnalyzer analyzer = featJARWrapper.featureModelAnalyzer(inputModel);

        Set<String> coreFeatures = simplifyCoreAndDeadFeatures
                ? new LinkedHashSet<>(analyzer.core().orElseThrow())
                : Set.of();
        Set<String> deadFeatures = simplifyCoreAndDeadFeatures
                ? new LinkedHashSet<>(analyzer.dead().orElseThrow())
                : Set.of();
        Set<String> atomicSetFeatures = simplifyAtomicSets ? collectAtomicSetFeatures(analyzer) : Set.of();

        IFeatureModel simplifiedModel =
                simplifier.reduceFeatureModel(inputModel, analyzer, simplifyCoreAndDeadFeatures, simplifyAtomicSets);
        featJARWrapper.storeFeatureModel(simplifiedModel, outputFile);

        // Reload both files so the statistics describe the persisted UVL models.
        IFeatureModel reloadedInputModel = loadFeatureModel(featJARWrapper, inputFile);
        IFeatureModel reloadedSimplifiedModel = loadFeatureModel(featJARWrapper, outputFile);
        Set<String> removedFeatures = featureNames(reloadedInputModel);
        removedFeatures.removeAll(featureNames(reloadedSimplifiedModel));

        int originalFeatureCount = reloadedInputModel.getNumberOfFeatures();
        int simplifiedFeatureCount = reloadedSimplifiedModel.getNumberOfFeatures();
        int originalConstraintCount = reloadedInputModel.getConstraints().size();
        int simplifiedConstraintCount = reloadedSimplifiedModel.getConstraints().size();

        return new Statistics(
                toPortablePath(relativeInputPath),
                toPortablePath(relativeInputPath),
                originalFeatureCount,
                simplifiedFeatureCount,
                reductionRatio(originalFeatureCount, simplifiedFeatureCount),
                originalConstraintCount,
                simplifiedConstraintCount,
                reductionRatio(originalConstraintCount, simplifiedConstraintCount),
                intersectionSize(removedFeatures, coreFeatures),
                intersectionSize(removedFeatures, deadFeatures),
                intersectionSize(removedFeatures, atomicSetFeatures));
    }

    private static IFeatureModel loadFeatureModel(FeatJARWrapper featJARWrapper, Path path) {
        return featJARWrapper.loadFeatureModel(path).orElseThrow();
    }

    private static Set<String> collectAtomicSetFeatures(FeatureModelAnalyzer analyzer) {
        Set<String> features = new LinkedHashSet<>();
        for (Pair<List<String>, List<String>> atomicSet : analyzer.atomicSets().orElseThrow()) {
            features.addAll(atomicSet.getFirst());
            features.addAll(atomicSet.getSecond());
        }
        return features;
    }

    private static Set<String> featureNames(IFeatureModel featureModel) {
        Set<String> names = new LinkedHashSet<>();
        for (IFeature feature : featureModel.getFeatures()) {
            feature.getName().ifPresent(names::add);
        }
        return names;
    }

    private static int intersectionSize(Set<String> removedFeatures, Set<String> classifiedFeatures) {
        int count = 0;
        for (String feature : removedFeatures) {
            if (classifiedFeatures.contains(feature)) {
                count++;
            }
        }
        return count;
    }

    private static double reductionRatio(int originalCount, int simplifiedCount) {
        return originalCount == 0 ? 0.0 : (double) (originalCount - simplifiedCount) / originalCount;
    }

    private static void writeStatistics(Path statisticsFile, List<Statistics> statistics) throws IOException {
        StringBuilder csv = new StringBuilder();
        appendCSVRow(csv, STATS_HEADER);
        for (Statistics row : statistics) {
            appendCSVRow(
                    csv,
                    List.of(
                            row.inputFile(),
                            row.outputFile(),
                            Integer.toString(row.originalFeatures()),
                            Integer.toString(row.simplifiedFeatures()),
                            formatRatio(row.featureReductionRatio()),
                            Integer.toString(row.originalConstraints()),
                            Integer.toString(row.simplifiedConstraints()),
                            formatRatio(row.constraintReductionRatio()),
                            Integer.toString(row.removedCoreFeatures()),
                            Integer.toString(row.removedDeadFeatures()),
                            Integer.toString(row.removedAtomicSetFeatures())));
        }
        Files.writeString(statisticsFile, csv, StandardCharsets.UTF_8);
    }

    private static void appendCSVRow(StringBuilder csv, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escapeCSV(fields.get(i)));
        }
        csv.append(System.lineSeparator());
    }

    private static String escapeCSV(String field) {
        if (field.indexOf(',') >= 0
                || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0) {
            return '"' + field.replace("\"", "\"\"") + '"';
        }
        return field;
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.ROOT, "%.6f", ratio);
    }

    private static boolean isUVLFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".uvl");
    }

    private static String toPortablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void requireInputDirectory(Path inputDirectory) {
        if (!Files.isDirectory(inputDirectory)) {
            throw new IllegalArgumentException("Input path must be an existing directory: " + inputDirectory);
        }
    }

    private static void requireOutputDirectory(Path outputDirectory) {
        if (Files.exists(outputDirectory) && !Files.isDirectory(outputDirectory)) {
            throw new IllegalArgumentException("Output path must be a directory: " + outputDirectory);
        }
    }

    private static void requireSeparateOutputDirectory(Path inputDirectory, Path outputDirectory) {
        Path normalizedInput = inputDirectory.toAbsolutePath().normalize();
        Path normalizedOutput = outputDirectory.toAbsolutePath().normalize();
        if (normalizedOutput.startsWith(normalizedInput)) {
            throw new IllegalArgumentException("Output directory must not be inside the input directory: "
                    + outputDirectory);
        }
    }
}
