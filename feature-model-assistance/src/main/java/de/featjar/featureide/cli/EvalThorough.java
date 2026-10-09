/*
 * Copyright (C) 2026 FeatJAR-Development-Team
 *
 * This file is part of FeatJAR-feature-model-assistance.
 *
 * FeatJAR-feature-model-assistance is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License,
 * or (at your option) any later version.
 *
 * FeatJAR-feature-model-assistance is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FeatJAR-feature-model-assistance. If not, see <https://www.gnu.org/licenses/>.
 */
package de.featjar.featureide.cli;

import de.featjar.base.FeatJAR;
import de.featjar.base.data.Pair;
import de.featjar.feature.model.IFeature;
import de.featjar.feature.model.IFeatureModel;
import de.featjar.featureide.FeatJARWrapper;
import de.featjar.featureide.FeatureModelAnalyzer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
/*
Type 2 AI: This file is the result of automated code generation.

 * @author Knut Köhnlein
 */

/**
 * Standalone evaluation of every UVL model except the five known automotive
 * timeout models. Each individual processing step has its own timeout, and all
 * step events are appended to one CSV in execution order.
 */
public class EvalThorough {

    private static final long STEP_TIMEOUT_MILLIS =
            positiveLongProperty("featjar.thoroughEvaluation.stepTimeoutMillis", TimeUnit.MINUTES.toMillis(5));
    private static final long WATCHDOG_POLL_MILLIS = 100;
    private static final int MAX_MODELS = nonNegativeIntegerProperty("featjar.thoroughEvaluation.maxModels", 0);
    private static final Set<String> EXCLUDED_MODELS = Set.of(
            "dataset_8/uvl/automotive2_4.uvl",
            "dataset_9/uvl/automotive02_01.uvl",
            "dataset_9/uvl/automotive02_02.uvl",
            "dataset_9/uvl/automotive02_03.uvl",
            "dataset_9/uvl/automotive02_04.uvl");

    private final Path inputDirectory;
    private final Path outputDirectory;
    private final Path reducedModelDirectory;
    private final EventRecorder events;
    private final FeatJARWrapper featJARWrapper = new FeatJARWrapper();
    private final FeatureModelSimplifyerCommand simplifier = new FeatureModelSimplifyerCommand();

    private enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        TIMED_OUT,
        FAILED,
        MISSING
    }

    private enum Stage {
        INPUT_MODEL_LOADING("input_model_loading"),
        BOOLEAN_FEATURE_TYPE_VALIDATION("boolean_feature_type_validation"),
        INITIAL_SATISFIABILITY_CHECK("initial_satisfiability_check"),
        CORE_FEATURE_COMPUTATION("core_feature_computation"),
        DEAD_FEATURE_COMPUTATION("dead_feature_computation"),
        ATOMIC_SET_COMPUTATION("atomic_set_computation"),
        REDUCTION_SATISFIABILITY_CHECK("reduction_satisfiability_check"),
        REDUCTION_ORIGINAL_CNF_CONSTRUCTION("reduction_original_cnf_construction"),
        REDUCTION_REMOVAL_PLAN_COMPUTATION("reduction_removal_plan_computation"),
        REDUCTION_VARIABLE_PROJECTION("reduction_variable_projection"),
        REDUCTION_FEATURE_TREE_REBUILD("reduction_feature_tree_rebuild"),
        REDUCTION_STRUCTURAL_MODEL_VALIDATION("reduction_structural_model_validation"),
        REDUCTION_PROJECTED_CONSTRAINT_ADDITION("reduction_projected_constraint_addition"),
        REDUCTION_REDUCED_MODEL_VALIDATION("reduction_reduced_model_validation"),
        SIMPLIFIED_MODEL_STORAGE("simplified_model_storage"),
        REDUCED_MODEL_RELOADING("reduced_model_reloading"),
        STATISTICS_COMPUTATION("statistics_computation");

        private final String csvName;

        Stage(String csvName) {
            this.csvName = csvName;
        }
    }

    private record ModelResult(
            int simplifiedFeatures,
            int simplifiedConstraints,
            double featureReductionRatio,
            double constraintReductionRatio,
            int removedCoreFeatures,
            int removedDeadFeatures,
            int removedAtomicSetFeatures,
            boolean fullReduction) {}

    private record EventData(
            String inputFile,
            String outputFile,
            long fileSizeBytes,
            Status status,
            String event,
            String step,
            long stepElapsedNanos,
            long evaluationElapsedNanos,
            long reductionElapsedNanos,
            int originalFeatures,
            int originalConstraints,
            int simplifiedFeatures,
            int simplifiedConstraints,
            double featureReductionRatio,
            double constraintReductionRatio,
            int removedCoreFeatures,
            int removedDeadFeatures,
            int removedAtomicSetFeatures,
            Boolean fullReduction,
            long projectedVariablesCompleted,
            long projectedVariablesTotal,
            String message) {}

    private record TimelineEvent(long sequence, String timestampUtc, EventData data) {}

    private static final class EventRecorder {

        private static final List<String> HEADER = List.of(
                "sequence",
                "timestamp_utc",
                "input_file",
                "output_file",
                "file_size_bytes",
                "model_status",
                "event",
                "step",
                "step_elapsed_seconds",
                "evaluation_elapsed_seconds",
                "reduction_elapsed_seconds",
                "step_timeout_seconds",
                "original_features",
                "original_constraints",
                "simplified_features",
                "simplified_constraints",
                "feature_reduction_ratio",
                "constraint_reduction_ratio",
                "removed_core_features",
                "removed_dead_features",
                "removed_atomic_set_features",
                "full_reduction",
                "projection_variables_completed",
                "projection_variables_total",
                "message");

        private final Path statisticsFile;
        private long nextSequence = 1;

        private EventRecorder(Path statisticsFile) {
            this.statisticsFile = statisticsFile;
        }

        private synchronized void reset() {
            nextSequence = 1;
            try {
                Files.createDirectories(statisticsFile.getParent());
                StringBuilder csv = new StringBuilder();
                appendCSVRow(csv, HEADER);
                Files.writeString(
                        statisticsFile,
                        csv,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not initialize thorough evaluation statistics", e);
            }
        }

        private synchronized void record(EventData data) {
            TimelineEvent event =
                    new TimelineEvent(nextSequence++, Instant.now().toString(), data);
            StringBuilder csv = new StringBuilder();
            appendCSVRow(csv, csvFields(event));
            try {
                Files.writeString(
                        statisticsFile,
                        csv,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not append thorough evaluation event", e);
            }
        }

        private static List<String> csvFields(TimelineEvent event) {
            EventData data = event.data();
            return List.of(
                    Long.toString(event.sequence()),
                    event.timestampUtc(),
                    data.inputFile(),
                    data.outputFile(),
                    Long.toString(data.fileSizeBytes()),
                    data.status().name().toLowerCase(Locale.ROOT),
                    data.event(),
                    data.step(),
                    formatOptionalNanos(data.stepElapsedNanos()),
                    formatSeconds(data.evaluationElapsedNanos()),
                    formatOptionalNanos(data.reductionElapsedNanos()),
                    formatSeconds(TimeUnit.MILLISECONDS.toNanos(STEP_TIMEOUT_MILLIS)),
                    formatInteger(data.originalFeatures()),
                    formatInteger(data.originalConstraints()),
                    formatInteger(data.simplifiedFeatures()),
                    formatInteger(data.simplifiedConstraints()),
                    formatOptionalDouble(data.featureReductionRatio()),
                    formatOptionalDouble(data.constraintReductionRatio()),
                    formatInteger(data.removedCoreFeatures()),
                    formatInteger(data.removedDeadFeatures()),
                    formatInteger(data.removedAtomicSetFeatures()),
                    data.fullReduction() == null ? "" : Boolean.toString(data.fullReduction()),
                    formatLong(data.projectedVariablesCompleted()),
                    formatLong(data.projectedVariablesTotal()),
                    data.message());
        }
    }

    private static final class DiagnosticRun {

        private final String inputFile;
        private final String outputFile;
        private final long fileSizeBytes;
        private final EventRecorder events;
        private Status status = Status.PENDING;
        private Stage activeStage;
        private Stage terminalStage;
        private long activeStageStartedNanos;
        private long evaluationStartedNanos;
        private long reductionStartedNanos;
        private long reductionElapsedNanos = -1;
        private int originalFeatures = -1;
        private int originalConstraints = -1;
        private int simplifiedFeatures = -1;
        private int simplifiedConstraints = -1;
        private double featureReductionRatio = Double.NaN;
        private double constraintReductionRatio = Double.NaN;
        private int removedCoreFeatures = -1;
        private int removedDeadFeatures = -1;
        private int removedAtomicSetFeatures = -1;
        private Boolean fullReduction;
        private long projectedVariablesCompleted = -1;
        private long projectedVariablesTotal = -1;

        private DiagnosticRun(String inputFile, String outputFile, long fileSizeBytes, EventRecorder events) {
            this.inputFile = inputFile;
            this.outputFile = outputFile;
            this.fileSizeBytes = fileSizeBytes;
            this.events = events;
        }

        private synchronized void start() {
            status = Status.RUNNING;
            evaluationStartedNanos = System.nanoTime();
            record("model_started", "", -1, "", evaluationStartedNanos);
        }

        private synchronized void begin(Stage stage) {
            if (isTerminal()) {
                return;
            }
            long now = System.nanoTime();
            finishActiveStage(now);
            activeStage = stage;
            record("step_started", stage.csvName, -1, "", now);
            activeStageStartedNanos = System.nanoTime();
        }

        private synchronized void startReduction() {
            if (isTerminal()) {
                return;
            }
            long now = System.nanoTime();
            finishActiveStage(now);
            record("reduction_started", "reduction", -1, "", now);
            reductionStartedNanos = System.nanoTime();
        }

        private synchronized void finishReduction() {
            if (isTerminal() || reductionStartedNanos <= 0) {
                return;
            }
            long now = System.nanoTime();
            finishActiveStage(now);
            reductionElapsedNanos = now - reductionStartedNanos;
            reductionStartedNanos = 0;
            record("reduction_completed", "reduction", reductionElapsedNanos, "", now);
        }

        private synchronized void setOriginalCounts(IFeatureModel model) {
            if (!isTerminal()) {
                originalFeatures = model.getNumberOfFeatures();
                originalConstraints = model.getConstraints().size();
            }
        }

        private synchronized void projectionProgress(long completed, long total) {
            if (!isTerminal()) {
                projectedVariablesCompleted = completed;
                projectedVariablesTotal = total;
            }
        }

        private synchronized long remainingStepNanos() {
            if (isTerminal() || activeStage == null) {
                return TimeUnit.MILLISECONDS.toNanos(STEP_TIMEOUT_MILLIS);
            }
            return TimeUnit.MILLISECONDS.toNanos(STEP_TIMEOUT_MILLIS) - (System.nanoTime() - activeStageStartedNanos);
        }

        private synchronized void complete(ModelResult result) {
            if (isTerminal()) {
                return;
            }
            long now = System.nanoTime();
            finishActiveStage(now);
            simplifiedFeatures = result.simplifiedFeatures();
            simplifiedConstraints = result.simplifiedConstraints();
            featureReductionRatio = result.featureReductionRatio();
            constraintReductionRatio = result.constraintReductionRatio();
            removedCoreFeatures = result.removedCoreFeatures();
            removedDeadFeatures = result.removedDeadFeatures();
            removedAtomicSetFeatures = result.removedAtomicSetFeatures();
            fullReduction = result.fullReduction();
            status = Status.COMPLETED;
            record("model_completed", "", -1, "", now);
        }

        private synchronized boolean timeOutIfExpired() {
            if (isTerminal() || activeStage == null) {
                return false;
            }
            long now = System.nanoTime();
            long stepElapsedNanos = now - activeStageStartedNanos;
            if (stepElapsedNanos < TimeUnit.MILLISECONDS.toNanos(STEP_TIMEOUT_MILLIS)) {
                return false;
            }
            Stage timedOutStage = activeStage;
            terminalStage = timedOutStage;
            activeStage = null;
            status = Status.TIMED_OUT;
            record(
                    "step_timed_out",
                    stageName(timedOutStage),
                    stepElapsedNanos,
                    "Step exceeded the timeout of " + formatMilliseconds(STEP_TIMEOUT_MILLIS),
                    now);
            return true;
        }

        private synchronized void fail(Throwable throwable) {
            if (isTerminal()) {
                return;
            }
            long now = System.nanoTime();
            Stage failedStage = activeStage;
            long stepElapsedNanos = failedStage == null ? -1 : now - activeStageStartedNanos;
            terminalStage = failedStage;
            activeStage = null;
            status = Status.FAILED;
            String exceptionMessage = throwable.getMessage();
            String message = throwable.getClass().getSimpleName()
                    + (exceptionMessage == null || exceptionMessage.isBlank() ? "" : ": " + exceptionMessage);
            record("model_failed", stageName(failedStage), stepElapsedNanos, message, now);
        }

        private synchronized void markMissing() {
            if (isTerminal()) {
                return;
            }
            long now = System.nanoTime();
            status = Status.MISSING;
            record("model_missing", "", -1, "Input model does not exist", now);
        }

        private synchronized String progressMessage() {
            Stage currentStage = activeStage == null ? terminalStage : activeStage;
            return inputFile
                    + " | status "
                    + status.name().toLowerCase(Locale.ROOT)
                    + " | step "
                    + stageName(currentStage);
        }

        private void finishActiveStage(long now) {
            if (activeStage == null) {
                return;
            }
            long elapsedNanos = now - activeStageStartedNanos;
            record("step_completed", activeStage.csvName, elapsedNanos, "", now);
            activeStage = null;
        }

        private void record(String event, String step, long stepElapsedNanos, String message, long now) {
            events.record(new EventData(
                    inputFile,
                    outputFile,
                    fileSizeBytes,
                    status,
                    event,
                    step,
                    stepElapsedNanos,
                    evaluationElapsedNanos(now),
                    reductionElapsedNanos(now),
                    originalFeatures,
                    originalConstraints,
                    simplifiedFeatures,
                    simplifiedConstraints,
                    featureReductionRatio,
                    constraintReductionRatio,
                    removedCoreFeatures,
                    removedDeadFeatures,
                    removedAtomicSetFeatures,
                    fullReduction,
                    projectedVariablesCompleted,
                    projectedVariablesTotal,
                    message));
        }

        private long evaluationElapsedNanos(long now) {
            return evaluationStartedNanos == 0 ? 0 : now - evaluationStartedNanos;
        }

        private long reductionElapsedNanos(long now) {
            return reductionStartedNanos > 0 ? now - reductionStartedNanos : reductionElapsedNanos;
        }

        private boolean isTerminal() {
            return status == Status.COMPLETED
                    || status == Status.TIMED_OUT
                    || status == Status.FAILED
                    || status == Status.MISSING;
        }
    }

    private EvalThorough(Path moduleDirectory) {
        String configuredInputDirectory = System.getProperty("featjar.thoroughEvaluation.inputDirectory", "");
        inputDirectory = configuredInputDirectory.isBlank()
                ? moduleDirectory.resolve("src/main/resources/uvlhub_bulk_2026_08_18")
                : Path.of(configuredInputDirectory).toAbsolutePath().normalize();

        String configuredOutputDirectory = System.getProperty("featjar.thoroughEvaluation.outputDirectory", "");
        outputDirectory = configuredOutputDirectory.isBlank()
                ? moduleDirectory.resolve("src/main/resources/output/uvlhub_bulk_2026_08_18")
                : Path.of(configuredOutputDirectory).toAbsolutePath().normalize();
        reducedModelDirectory = outputDirectory.resolve("OUTPUT_THOROUGH");
        events = new EventRecorder(outputDirectory.resolve("stats-thorough.csv"));
    }

    public static void main(String[] ignoredArguments) throws IOException {
        new EvalThorough(findModuleDirectory()).run();
    }

    private void run() throws IOException {
        if (!Files.isDirectory(inputDirectory)) {
            throw new IllegalArgumentException("Input directory does not exist: " + inputDirectory);
        }
        Files.createDirectories(reducedModelDirectory);
        List<Path> inputFiles = findInputFiles();
        events.reset();
        FeatJAR.log()
                .message("Thorough evaluation will process " + inputFiles.size() + " model(s); excluded "
                        + EXCLUDED_MODELS.size() + " known automotive timeout model(s)");

        for (int index = 0; index < inputFiles.size(); index++) {
            Path inputFile = inputFiles.get(index);
            Path relativeInputPath = inputDirectory.relativize(inputFile);
            Path outputFile = reducedModelDirectory.resolve(relativeInputPath);
            Path outputParent = outputFile.getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }
            DiagnosticRun run = new DiagnosticRun(
                    toPortablePath(relativeInputPath),
                    toPortablePath(outputDirectory.relativize(outputFile)),
                    Files.size(inputFile),
                    events);
            FeatJAR.log()
                    .message(
                            "Evaluating [" + (index + 1) + "/" + inputFiles.size() + "] " + inputFile.toAbsolutePath());
            evaluateWithStepTimeout(run, inputFile, outputFile);
        }

        FeatJAR.log()
                .message("Thorough evaluation statistics stored at: "
                        + outputDirectory.resolve("stats-thorough.csv").toAbsolutePath());
    }

    private List<Path> findInputFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(inputDirectory)) {
            Stream<Path> inputFiles = paths.filter(Files::isRegularFile)
                    .filter(EvalThorough::isUVLFile)
                    .filter(path -> !isExcluded(inputDirectory.relativize(path)))
                    .sorted(Comparator.comparing(path -> toPortablePath(inputDirectory.relativize(path))));
            if (MAX_MODELS > 0) {
                inputFiles = inputFiles.limit(MAX_MODELS);
            }
            return inputFiles.toList();
        }
    }

    private void evaluateWithStepTimeout(DiagnosticRun run, Path inputFile, Path outputFile) {
        run.start();
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "thorough-evaluation-worker");
            thread.setDaemon(true);
            return thread;
        });
        Future<ModelResult> future = workerExecutor.submit(() -> evaluateModel(run, inputFile, outputFile));
        try {
            while (true) {
                if (future.isDone()) {
                    run.complete(future.get());
                    return;
                }
                long remainingStepNanos = run.remainingStepNanos();
                if (remainingStepNanos <= 0) {
                    if (future.isDone()) {
                        continue;
                    }
                    if (run.timeOutIfExpired()) {
                        future.cancel(true);
                        return;
                    }
                    continue;
                }
                long waitNanos = Math.min(remainingStepNanos, TimeUnit.MILLISECONDS.toNanos(WATCHDOG_POLL_MILLIS));
                try {
                    run.complete(future.get(waitNanos, TimeUnit.NANOSECONDS));
                    return;
                } catch (TimeoutException ignored) {
                    // Re-read the active step because the worker may have advanced.
                }
            }
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            run.fail(e);
        } catch (ExecutionException e) {
            run.fail(e.getCause());
        } finally {
            workerExecutor.shutdownNow();
            awaitTermination(workerExecutor);
            FeatJAR.log().message("[Thorough evaluation] " + run.progressMessage());
        }
    }

    private ModelResult evaluateModel(DiagnosticRun run, Path inputFile, Path outputFile) throws IOException {
        begin(run, Stage.INPUT_MODEL_LOADING);
        IFeatureModel inputModel = loadFeatureModel(featJARWrapper, inputFile);
        run.setOriginalCounts(inputModel);
        checkCancellation();

        begin(run, Stage.BOOLEAN_FEATURE_TYPE_VALIDATION);
        requireBooleanFeatureTypes(inputModel);
        checkCancellation();

        FeatureModelAnalyzer analyzer = featJARWrapper.featureModelAnalyzer(inputModel);
        begin(run, Stage.INITIAL_SATISFIABILITY_CHECK);
        if (!analyzer.isSatisfiable().orElseThrow()) {
            throw new IllegalArgumentException("Feature model is unsatisfiable");
        }
        checkCancellation();

        begin(run, Stage.CORE_FEATURE_COMPUTATION);
        Set<String> coreFeatures = new LinkedHashSet<>(analyzer.core().orElseThrow());
        checkCancellation();

        begin(run, Stage.DEAD_FEATURE_COMPUTATION);
        Set<String> deadFeatures = new LinkedHashSet<>(analyzer.dead().orElseThrow());
        checkCancellation();

        begin(run, Stage.ATOMIC_SET_COMPUTATION);
        Set<String> atomicSetFeatures = collectAtomicSetFeatures(analyzer);
        checkCancellation();

        run.startReduction();
        IFeatureModel simplifiedModel = simplifier.reduceFeatureModel(
                inputModel,
                analyzer,
                true,
                true,
                ignoredProgressMessage -> {},
                run::projectionProgress,
                phase -> begin(run, toStage(phase)));
        run.finishReduction();
        checkCancellation();

        begin(run, Stage.SIMPLIFIED_MODEL_STORAGE);
        featJARWrapper.storeFeatureModel(simplifiedModel, outputFile);
        checkCancellation();

        begin(run, Stage.REDUCED_MODEL_RELOADING);
        IFeatureModel reloadedSimplifiedModel = loadFeatureModel(featJARWrapper, outputFile);
        checkCancellation();

        begin(run, Stage.STATISTICS_COMPUTATION);
        Set<String> removedFeatures = featureNames(inputModel);
        removedFeatures.removeAll(featureNames(reloadedSimplifiedModel));
        int originalFeatureCount = inputModel.getNumberOfFeatures();
        int originalConstraintCount = inputModel.getConstraints().size();
        int simplifiedFeatureCount = reloadedSimplifiedModel.getNumberOfFeatures();
        int simplifiedConstraintCount = reloadedSimplifiedModel.getConstraints().size();
        return new ModelResult(
                simplifiedFeatureCount,
                simplifiedConstraintCount,
                reductionRatio(originalFeatureCount, simplifiedFeatureCount),
                reductionRatio(originalConstraintCount, simplifiedConstraintCount),
                intersectionSize(removedFeatures, coreFeatures),
                intersectionSize(removedFeatures, deadFeatures),
                intersectionSize(removedFeatures, atomicSetFeatures),
                simplifiedFeatureCount < originalFeatureCount && simplifiedConstraintCount < originalConstraintCount);
    }

    private void begin(DiagnosticRun run, Stage stage) {
        run.begin(stage);
        FeatJAR.log().message("[Thorough evaluation] " + run.progressMessage());
    }

    private static Stage toStage(FeatureModelSimplifyerCommand.ReductionPhase phase) {
        return switch (phase) {
            case SATISFIABILITY_CHECK -> Stage.REDUCTION_SATISFIABILITY_CHECK;
            case ORIGINAL_CNF_CONSTRUCTION -> Stage.REDUCTION_ORIGINAL_CNF_CONSTRUCTION;
            case REMOVAL_PLAN_COMPUTATION -> Stage.REDUCTION_REMOVAL_PLAN_COMPUTATION;
            case VARIABLE_PROJECTION -> Stage.REDUCTION_VARIABLE_PROJECTION;
            case FEATURE_TREE_REBUILD -> Stage.REDUCTION_FEATURE_TREE_REBUILD;
            case STRUCTURAL_MODEL_VALIDATION -> Stage.REDUCTION_STRUCTURAL_MODEL_VALIDATION;
            case PROJECTED_CONSTRAINT_ADDITION -> Stage.REDUCTION_PROJECTED_CONSTRAINT_ADDITION;
            case REDUCED_MODEL_VALIDATION -> Stage.REDUCTION_REDUCED_MODEL_VALIDATION;
        };
    }

    private static IFeatureModel loadFeatureModel(FeatJARWrapper featJARWrapper, Path path) {
        var result = featJARWrapper.loadFeatureModel(path);
        if (result.isPresent()) {
            return result.get();
        }
        StringBuilder message = new StringBuilder("Feature model could not be loaded");
        result.getProblems().forEach(problem -> message.append(System.lineSeparator())
                .append(problem.getException().toString().strip()));
        throw new IllegalArgumentException(message.toString());
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
            throw new IllegalArgumentException(
                    "Boolean features are required; unsupported features: " + String.join(", ", unsupportedFeatures));
        }
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

    private static boolean isUVLFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".uvl");
    }

    private static boolean isExcluded(Path relativePath) {
        return EXCLUDED_MODELS.contains(toPortablePath(relativePath).toLowerCase(Locale.ROOT));
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Thorough model evaluation was cancelled");
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                FeatJAR.log().warning("Thorough evaluation worker did not stop within five seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path findModuleDirectory() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path moduleCandidate = current.resolve("feature-model-assistance");
            if (Files.isDirectory(moduleCandidate.resolve("src/main/resources"))) {
                return moduleCandidate;
            }
            if (Files.isDirectory(current.resolve("src/main/resources"))
                    && current.getFileName() != null
                    && current.getFileName().toString().equals("feature-model-assistance")) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Could not locate the feature-model-assistance module from the working directory");
    }

    private static void appendCSVRow(StringBuilder csv, List<String> fields) {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append(escapeCSV(fields.get(index)));
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

    private static String stageName(Stage stage) {
        return stage == null ? "" : stage.csvName;
    }

    private static String formatInteger(int value) {
        return value < 0 ? "" : Integer.toString(value);
    }

    private static String formatLong(long value) {
        return value < 0 ? "" : Long.toString(value);
    }

    private static String formatOptionalDouble(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String formatOptionalNanos(long elapsedNanos) {
        return elapsedNanos < 0 ? "" : formatSeconds(elapsedNanos);
    }

    private static String formatSeconds(long elapsedNanos) {
        return String.format(Locale.ROOT, "%.6f", elapsedNanos / 1_000_000_000.0);
    }

    private static String formatMilliseconds(long milliseconds) {
        return String.format(Locale.ROOT, "%.3f seconds", milliseconds / 1000.0);
    }

    private static String toPortablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static long positiveLongProperty(String propertyName, long defaultValue) {
        String configuredValue = System.getProperty(propertyName, Long.toString(defaultValue));
        try {
            long parsedValue = Long.parseLong(configuredValue);
            if (parsedValue > 0) {
                return parsedValue;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the descriptive configuration error below.
        }
        throw new IllegalArgumentException(propertyName + " must be a positive integer: " + configuredValue);
    }

    private static int nonNegativeIntegerProperty(String propertyName, int defaultValue) {
        String configuredValue = System.getProperty(propertyName, Integer.toString(defaultValue));
        try {
            int parsedValue = Integer.parseInt(configuredValue);
            if (parsedValue >= 0) {
                return parsedValue;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the descriptive configuration error below.
        }
        throw new IllegalArgumentException(propertyName + " must be zero or greater: " + configuredValue);
    }
}
