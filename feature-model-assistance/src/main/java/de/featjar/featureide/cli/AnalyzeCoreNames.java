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
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
Type 2 AI: This file is the result of automated code generation.

 * @author Knut Köhnlein
 */

/**
 * Creates independently ranked lists of the most common core and dead feature
 * names from the CSV written by {@link coreFeatureNameOutput}. Run
 * {@link #main(String[])} directly; no command-line arguments are required.
 */
public class AnalyzeCoreNames {

    private static final String INPUT_FILE_PROPERTY = "featjar.analyzeCoreNames.inputFile";
    private static final String OUTPUT_FILE_PROPERTY = "featjar.analyzeCoreNames.outputFile";
    private static final String FEATURE_NAME_SEPARATOR = "; ";
    private static final List<String> CSV_HEADER = List.of(
            "rank",
            "core_feature_name",
            "core_feature_count",
            "core_feature_model_count",
            "dead_feature_name",
            "dead_feature_count",
            "dead_feature_model_count");
    private static final Comparator<NameFrequency> FREQUENCY_ORDER = Comparator.comparingLong(
                    NameFrequency::featureCount)
            .reversed()
            .thenComparing(
                    Comparator.comparingInt(NameFrequency::featureModelCount).reversed())
            .thenComparing(NameFrequency::featureName);

    private static final class NameStatistics {

        private long featureCount;
        private final Set<String> featureModels = new HashSet<>();

        private void addOccurrence(String featureModel) {
            featureCount++;
            featureModels.add(featureModel);
        }

        private NameFrequency toFrequency(String featureName) {
            return new NameFrequency(featureName, featureCount, featureModels.size());
        }
    }

    private record NameFrequency(String featureName, long featureCount, int featureModelCount) {}

    private record ColumnIndices(
            int inputFile, int coreFeatureCount, int coreFeatureNames, int deadFeatureCount, int deadFeatureNames) {}

    private record AnalysisResult(
            List<NameFrequency> coreFrequencies,
            List<NameFrequency> deadFrequencies,
            int csvRecordCount,
            int featureModelCount) {}

    private final Path inputFile;
    private final Path outputFile;

    private AnalyzeCoreNames(Path moduleDirectory) {
        Path defaultDirectory = moduleDirectory.resolve("src/main/resources/output/uvlhub_bulk_2026_08_18");

        String configuredInputFile = System.getProperty(INPUT_FILE_PROPERTY, "");
        inputFile = configuredInputFile.isBlank()
                ? defaultDirectory.resolve("core-dead-feature-names-under-1000.csv")
                : Path.of(configuredInputFile).toAbsolutePath().normalize();

        String configuredOutputFile = System.getProperty(OUTPUT_FILE_PROPERTY, "");
        outputFile = configuredOutputFile.isBlank()
                ? defaultDirectory.resolve("core-dead-feature-name-frequencies-under-1000.csv")
                : Path.of(configuredOutputFile).toAbsolutePath().normalize();
    }

    public static void main(String[] ignoredArguments) throws IOException {
        new AnalyzeCoreNames(findModuleDirectory()).run();
    }

    private void run() throws IOException {
        if (!Files.isRegularFile(inputFile)) {
            throw new IllegalArgumentException("Input CSV file does not exist: " + inputFile);
        }
        if (Files.isDirectory(outputFile)) {
            throw new IllegalArgumentException("Output path must be a CSV file: " + outputFile);
        }
        if (inputFile
                .toAbsolutePath()
                .normalize()
                .equals(outputFile.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Input and output CSV files must be different: " + inputFile);
        }

        AnalysisResult result = analyze(readCSV(inputFile));
        writeFrequencies(result.coreFrequencies(), result.deadFrequencies());

        FeatJAR.log().message("Feature-name frequency report stored at: " + outputFile.toAbsolutePath());
        FeatJAR.log()
                .message("Analyzed " + result.csvRecordCount() + " CSV record(s) for "
                        + result.featureModelCount() + " distinct feature model(s); found "
                        + result.coreFrequencies().size() + " core name(s) and "
                        + result.deadFrequencies().size() + " dead name(s)");
    }

    private static AnalysisResult analyze(List<List<String>> rows) {
        int headerIndex = 0;
        while (headerIndex < rows.size() && isBlankRow(rows.get(headerIndex))) {
            headerIndex++;
        }
        if (headerIndex >= rows.size()) {
            throw new IllegalArgumentException("Input CSV is empty");
        }

        ColumnIndices columns = findColumnIndices(rows.get(headerIndex));
        Map<String, NameStatistics> coreStatistics = new HashMap<>();
        Map<String, NameStatistics> deadStatistics = new HashMap<>();
        Set<String> featureModels = new HashSet<>();
        int csvRecordCount = 0;

        for (int rowIndex = headerIndex + 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (isBlankRow(row)) {
                continue;
            }

            int recordNumber = rowIndex + 1;
            String featureModel = field(row, columns.inputFile(), "input_file", recordNumber);
            if (featureModel.isBlank()) {
                throw new IllegalArgumentException("CSV record " + recordNumber + " has an empty input_file");
            }

            addFeatureNames(
                    coreStatistics,
                    featureModel,
                    field(row, columns.coreFeatureCount(), "core_feature_count", recordNumber),
                    field(row, columns.coreFeatureNames(), "core_feature_names", recordNumber),
                    "core",
                    recordNumber);
            addFeatureNames(
                    deadStatistics,
                    featureModel,
                    field(row, columns.deadFeatureCount(), "dead_feature_count", recordNumber),
                    field(row, columns.deadFeatureNames(), "dead_feature_names", recordNumber),
                    "dead",
                    recordNumber);
            featureModels.add(featureModel);
            csvRecordCount++;
        }

        return new AnalysisResult(
                sortedFrequencies(coreStatistics),
                sortedFrequencies(deadStatistics),
                csvRecordCount,
                featureModels.size());
    }

    private static void addFeatureNames(
            Map<String, NameStatistics> statistics,
            String featureModel,
            String countField,
            String namesField,
            String featureType,
            int recordNumber) {
        long declaredCount = parseCount(countField, featureType, recordNumber);
        List<String> featureNames = parseFeatureNames(namesField, featureType, recordNumber);
        if (declaredCount != featureNames.size()) {
            throw new IllegalArgumentException("CSV record " + recordNumber + " declares " + declaredCount + " "
                    + featureType + " feature(s), but contains " + featureNames.size() + " name(s)");
        }

        for (String featureName : featureNames) {
            statistics
                    .computeIfAbsent(featureName, ignored -> new NameStatistics())
                    .addOccurrence(featureModel);
        }
    }

    private static long parseCount(String countField, String featureType, int recordNumber) {
        try {
            long count = Long.parseLong(countField);
            if (count < 0) {
                throw new NumberFormatException("negative count");
            }
            return count;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "CSV record " + recordNumber + " has an invalid " + featureType + " feature count: " + countField,
                    exception);
        }
    }

    private static List<String> parseFeatureNames(String namesField, String featureType, int recordNumber) {
        if (namesField.isEmpty()) {
            return List.of();
        }

        List<String> names = List.of(namesField.split(FEATURE_NAME_SEPARATOR, -1));
        if (names.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException(
                    "CSV record " + recordNumber + " contains an empty " + featureType + " feature name");
        }
        return names;
    }

    private static List<NameFrequency> sortedFrequencies(Map<String, NameStatistics> statistics) {
        return statistics.entrySet().stream()
                .map(entry -> entry.getValue().toFrequency(entry.getKey()))
                .sorted(FREQUENCY_ORDER)
                .toList();
    }

    private void writeFrequencies(List<NameFrequency> coreFrequencies, List<NameFrequency> deadFrequencies)
            throws IOException {
        Path outputParent = outputFile.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
                outputFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            appendCSVRow(writer, CSV_HEADER);
            int numberOfRanks = Math.max(coreFrequencies.size(), deadFrequencies.size());
            for (int index = 0; index < numberOfRanks; index++) {
                List<String> fields = new ArrayList<>(CSV_HEADER.size());
                fields.add(Integer.toString(index + 1));
                appendFrequency(fields, index < coreFrequencies.size() ? coreFrequencies.get(index) : null);
                appendFrequency(fields, index < deadFrequencies.size() ? deadFrequencies.get(index) : null);
                appendCSVRow(writer, fields);
            }
        }
    }

    private static void appendFrequency(List<String> fields, NameFrequency frequency) {
        if (frequency == null) {
            fields.add("");
            fields.add("");
            fields.add("");
        } else {
            fields.add(frequency.featureName());
            fields.add(Long.toString(frequency.featureCount()));
            fields.add(Integer.toString(frequency.featureModelCount()));
        }
    }

    private static ColumnIndices findColumnIndices(List<String> header) {
        Map<String, Integer> indices = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            String columnName = header.get(index);
            if (index == 0 && columnName.startsWith("\ufeff")) {
                columnName = columnName.substring(1);
            }
            if (indices.put(columnName, index) != null) {
                throw new IllegalArgumentException("Input CSV contains duplicate column: " + columnName);
            }
        }
        return new ColumnIndices(
                requireColumn(indices, "input_file"),
                requireColumn(indices, "core_feature_count"),
                requireColumn(indices, "core_feature_names"),
                requireColumn(indices, "dead_feature_count"),
                requireColumn(indices, "dead_feature_names"));
    }

    private static int requireColumn(Map<String, Integer> indices, String columnName) {
        Integer index = indices.get(columnName);
        if (index == null) {
            throw new IllegalArgumentException("Input CSV is missing required column: " + columnName);
        }
        return index;
    }

    private static String field(List<String> row, int columnIndex, String columnName, int recordNumber) {
        if (columnIndex >= row.size()) {
            throw new IllegalArgumentException("CSV record " + recordNumber + " is missing column: " + columnName);
        }
        return row.get(columnIndex);
    }

    private static boolean isBlankRow(List<String> row) {
        return row.size() == 1 && row.get(0).isBlank();
    }

    private static List<List<String>> readCSV(Path path) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean atStartOfField = true;
            boolean inQuotedField = false;
            boolean quotedFieldClosed = false;
            boolean recordHasContent = false;
            int character;

            while ((character = reader.read()) != -1) {
                if (inQuotedField) {
                    if (character == '"') {
                        int nextCharacter = reader.read();
                        if (nextCharacter == '"') {
                            field.append('"');
                        } else {
                            inQuotedField = false;
                            quotedFieldClosed = true;
                            if (nextCharacter != -1) {
                                reader.unread(nextCharacter);
                            }
                        }
                    } else {
                        field.append((char) character);
                    }
                    recordHasContent = true;
                    continue;
                }

                if (quotedFieldClosed && character != ',' && character != '\r' && character != '\n') {
                    throw new IllegalArgumentException(
                            "Unexpected character after closing quote in CSV record " + (rows.size() + 1));
                }

                if (character == '"') {
                    if (!atStartOfField) {
                        throw new IllegalArgumentException("Unexpected quote in CSV record " + (rows.size() + 1));
                    }
                    inQuotedField = true;
                    atStartOfField = false;
                    recordHasContent = true;
                } else if (character == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    atStartOfField = true;
                    quotedFieldClosed = false;
                    recordHasContent = true;
                } else if (character == '\r' || character == '\n') {
                    if (character == '\r') {
                        int nextCharacter = reader.read();
                        if (nextCharacter != '\n' && nextCharacter != -1) {
                            reader.unread(nextCharacter);
                        }
                    }
                    row.add(field.toString());
                    rows.add(List.copyOf(row));
                    row.clear();
                    field.setLength(0);
                    atStartOfField = true;
                    quotedFieldClosed = false;
                    recordHasContent = false;
                } else {
                    field.append((char) character);
                    atStartOfField = false;
                    recordHasContent = true;
                }
            }

            if (inQuotedField) {
                throw new IllegalArgumentException("Unterminated quoted field in CSV record " + (rows.size() + 1));
            }
            if (recordHasContent || !row.isEmpty()) {
                row.add(field.toString());
                rows.add(List.copyOf(row));
            }
        }
        return rows;
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
