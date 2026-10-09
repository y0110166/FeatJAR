/*
 * Copyright (C) 2026 FeatJAR-Development-Team
 *
 * This file is part of FeatJAR-feature-model-assistance.
 *
 * feature-model-assistance is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License,
 * or (at your option) any later version.
 *
 * feature-model-assistance is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with feature-model-assistance. If not, see <https://www.gnu.org/licenses/>.
 *
 * See <https://github.com/FeatureIDE/FeatJAR-feature-model-assistance> for further information.
 */
package de.featjar.featureide.cli;

import de.featjar.base.FeatJAR;
import de.featjar.feature.model.IFeature;
import de.featjar.feature.model.IFeatureModel;
import de.featjar.featureide.FeatJARWrapper;
import de.featjar.featureide.FeatureModelAnalyzer;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/*
Type 2 AI: This file is the result of automated code generation.

 * @author Knut Köhnlein
 */

/**
 * Writes the core and dead feature names of every bundled UVL model with fewer
 * than 1,000 features to a CSV file. Run {@link #main(String[])} directly; no
 * command-line arguments are required.
 */
public class coreFeatureNameOutput {

    private static final int FEATURE_LIMIT = 1_000;
    private static final String INPUT_DIRECTORY_PROPERTY = "featjar.coreFeatureNameOutput.inputDirectory";
    private static final String OUTPUT_FILE_PROPERTY = "featjar.coreFeatureNameOutput.outputFile";
    private static final List<String> CSV_HEADER = List.of(
            "input_file",
            "feature_count",
            "core_feature_count",
            "core_feature_names",
            "dead_feature_count",
            "dead_feature_names");

    private final Path inputDirectory;
    private final Path outputFile;
    private final FeatJARWrapper featJARWrapper = new FeatJARWrapper();

    private coreFeatureNameOutput(Path moduleDirectory) {
        String configuredInputDirectory = System.getProperty(INPUT_DIRECTORY_PROPERTY, "");
        inputDirectory = configuredInputDirectory.isBlank()
                ? moduleDirectory.resolve("src/main/resources/uvlhub_bulk_2026_08_18")
                : Path.of(configuredInputDirectory).toAbsolutePath().normalize();

        String configuredOutputFile = System.getProperty(OUTPUT_FILE_PROPERTY, "");
        outputFile = configuredOutputFile.isBlank()
                ? moduleDirectory.resolve(
                        "src/main/resources/output/uvlhub_bulk_2026_08_18/core-dead-feature-names-under-1000.csv")
                : Path.of(configuredOutputFile).toAbsolutePath().normalize();
    }

    public static void main(String[] ignoredArguments) throws IOException {
        new coreFeatureNameOutput(findModuleDirectory()).run();
    }

    private void run() throws IOException {
        if (!Files.isDirectory(inputDirectory)) {
            throw new IllegalArgumentException("Input directory does not exist: " + inputDirectory);
        }
        if (Files.isDirectory(outputFile)) {
            throw new IllegalArgumentException("Output path must be a CSV file: " + outputFile);
        }

        Path outputParent = outputFile.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }

        List<Path> inputFiles = findInputFiles();
        int exportedModels = 0;
        int modelsAtOrAboveLimit = 0;
        int failedModels = 0;

        try (BufferedWriter writer = Files.newBufferedWriter(
                outputFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            appendCSVRow(writer, CSV_HEADER);
            writer.flush();

            for (int index = 0; index < inputFiles.size(); index++) {
                Path inputFile = inputFiles.get(index);
                Path relativeInputPath = inputDirectory.relativize(inputFile);
                String portableInputPath = toPortablePath(relativeInputPath);

                IFeatureModel featureModel;
                try {
                    featureModel = loadFeatureModel(inputFile);
                } catch (RuntimeException exception) {
                    failedModels++;
                    logFailure(portableInputPath, exception);
                    continue;
                }

                int featureCount = featureModel.getNumberOfFeatures();
                if (featureCount >= FEATURE_LIMIT) {
                    modelsAtOrAboveLimit++;
                    continue;
                }

                FeatJAR.log()
                        .message("Analyzing [" + (index + 1) + "/" + inputFiles.size() + "] "
                                + inputFile.toAbsolutePath());
                try {
                    requireBooleanFeatureTypes(featureModel);
                    FeatureModelAnalyzer analyzer = featJARWrapper.featureModelAnalyzer(featureModel);
                    if (!analyzer.isSatisfiable().orElseThrow()) {
                        throw new IllegalArgumentException("Feature model is unsatisfiable");
                    }

                    List<String> coreFeatures = coreFeatureNamesWithoutRoots(
                            featureModel, analyzer.core().orElseThrow());
                    List<String> deadFeatures = sortedDistinct(analyzer.dead().orElseThrow());
                    appendCSVRow(
                            writer,
                            List.of(
                                    portableInputPath,
                                    Integer.toString(featureCount),
                                    Integer.toString(coreFeatures.size()),
                                    String.join("; ", coreFeatures),
                                    Integer.toString(deadFeatures.size()),
                                    String.join("; ", deadFeatures)));
                    writer.flush();
                    exportedModels++;
                } catch (RuntimeException exception) {
                    failedModels++;
                    logFailure(portableInputPath, exception);
                }
            }
        }

        FeatJAR.log().message("Core/dead feature report stored at: " + outputFile.toAbsolutePath());
        FeatJAR.log()
                .message("Exported " + exportedModels + " model(s); skipped " + modelsAtOrAboveLimit
                        + " model(s) with at least " + FEATURE_LIMIT + " features and " + failedModels
                        + " model(s) that could not be analyzed");
    }

    private List<Path> findInputFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(inputDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(coreFeatureNameOutput::isUVLFile)
                    .sorted(Comparator.comparing(path -> toPortablePath(inputDirectory.relativize(path))))
                    .toList();
        }
    }

    private IFeatureModel loadFeatureModel(Path inputFile) {
        var result = featJARWrapper.loadFeatureModel(inputFile);
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

    private static List<String> sortedDistinct(List<String> featureNames) {
        return featureNames.stream().distinct().sorted().toList();
    }

    private static List<String> coreFeatureNamesWithoutRoots(
            IFeatureModel featureModel, List<String> coreFeatureNames) {
        Set<String> rootFeatureNames = new HashSet<>();
        for (IFeature rootFeature : featureModel.getRootFeatures()) {
            rootFeature.getName().ifPresent(rootFeatureNames::add);
        }
        return coreFeatureNames.stream()
                .filter(featureName -> !rootFeatureNames.contains(featureName))
                .distinct()
                .sorted()
                .toList();
    }

    private static void appendCSVRow(BufferedWriter writer, List<String> fields) throws IOException {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(escapeCSV(fields.get(index)));
        }
        writer.newLine();
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

    private static boolean isUVLFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".uvl");
    }

    private static String toPortablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void logFailure(String inputFile, RuntimeException exception) {
        String exceptionMessage = exception.getMessage();
        String message = exceptionMessage == null || exceptionMessage.isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + exceptionMessage;
        FeatJAR.log().message("[Skipped] " + inputFile + " could not be analyzed: " + message);
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
}
