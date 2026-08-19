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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.ToDoubleFunction;
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

    public static final Option<Integer> PROGRESS_INTERVAL_OPTION = Options.newOption(
                    "progress_interval", Options.IntegerParser, "30")
            .setDescription("Seconds between progress heartbeats; use 0 to disable them (default: 30)");

    public static final Option<Integer> MODEL_TIMEOUT_OPTION = Options.newOption(
                    "model_timeout", Options.IntegerParser, "0")
            .setDescription("Maximum seconds per model; use 0 for no timeout (default: 0)");

    private static final String STATS_FILE_NAME = "stats.csv";
    private static final String STATS_META_FILE_NAME = "stats-meta.csv";
    private static final String FAILURES_FILE_NAME = "failures.csv";
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
            "removed_atomic_set_features",
            "full_reduction");

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
            int removedAtomicSetFeatures,
            boolean fullReduction) {}

    private record AggregateStatistics(double mean, double standardDeviation) {}

    private record EvaluationFailure(String inputFile, String reason, String message) {}

    private static final class UnsatisfiableFeatureModelException extends IllegalArgumentException {

        private UnsatisfiableFeatureModelException() {
            super("Feature model is unsatisfiable and cannot be simplified");
        }
    }

    private static final class InputFeatureModelLoadException extends IllegalArgumentException {

        private InputFeatureModelLoadException(String message) {
            super(message);
        }
    }

    private static final class UnsupportedFeatureTypesException extends IllegalArgumentException {

        private UnsupportedFeatureTypesException(String message) {
            super(message);
        }
    }

    private static final class ModelTimeoutException extends RuntimeException {

        private ModelTimeoutException(int timeoutSeconds) {
            super("Model evaluation exceeded the timeout of " + timeoutSeconds + " seconds");
        }
    }

    private static final class ProgressReporter implements AutoCloseable {

        private final String inputFile;
        private final long evaluationStartedNanos = System.nanoTime();
        private final ScheduledExecutorService heartbeatExecutor;
        private volatile Thread evaluationThread;
        private volatile String currentStage = "starting";
        private volatile long stageStartedNanos = evaluationStartedNanos;
        private volatile long completedProjectionVariables = -1;
        private volatile long totalProjectionVariables = -1;
        private volatile boolean finished;

        private ProgressReporter(String inputFile, int heartbeatIntervalSeconds) {
            this.inputFile = inputFile;
            if (heartbeatIntervalSeconds > 0) {
                heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "feature-model-evaluation-progress");
                    thread.setDaemon(true);
                    return thread;
                });
                heartbeatExecutor.scheduleAtFixedRate(
                        this::logHeartbeat, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
            } else {
                heartbeatExecutor = null;
            }
        }

        private void stage(String stage) {
            long now = System.nanoTime();
            stageStartedNanos = now;
            currentStage = stage;
            completedProjectionVariables = -1;
            totalProjectionVariables = -1;
            /*
            FeatJAR.log()
                    .message(progressPrefix() + stage + " | total elapsed "
                            + formatElapsed(now - evaluationStartedNanos));
             */
        }

        private void attachCurrentThread() {
            evaluationThread = Thread.currentThread();
        }

        private void projectionProgress(long completedVariables, long totalVariables) {
            completedProjectionVariables = completedVariables;
            totalProjectionVariables = totalVariables;
        }

        private void complete() {
            finished = true;
            /*
            long now = System.nanoTime();
            FeatJAR.log()
                    .message(progressPrefix() + "completed | total elapsed "
                            + formatElapsed(now - evaluationStartedNanos));
             */
        }

        private void logHeartbeat() {
            if (finished) {
                return;
            }
            long now = System.nanoTime();
            String stage = currentStage;
            long stageStarted = stageStartedNanos;
            Thread worker = evaluationThread;
            StringBuilder message = new StringBuilder(progressPrefix())
                    .append("still running: ")
                    .append(stage);
            long totalVariables = totalProjectionVariables;
            if (totalVariables > 0) {
                long completedVariables = Math.min(completedProjectionVariables, totalVariables);
                double percentage = 100.0 * completedVariables / totalVariables;
                message.append(" | projection progress ")
                        .append(completedVariables)
                        .append('/')
                        .append(totalVariables)
                        .append(" variables (")
                        .append(String.format(Locale.ROOT, "%.1f", percentage))
                        .append("%; variable-count estimate)");
            }
            message.append(" | stage elapsed ")
                    .append(formatElapsed(now - stageStarted))
                    .append(" | total elapsed ")
                    .append(formatElapsed(now - evaluationStartedNanos))
                    .append(" | worker state ")
                    .append(worker == null ? "NOT_STARTED" : worker.getState());
            FeatJAR.log().message(message.toString());
        }

        private String progressPrefix() {
            return "[Progress] " + inputFile + " | ";
        }

        @Override
        public void close() {
            finished = true;
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdownNow();
            }
        }
    }

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
                    optionParser.get(ATOMIC_SETS_OPTION),
                    optionParser.get(PROGRESS_INTERVAL_OPTION),
                    optionParser.get(MODEL_TIMEOUT_OPTION));
            return FeatJAR.EXIT_SUCCESS;
        } catch (Exception e) {
            FeatJAR.log().error(e);
            return FeatJAR.ERROR_COMPUTING_RESULT;
        }
    }

    List<Statistics> evaluate(
            Path inputDirectory,
            Path outputDirectory,
            boolean simplifyCoreAndDeadFeatures,
            boolean simplifyAtomicSets,
            int progressIntervalSeconds)
            throws IOException {
        return evaluate(
                inputDirectory,
                outputDirectory,
                simplifyCoreAndDeadFeatures,
                simplifyAtomicSets,
                progressIntervalSeconds,
                0);
    }

    List<Statistics> evaluate(
            Path inputDirectory,
            Path outputDirectory,
            boolean simplifyCoreAndDeadFeatures,
            boolean simplifyAtomicSets,
            int progressIntervalSeconds,
            int modelTimeoutSeconds)
            throws IOException {
        requireInputDirectory(inputDirectory);
        requireOutputDirectory(outputDirectory);
        requireSeparateOutputDirectory(inputDirectory, outputDirectory);
        if (progressIntervalSeconds < 0) {
            throw new IllegalArgumentException("Progress interval must be zero or greater: " + progressIntervalSeconds);
        }
        if (modelTimeoutSeconds < 0) {
            throw new IllegalArgumentException("Model timeout must be zero or greater: " + modelTimeoutSeconds);
        }

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
        List<EvaluationFailure> failures = new ArrayList<>();

        for (int inputFileIndex = 0; inputFileIndex < inputFiles.size(); inputFileIndex++) {
            Path inputFile = inputFiles.get(inputFileIndex);
            Path relativeInputPath = inputDirectory.relativize(inputFile);
            Path outputFile = outputDirectory.resolve(relativeInputPath);
            Path outputParent = outputFile.getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }

            FeatJAR.log()
                    .message("Evaluating [" + (inputFileIndex + 1) + "/" + inputFiles.size() + "] "
                            + inputFile.toAbsolutePath());
            try (ProgressReporter progress =
                    new ProgressReporter(toPortablePath(relativeInputPath), progressIntervalSeconds)) {
                statistics.add(evaluateSingleFileWithTimeout(
                        featJARWrapper,
                        simplifier,
                        inputFile,
                        outputFile,
                        relativeInputPath,
                        simplifyCoreAndDeadFeatures,
                        simplifyAtomicSets,
                        progress,
                        modelTimeoutSeconds));
            } catch (InputFeatureModelLoadException e) {
                failures.add(new EvaluationFailure(
                        toPortablePath(relativeInputPath), "input_model_load_error", e.getMessage()));
                FeatJAR.log().warning("Skipping invalid or unreadable input model: " + inputFile.toAbsolutePath());
            } catch (UnsupportedFeatureTypesException e) {
                failures.add(new EvaluationFailure(
                        toPortablePath(relativeInputPath), "unsupported_feature_types", e.getMessage()));
                FeatJAR.log()
                        .warning(
                                "Skipping feature model with unsupported feature types: " + inputFile.toAbsolutePath());
            } catch (UnsatisfiableFeatureModelException e) {
                failures.add(new EvaluationFailure(
                        toPortablePath(relativeInputPath), "unsatisfiable_feature_model", e.getMessage()));
                FeatJAR.log().warning("Skipping unsatisfiable feature model: " + inputFile.toAbsolutePath());
            } catch (ModelTimeoutException e) {
                failures.add(new EvaluationFailure(toPortablePath(relativeInputPath), "model_timeout", e.getMessage()));
                Files.deleteIfExists(outputFile);
                FeatJAR.log().warning("Skipping model after timeout: " + inputFile.toAbsolutePath());
            }
        }

        Path statisticsDirectory = outputDirectory.toAbsolutePath().normalize().getParent();
        if (statisticsDirectory == null) {
            throw new IllegalArgumentException("Output directory must have a parent directory: " + outputDirectory);
        }
        Path statisticsFile = statisticsDirectory.resolve(STATS_FILE_NAME);
        Path metaStatisticsFile = statisticsDirectory.resolve(STATS_META_FILE_NAME);
        Path failuresFile = statisticsDirectory.resolve(FAILURES_FILE_NAME);
        writeStatistics(statisticsFile, statistics);
        writeMetaStatistics(metaStatisticsFile, statistics);
        writeFailures(failuresFile, failures);
        FeatJAR.log().message("Evaluation statistics stored at: " + statisticsFile.toAbsolutePath());
        FeatJAR.log().message("Evaluation meta-statistics stored at: " + metaStatisticsFile.toAbsolutePath());
        FeatJAR.log().message("Skipped-model report stored at: " + failuresFile.toAbsolutePath());
        FeatJAR.log().message("Evaluated " + statistics.size() + " model(s); skipped " + failures.size() + " model(s)");
        return List.copyOf(statistics);
    }

    private Statistics evaluateSingleFileWithTimeout(
            FeatJARWrapper featJARWrapper,
            FeatureModelSimplifyerCommand simplifier,
            Path inputFile,
            Path outputFile,
            Path relativeInputPath,
            boolean simplifyCoreAndDeadFeatures,
            boolean simplifyAtomicSets,
            ProgressReporter progress,
            int modelTimeoutSeconds)
            throws IOException {
        if (modelTimeoutSeconds == 0) {
            return evaluateSingleFile(
                    featJARWrapper,
                    simplifier,
                    inputFile,
                    outputFile,
                    relativeInputPath,
                    simplifyCoreAndDeadFeatures,
                    simplifyAtomicSets,
                    progress);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "feature-model-evaluation-worker");
            thread.setDaemon(true);
            return thread;
        });
        Future<Statistics> future = executor.submit(() -> evaluateSingleFile(
                featJARWrapper,
                simplifier,
                inputFile,
                outputFile,
                relativeInputPath,
                simplifyCoreAndDeadFeatures,
                simplifyAtomicSets,
                progress));
        try {
            return future.get(modelTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ModelTimeoutException(modelTimeoutSeconds);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Evaluation was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Model evaluation failed", cause);
        } finally {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    FeatJAR.log().warning("Timed-out model worker did not stop within five seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Statistics evaluateSingleFile(
            FeatJARWrapper featJARWrapper,
            FeatureModelSimplifyerCommand simplifier,
            Path inputFile,
            Path outputFile,
            Path relativeInputPath,
            boolean simplifyCoreAndDeadFeatures,
            boolean simplifyAtomicSets,
            ProgressReporter progress)
            throws IOException {
        progress.attachCurrentThread();
        progress.stage("loading input model");
        IFeatureModel inputModel = loadInputFeatureModel(featJARWrapper, inputFile);
        checkCancellation();
        progress.stage("validating Boolean feature types");
        requireBooleanFeatureTypes(inputModel);
        checkCancellation();
        FeatureModelAnalyzer analyzer = featJARWrapper.featureModelAnalyzer(inputModel);
        progress.stage("checking satisfiability");
        if (!analyzer.isSatisfiable().orElseThrow()) {
            throw new UnsatisfiableFeatureModelException();
        }
        checkCancellation();

        if (simplifyCoreAndDeadFeatures) {
            progress.stage("computing core features");
        }
        Set<String> coreFeatures = simplifyCoreAndDeadFeatures
                ? new LinkedHashSet<>(analyzer.core().orElseThrow())
                : Set.of();
        checkCancellation();
        if (simplifyCoreAndDeadFeatures) {
            progress.stage("computing dead features");
        }
        Set<String> deadFeatures = simplifyCoreAndDeadFeatures
                ? new LinkedHashSet<>(analyzer.dead().orElseThrow())
                : Set.of();
        checkCancellation();
        if (simplifyAtomicSets) {
            progress.stage("computing atomic sets");
        }
        Set<String> atomicSetFeatures = simplifyAtomicSets ? collectAtomicSetFeatures(analyzer) : Set.of();
        checkCancellation();

        progress.stage("simplifying feature model");
        IFeatureModel simplifiedModel = simplifier.reduceFeatureModel(
                inputModel,
                analyzer,
                simplifyCoreAndDeadFeatures,
                simplifyAtomicSets,
                progress::stage,
                progress::projectionProgress);
        progress.stage("storing simplified UVL");
        featJARWrapper.storeFeatureModel(simplifiedModel, outputFile);
        checkCancellation();

        // Reload both files so the statistics describe the persisted UVL models.
        progress.stage("reloading persisted models");
        IFeatureModel reloadedInputModel = loadFeatureModel(featJARWrapper, inputFile);
        IFeatureModel reloadedSimplifiedModel = loadFeatureModel(featJARWrapper, outputFile);
        progress.stage("computing statistics");
        Set<String> removedFeatures = featureNames(reloadedInputModel);
        removedFeatures.removeAll(featureNames(reloadedSimplifiedModel));

        int originalFeatureCount = reloadedInputModel.getNumberOfFeatures();
        int simplifiedFeatureCount = reloadedSimplifiedModel.getNumberOfFeatures();
        int originalConstraintCount = reloadedInputModel.getConstraints().size();
        int simplifiedConstraintCount = reloadedSimplifiedModel.getConstraints().size();

        Statistics statistics = new Statistics(
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
                intersectionSize(removedFeatures, atomicSetFeatures),
                simplifiedFeatureCount < originalFeatureCount && simplifiedConstraintCount < originalConstraintCount);
        progress.complete();
        return statistics;
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Model evaluation was cancelled");
        }
    }

    private static IFeatureModel loadFeatureModel(FeatJARWrapper featJARWrapper, Path path) {
        return featJARWrapper.loadFeatureModel(path).orElseThrow();
    }

    private static void requireBooleanFeatureTypes(IFeatureModel featureModel) {
        List<String> unsupportedFeatures = new ArrayList<>();
        for (IFeature feature : featureModel.getFeatures()) {
            if (feature.getType() != Boolean.class) {
                unsupportedFeatures.add(feature.getName().orElse("<unnamed>")
                        + " ("
                        + feature.getType().getSimpleName()
                        + ")");
            }
        }
        if (!unsupportedFeatures.isEmpty()) {
            throw new UnsupportedFeatureTypesException(
                    "Boolean features are required; unsupported features: " + String.join(", ", unsupportedFeatures));
        }
    }

    private static IFeatureModel loadInputFeatureModel(FeatJARWrapper featJARWrapper, Path path) {
        var result = featJARWrapper.loadFeatureModel(path);
        if (result.isPresent()) {
            return result.get();
        }

        StringBuilder details = new StringBuilder();
        result.getProblems().forEach(problem -> {
            if (details.length() > 0) {
                details.append(System.lineSeparator());
            }
            details.append(problem.getException().toString().strip());
        });
        String message = details.length() > 0 ? details.toString() : "Feature model could not be loaded";
        throw new InputFeatureModelLoadException(message);
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
                            Integer.toString(row.removedAtomicSetFeatures()),
                            Boolean.toString(row.fullReduction())));
        }
        Files.writeString(statisticsFile, csv, StandardCharsets.UTF_8);
    }

    private static void writeMetaStatistics(Path metaStatisticsFile, List<Statistics> statistics) throws IOException {
        StringBuilder csv = new StringBuilder();
        appendCSVRow(csv, List.of("metric", "mean", "standard_deviation", "count"));
        appendAggregateRow(csv, "original_features", statistics, Statistics::originalFeatures);
        appendAggregateRow(csv, "simplified_features", statistics, Statistics::simplifiedFeatures);
        appendAggregateRow(csv, "feature_reduction_ratio", statistics, Statistics::featureReductionRatio);
        appendAggregateRow(csv, "original_constraints", statistics, Statistics::originalConstraints);
        appendAggregateRow(csv, "simplified_constraints", statistics, Statistics::simplifiedConstraints);
        appendAggregateRow(csv, "constraint_reduction_ratio", statistics, Statistics::constraintReductionRatio);
        appendAggregateRow(csv, "removed_core_features", statistics, Statistics::removedCoreFeatures);
        appendAggregateRow(csv, "removed_dead_features", statistics, Statistics::removedDeadFeatures);
        appendAggregateRow(csv, "removed_atomic_set_features", statistics, Statistics::removedAtomicSetFeatures);
        long fullReductionCount =
                statistics.stream().filter(Statistics::fullReduction).count();
        appendCSVRow(csv, List.of("full_reduction", "", "", Long.toString(fullReductionCount)));
        Files.writeString(metaStatisticsFile, csv, StandardCharsets.UTF_8);
    }

    private static void writeFailures(Path failuresFile, List<EvaluationFailure> failures) throws IOException {
        StringBuilder csv = new StringBuilder();
        appendCSVRow(csv, List.of("input_file", "reason", "message"));
        for (EvaluationFailure failure : failures) {
            appendCSVRow(csv, List.of(failure.inputFile(), failure.reason(), failure.message()));
        }
        Files.writeString(failuresFile, csv, StandardCharsets.UTF_8);
    }

    private static void appendAggregateRow(
            StringBuilder csv,
            String metric,
            List<Statistics> statistics,
            ToDoubleFunction<Statistics> valueExtractor) {
        AggregateStatistics aggregate = aggregate(statistics, valueExtractor);
        appendCSVRow(
                csv, List.of(metric, formatRatio(aggregate.mean()), formatRatio(aggregate.standardDeviation()), ""));
    }

    private static AggregateStatistics aggregate(
            List<Statistics> statistics, ToDoubleFunction<Statistics> valueExtractor) {
        if (statistics.isEmpty()) {
            return new AggregateStatistics(0.0, 0.0);
        }

        double mean = statistics.stream().mapToDouble(valueExtractor).average().orElseThrow();
        double variance = statistics.stream()
                .mapToDouble(valueExtractor)
                .map(value -> {
                    double difference = value - mean;
                    return difference * difference;
                })
                .average()
                .orElseThrow();
        return new AggregateStatistics(mean, Math.sqrt(variance));
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

    private static String formatElapsed(long elapsedNanos) {
        long totalSeconds = TimeUnit.NANOSECONDS.toSeconds(Math.max(0, elapsedNanos));
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
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
            throw new IllegalArgumentException(
                    "Output directory must not be inside the input directory: " + outputDirectory);
        }
    }
}
