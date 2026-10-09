#!/usr/bin/env Rscript

# This version includes these additional figures:
#   features_removed_by_model_size_combined.pdf
#   constraints_added_by_removed_feature_type.pdf
#   features_before_after_boxplot.pdf
#   constraints_before_after_boxplot.pdf
#   original_vs_simplified_features.pdf
#   original_vs_simplified_constraints.pdf
#   features_before_after_histogram.pdf
#   constraints_before_after_histogram.pdf
#   automotive_projection_time_by_completed_projections.pdf

# Exploratory analysis of feature-model reduction results
#
# Expected input files:
#   stats.csv
#   stats-meta.csv
#   failures.csv
# Optional detailed timing input:
#   stats-thorough.csv
# Optional timeout-model input:
#   automotive_scaling/automotive-stats.csv
# Optional projection-progress input:
#   automotive_scaling/automotive-projection-timings.csv
#
# Run from RStudio:
#   source("analyze_feature_model_reduction.R")
#
# Run from a terminal:
#   Rscript analyze_feature_model_reduction.R [data_directory] [output_directory]
#
# Examples:
#   Rscript analyze_feature_model_reduction.R
#   Rscript analyze_feature_model_reduction.R ./data ./analysis_output

# Type 2 AI: This file is the result of automated code generation. It undertook many iterations of code generation
# until it reached the current version.

required_packages <- c(
  "readr", "dplyr", "tidyr", "ggplot2", "stringr", "purrr", "broom", "scales"
)

missing_packages <- required_packages[
  !vapply(required_packages, requireNamespace, logical(1), quietly = TRUE)
]

if (length(missing_packages) > 0) {
  stop(
    "Missing R packages: ", paste(missing_packages, collapse = ", "),
    "\nInstall them with:\ninstall.packages(c(",
    paste(sprintf('"%s"', missing_packages), collapse = ", "), "))",
    call. = FALSE
  )
}

suppressPackageStartupMessages({
  library(readr)
  library(dplyr)
  library(tidyr)
  library(ggplot2)
  library(stringr)
  library(purrr)
  library(broom)
  library(scales)
})

options(dplyr.summarise.inform = FALSE)

args <- commandArgs(trailingOnly = TRUE)
data_directory <- if (length(args) >= 1) {
  args[[1]]
} else {
  "D:/Uni/FeatJAR/feature-model-assistance/src/main/resources/output/uvlhub_bulk_2026_08_18"
}
output_directory <- "D:\\Uni\\FeatJAR\\feature-model-assistance\\src\\main\\resources\\ROUTPUT"
plot_directory <- file.path(output_directory, "plots")
table_directory <- file.path(output_directory, "tables")

dir.create(plot_directory, recursive = TRUE, showWarnings = FALSE)
dir.create(table_directory, recursive = TRUE, showWarnings = FALSE)

stats_path <- file.path(data_directory, "stats.csv")
stats_thorough_path <- file.path(data_directory, "stats-thorough.csv")
meta_path <- file.path(data_directory, "stats-meta.csv")
failures_path <- file.path(data_directory, "failures.csv")
automotive_stats_path <- file.path(
  dirname(data_directory),
  "automotive_scaling",
  "automotive-stats.csv"
)
automotive_projection_timings_path <- file.path(
  dirname(data_directory),
  "automotive_scaling",
  "automotive-projection-timings.csv"
)

input_paths <- c(stats_path, meta_path, failures_path)
missing_files <- input_paths[!file.exists(input_paths)]
if (length(missing_files) > 0) {
  stop("Missing input file(s):\n", paste0("- ", missing_files, collapse = "\n"), call. = FALSE)
}

base_stats_required <- c(
  "input_file", "output_file", "original_features", "simplified_features",
  "feature_reduction_ratio", "original_constraints", "simplified_constraints",
  "constraint_reduction_ratio", "removed_core_features", "removed_dead_features",
  "removed_atomic_set_features", "full_reduction"
)

# Timing prefixes follow the order used in stats.csv. The reduction_time fields
# measure the complete reduction step; the reduction_* fields below them are
# nested measurements that explain where reduction time was spent.
top_level_timing_prefixes <- c(
  "input_model_loading_time",
  "boolean_feature_type_validation_time",
  "initial_satisfiability_check_time",
  "core_feature_computation_time",
  "dead_feature_computation_time",
  "atomic_set_computation_time",
  "reduction_time",
  "simplified_model_storage_time",
  "reduced_model_reloading_time",
  "statistics_computation_time"
)

reduction_subphase_timing_prefixes <- c(
  "reduction_satisfiability_check_time",
  "reduction_original_cnf_construction_time",
  "reduction_removal_plan_computation_time",
  "reduction_variable_projection_time",
  "reduction_feature_tree_rebuild_time",
  "reduction_structural_model_validation_time",
  "reduction_projected_constraint_addition_time",
  "reduction_reduced_model_validation_time"
)

timing_component_prefixes <- c(
  top_level_timing_prefixes[1:7],
  reduction_subphase_timing_prefixes,
  top_level_timing_prefixes[8:10],
  "evaluation_time"
)

timing_column_suffixes <- c(
  "_run_1_seconds",
  "_run_2_seconds",
  "_run_3_seconds",
  "_mean_seconds",
  "_standard_deviation_seconds"
)

timing_stats_columns <- unlist(
  lapply(
    timing_component_prefixes,
    function(prefix) paste0(prefix, timing_column_suffixes)
  ),
  use.names = FALSE
)

stats_required <- c(
  base_stats_required,
  timing_stats_columns
)
meta_required <- c("metric", "mean", "standard_deviation", "count")
failures_required <- c("input_file", "reason", "message")

assert_columns <- function(data, required, file_name) {
  absent <- setdiff(required, names(data))
  if (length(absent) > 0) {
    stop(
      file_name, " is missing column(s): ", paste(absent, collapse = ", "),
      call. = FALSE
    )
  }
}

read_input <- function(path) {
  read_csv(
    path,
    na = c("", "NA", "NaN", "null", "NULL"),
    trim_ws = TRUE,
    show_col_types = FALSE,
    progress = FALSE
  )
}

stats_raw <- read_input(stats_path)
meta_raw <- read_input(meta_path)
failures <- read_input(failures_path)

assert_columns(stats_raw, stats_required, "stats.csv")
assert_columns(meta_raw, meta_required, "stats-meta.csv")
assert_columns(failures, failures_required, "failures.csv")

numeric_stats_columns <- c(
  "original_features", "simplified_features", "feature_reduction_ratio",
  "original_constraints", "simplified_constraints", "constraint_reduction_ratio",
  "removed_core_features", "removed_dead_features", "removed_atomic_set_features",
  timing_stats_columns
)

evaluation_run_columns <- c(
  "evaluation_time_run_1_seconds",
  "evaluation_time_run_2_seconds",
  "evaluation_time_run_3_seconds"
)

top_level_timing_labels <- c(
  input_model_loading_time = "Input model loading",
  boolean_feature_type_validation_time = "Boolean feature-type validation",
  initial_satisfiability_check_time = "Initial satisfiability check",
  core_feature_computation_time = "Core-feature computation",
  dead_feature_computation_time = "Dead-feature computation",
  atomic_set_computation_time = "Atomic-set computation",
  reduction_time = "Complete reduction",
  simplified_model_storage_time = "Simplified-model storage",
  reduced_model_reloading_time = "Reduced-model reloading",
  statistics_computation_time = "Statistics computation"
)

reduction_subphase_timing_labels <- c(
  reduction_satisfiability_check_time = "Satisfiability check",
  reduction_original_cnf_construction_time = "Original CNF construction",
  reduction_removal_plan_computation_time = "Removal-plan computation",
  reduction_variable_projection_time = "Variable projection",
  reduction_feature_tree_rebuild_time = "Feature-tree rebuild",
  reduction_structural_model_validation_time = "Structural-model validation",
  reduction_projected_constraint_addition_time = "Projected-constraint addition",
  reduction_reduced_model_validation_time = "Reduced-model validation"
)

to_numeric <- function(x) {
  suppressWarnings(parse_double(as.character(x), locale = locale(decimal_mark = ".")))
}

stats <- stats_raw %>%
  mutate(across(all_of(numeric_stats_columns), to_numeric)) %>%
  rowwise() %>%
  mutate(
    evaluation_time_runs_available = sum(!is.na(c_across(all_of(evaluation_run_columns)))),
    calculated_evaluation_time_mean_seconds = if_else(
      evaluation_time_runs_available > 0,
      mean(c_across(all_of(evaluation_run_columns)), na.rm = TRUE),
      NA_real_
    ),
    calculated_evaluation_time_standard_deviation_seconds = if_else(
      evaluation_time_runs_available > 1,
      {
        evaluation_run_values <- c_across(all_of(evaluation_run_columns))
        evaluation_run_mean <- mean(evaluation_run_values, na.rm = TRUE)
        sqrt(mean(
          (evaluation_run_values - evaluation_run_mean)^2,
          na.rm = TRUE
        ))
      },
      NA_real_
    )
  ) %>%
  ungroup() %>%
  mutate(
    calculated_feature_reduction_ratio = if_else(
      original_features > 0,
      (original_features - simplified_features) / original_features,
      NA_real_
    ),
    calculated_constraint_reduction_ratio = if_else(
      original_constraints > 0,
      (original_constraints - simplified_constraints) / original_constraints,
      NA_real_
    ),
    feature_ratio_difference = feature_reduction_ratio - calculated_feature_reduction_ratio,
    constraint_ratio_difference = constraint_reduction_ratio - calculated_constraint_reduction_ratio,
    net_constraint_change = simplified_constraints - original_constraints,
    constraints_added = if_else(
      is.na(net_constraint_change),
      NA_real_,
      pmax(net_constraint_change, 0)
    ),
    evaluation_time_mean_difference_seconds =
      evaluation_time_mean_seconds - calculated_evaluation_time_mean_seconds,
    evaluation_time_standard_deviation_difference_seconds =
      evaluation_time_standard_deviation_seconds -
      calculated_evaluation_time_standard_deviation_seconds,
    evaluation_time_seconds_per_feature = if_else(
      original_features > 0,
      evaluation_time_mean_seconds / original_features,
      NA_real_
    ),
    evaluation_time_coefficient_of_variation = if_else(
      evaluation_time_mean_seconds > 0,
      evaluation_time_standard_deviation_seconds / evaluation_time_mean_seconds,
      NA_real_
    ),
    feature_count_group = case_when(
      is.na(original_features) ~ NA_character_,
      original_features <= 1000 ~ "1,000 features or fewer",
      TRUE ~ "More than 1,000 features"
    ),
    removed_features_reported = removed_core_features + removed_dead_features +
      removed_atomic_set_features,
    removed_features_from_sizes = original_features - simplified_features,
    removed_count_difference = removed_features_reported - removed_features_from_sizes,
    model_size_group = factor(
      ntile(original_features, 4),
      levels = 1:4,
      labels = c("Q1: smallest", "Q2", "Q3", "Q4: largest")
    )
  )

meta <- meta_raw %>%
  mutate(
    mean = to_numeric(mean),
    standard_deviation = to_numeric(standard_deviation),
    count = to_numeric(count)
  )

ratio_columns <- c("feature_reduction_ratio", "constraint_reduction_ratio")
invalid_ratio_rows <- stats %>%
  filter(
    !is.na(feature_reduction_ratio) &
      (feature_reduction_ratio < 0 | feature_reduction_ratio > 1) |
      !is.na(constraint_reduction_ratio) & constraint_reduction_ratio > 1
  )
if (nrow(invalid_ratio_rows) > 0) {
  warning(
    nrow(invalid_ratio_rows),
    paste(
      " row(s) contain an implausible reduction ratio.",
      "Negative constraint ratios are allowed because they indicate added constraints.",
      "See tables/data_quality_issues.csv."
    )
  )
}

negative_count_rows <- stats %>%
  filter(if_any(all_of(setdiff(numeric_stats_columns, ratio_columns)), ~ !is.na(.x) & .x < 0))

missing_by_column <- tibble(
  column = names(stats),
  missing_count = map_int(stats, ~ sum(is.na(.x))),
  missing_percentage = missing_count / nrow(stats)
)

data_quality_issues <- bind_rows(
  invalid_ratio_rows %>%
    transmute(input_file, issue = "Implausible reduction ratio"),
  negative_count_rows %>%
    transmute(input_file, issue = "Negative count or duration")
) %>%
  distinct()

write_csv(missing_by_column, file.path(table_directory, "missing_values.csv"))
write_csv(data_quality_issues, file.path(table_directory, "data_quality_issues.csv"))

summarise_numeric <- function(data, columns) {
  data %>%
    select(all_of(columns)) %>%
    pivot_longer(everything(), names_to = "metric", values_to = "value") %>%
    group_by(metric) %>%
    summarise(
      count = sum(!is.na(value)),
      missing = sum(is.na(value)),
      mean = mean(value, na.rm = TRUE),
      standard_deviation = sd(value, na.rm = TRUE),
      minimum = min(value, na.rm = TRUE),
      q1 = quantile(value, 0.25, na.rm = TRUE),
      median = median(value, na.rm = TRUE),
      q3 = quantile(value, 0.75, na.rm = TRUE),
      maximum = max(value, na.rm = TRUE)
    ) %>%
    mutate(across(where(is.numeric), ~ ifelse(is.infinite(.x), NA_real_, .x)))
}

descriptive_statistics <- summarise_numeric(stats, numeric_stats_columns)
write_csv(descriptive_statistics, file.path(table_directory, "descriptive_statistics.csv"))

timing_columns <- c(
  evaluation_run_columns,
  "evaluation_time_mean_seconds",
  "evaluation_time_standard_deviation_seconds",
  "evaluation_time_seconds_per_feature",
  "evaluation_time_coefficient_of_variation"
)

timing_summary <- summarise_numeric(stats, timing_columns)
write_csv(timing_summary, file.path(table_directory, "evaluation_time_summary.csv"))

make_component_timing_data <- function(data, prefixes, labels) {
  mean_columns <- paste0(prefixes, "_mean_seconds")
  if (!"timeout_timing_prefix" %in% names(data)) {
    data$timeout_timing_prefix <- NA_character_
  }
  if (!"current_stage_elapsed_seconds" %in% names(data)) {
    data$current_stage_elapsed_seconds <- NA_real_
  }
  identifier_columns <- intersect(
    c(
      "input_file", "original_features", "status",
      "last_completed_stage", "active_or_stuck_stage",
      "timeout_timing_prefix", "current_stage_elapsed_seconds"
    ),
    names(data)
  )

  data %>%
    select(all_of(identifier_columns), all_of(mean_columns)) %>%
    pivot_longer(
      cols = all_of(mean_columns),
      names_to = "timing_column",
      values_to = "reported_mean_seconds"
    ) %>%
    mutate(
      timing_prefix = str_remove(timing_column, "_mean_seconds$"),
      component = unname(labels[timing_prefix]),
      component_order = match(timing_prefix, prefixes),
      is_timeout_stage = !is.na(timeout_timing_prefix) &
        timing_prefix == timeout_timing_prefix,
      timeout_marker_seconds = if_else(
        is_timeout_stage,
        coalesce(current_stage_elapsed_seconds, reported_mean_seconds),
        NA_real_
      )
    )
}

summarise_component_timing <- function(data) {
  data %>%
    group_by(component_order, component, timing_prefix) %>%
    summarise(
      feature_models = sum(!is.na(reported_mean_seconds)),
      mean_seconds = mean(reported_mean_seconds, na.rm = TRUE),
      standard_deviation_seconds = sd(reported_mean_seconds, na.rm = TRUE),
      minimum_seconds = min(reported_mean_seconds, na.rm = TRUE),
      first_quartile_seconds = quantile(reported_mean_seconds, 0.25, na.rm = TRUE),
      median_seconds = median(reported_mean_seconds, na.rm = TRUE),
      third_quartile_seconds = quantile(reported_mean_seconds, 0.75, na.rm = TRUE),
      maximum_seconds = max(reported_mean_seconds, na.rm = TRUE)
    ) %>%
    mutate(across(where(is.numeric), ~ ifelse(is.nan(.x) | is.infinite(.x), NA_real_, .x))) %>%
    arrange(component_order)
}

evaluation_overview_prefixes <- c("evaluation_time", top_level_timing_prefixes)
evaluation_overview_labels <- c(
  evaluation_time = "Complete evaluation",
  top_level_timing_labels
)
evaluation_component_timing <- make_component_timing_data(
  stats,
  evaluation_overview_prefixes,
  evaluation_overview_labels
)
evaluation_component_timing_summary <- summarise_component_timing(
  evaluation_component_timing
)
write_csv(
  evaluation_component_timing_summary,
  file.path(table_directory, "evaluation_processing_step_time_summary.csv")
)

reduction_overview_prefixes <- c(
  "reduction_time",
  reduction_subphase_timing_prefixes
)
reduction_overview_labels <- c(
  reduction_time = "Complete reduction",
  reduction_subphase_timing_labels
)

# Default to the three-run means in stats.csv. When stats-thorough.csv is
# available, replace these values with its completed event durations.
reduction_component_timing <- make_component_timing_data(
  stats,
  reduction_overview_prefixes,
  reduction_overview_labels
)
reduction_timing_source_name <- "stats.csv"

normalise_reduction_event_step <- function(step) {
  step <- str_remove(as.character(step), "_seconds$")
  timing_prefix <- if_else(
    is.na(step),
    NA_character_,
    if_else(str_ends(step, "_time"), step, paste0(step, "_time"))
  )
  if_else(
    timing_prefix %in% reduction_overview_prefixes,
    timing_prefix,
    NA_character_
  )
}

if (file.exists(stats_thorough_path)) {
  thorough_stats <- read_input(stats_thorough_path)
  thorough_required_columns <- c(
    "sequence", "input_file", "model_status", "event", "step",
    "step_elapsed_seconds", "reduction_elapsed_seconds",
    "original_features"
  )
  thorough_missing_columns <- setdiff(
    thorough_required_columns,
    names(thorough_stats)
  )

  if (length(thorough_missing_columns) > 0) {
    warning(
      paste(
        "stats-thorough.csv cannot be used for the reduction-step plot",
        "because it is missing columns:"
      ),
      paste(thorough_missing_columns, collapse = ", "),
      ". Falling back to stats.csv.",
      call. = FALSE
    )
  } else {
    thorough_numeric_columns <- intersect(
      c(
        "sequence", "step_elapsed_seconds", "reduction_elapsed_seconds",
        "step_timeout_seconds", "original_features"
      ),
      names(thorough_stats)
    )
    thorough_stats <- thorough_stats %>%
      mutate(across(all_of(thorough_numeric_columns), to_numeric))

    thorough_completed_step_timing <- thorough_stats %>%
      mutate(
        timing_prefix = normalise_reduction_event_step(step)
      ) %>%
      filter(
        event == "step_completed",
        !is.na(timing_prefix),
        !is.na(step_elapsed_seconds),
        step_elapsed_seconds >= 0
      ) %>%
      arrange(input_file, timing_prefix, sequence) %>%
      group_by(input_file, timing_prefix) %>%
      slice_tail(n = 1) %>%
      ungroup() %>%
      transmute(
        input_file,
        original_features,
        status = model_status,
        timing_column = paste0(timing_prefix, "_event_seconds"),
        reported_mean_seconds = step_elapsed_seconds,
        timing_prefix,
        component = unname(reduction_overview_labels[timing_prefix]),
        component_order = match(timing_prefix, reduction_overview_prefixes),
        is_timeout_stage = FALSE,
        timeout_marker_seconds = NA_real_
      )

    # Some event-log versions report the complete reduction only on the final
    # model_completed row. Add it only when no completed reduction event exists
    # for that feature model.
    thorough_completed_reduction_totals <- thorough_stats %>%
      filter(
        event == "model_completed",
        !is.na(reduction_elapsed_seconds),
        reduction_elapsed_seconds >= 0
      ) %>%
      arrange(input_file, sequence) %>%
      group_by(input_file) %>%
      slice_tail(n = 1) %>%
      ungroup() %>%
      anti_join(
        thorough_completed_step_timing %>%
          filter(timing_prefix == "reduction_time") %>%
          distinct(input_file),
        by = "input_file"
      ) %>%
      transmute(
        input_file,
        original_features,
        status = model_status,
        timing_column = "reduction_time_event_seconds",
        reported_mean_seconds = reduction_elapsed_seconds,
        timing_prefix = "reduction_time",
        component = unname(
          reduction_overview_labels["reduction_time"]
        ),
        component_order = match(
          "reduction_time",
          reduction_overview_prefixes
        ),
        is_timeout_stage = FALSE,
        timeout_marker_seconds = NA_real_
      )

    thorough_reduction_component_timing <- bind_rows(
      thorough_completed_step_timing,
      thorough_completed_reduction_totals
    )

    if (nrow(thorough_reduction_component_timing) > 0) {
      reduction_component_timing <- thorough_reduction_component_timing
      reduction_timing_source_name <- "stats-thorough.csv"
    } else {
      warning(
        paste(
          "stats-thorough.csv contains no completed reduction-step timings.",
          "Falling back to stats.csv."
        ),
        call. = FALSE
      )
    }
  }
} else {
  warning(
    "stats-thorough.csv was not found; using stats.csv for the reduction-step plot.",
    call. = FALSE
  )
}

reduction_component_timing_summary <- summarise_component_timing(
  reduction_component_timing
) %>%
  mutate(source_file = reduction_timing_source_name, .before = 1)
write_csv(
  reduction_component_timing_summary,
  file.path(table_directory, "reduction_processing_step_time_summary.csv")
)

# Split successful reductions at 1,000 original features. Models with exactly
# 1,000 features belong to the lower group so every valid row is retained.
stats_less_or_equal_1000 <- stats %>%
  filter(!is.na(original_features), original_features <= 1000)

stats_more_than_1000 <- stats %>%
  filter(!is.na(original_features), original_features > 1000)

# Summarise feature and constraint counts before reduction, after reduction,
# and as a signed per-model change. A positive change means elements were
# added; a negative change means elements were removed.
summarise_count_vector <- function(values) {
  finite_values <- values[is.finite(values)]

  tibble(
    feature_models = length(finite_values),
    mean = if (length(finite_values) > 0) mean(finite_values) else NA_real_,
    median = if (length(finite_values) > 0) median(finite_values) else NA_real_,
    standard_deviation = if (length(finite_values) > 1) {
      sd(finite_values)
    } else {
      NA_real_
    }
  )
}

summarise_before_after_counts <- function(
  data,
  before_column,
  after_column,
  quantity_label,
  feature_group_label
) {
  valid_counts <- data %>%
    transmute(
      before = .data[[before_column]],
      after = .data[[after_column]]
    ) %>%
    filter(is.finite(before), is.finite(after)) %>%
    mutate(change = after - before)

  bind_rows(
    summarise_count_vector(valid_counts$before) %>%
      mutate(measurement = "Before reduction", .before = 1),
    summarise_count_vector(valid_counts$after) %>%
      mutate(measurement = "After reduction", .before = 1),
    summarise_count_vector(valid_counts$change) %>%
      mutate(measurement = "Change (after - before)", .before = 1)
  ) %>%
    mutate(
      original_feature_group = feature_group_label,
      quantity = quantity_label,
      .before = 1
    )
}

before_after_count_summary_by_size <- bind_rows(
  summarise_before_after_counts(
    stats_less_or_equal_1000,
    "original_features",
    "simplified_features",
    "Features",
    "1,000 features or fewer"
  ),
  summarise_before_after_counts(
    stats_less_or_equal_1000,
    "original_constraints",
    "simplified_constraints",
    "Constraints",
    "1,000 features or fewer"
  ),
  summarise_before_after_counts(
    stats_more_than_1000,
    "original_features",
    "simplified_features",
    "Features",
    "More than 1,000 features"
  ),
  summarise_before_after_counts(
    stats_more_than_1000,
    "original_constraints",
    "simplified_constraints",
    "Constraints",
    "More than 1,000 features"
  )
)

write_csv(
  before_after_count_summary_by_size,
  file.path(
    table_directory,
    "feature_and_constraint_counts_by_1000_feature_groups.csv"
  )
)

# Preserve the original stats.csv schema in the two raw subset exports.
write_csv(
  stats_less_or_equal_1000 %>% select(all_of(stats_required)),
  file.path(table_directory, "stats_less_or_equal_to_1000_features.csv")
)
write_csv(
  stats_more_than_1000 %>% select(all_of(stats_required)),
  file.path(table_directory, "stats_more_than_1000_features.csv")
)

size_group_numeric_columns <- c(
  numeric_stats_columns,
  "net_constraint_change",
  "constraints_added",
  "evaluation_time_seconds_per_feature",
  "evaluation_time_coefficient_of_variation"
)

descriptive_statistics_less_or_equal_1000 <- summarise_numeric(
  stats_less_or_equal_1000,
  size_group_numeric_columns
)
descriptive_statistics_more_than_1000 <- summarise_numeric(
  stats_more_than_1000,
  size_group_numeric_columns
)

write_csv(
  descriptive_statistics_less_or_equal_1000,
  file.path(
    table_directory,
    "descriptive_statistics_less_or_equal_to_1000_features.csv"
  )
)
write_csv(
  descriptive_statistics_more_than_1000,
  file.path(
    table_directory,
    "descriptive_statistics_more_than_1000_features.csv"
  )
)

model_size_group_comparison <- stats %>%
  filter(!is.na(feature_count_group)) %>%
  group_by(feature_count_group) %>%
  summarise(
    feature_models = n(),
    mean_original_features = mean(original_features, na.rm = TRUE),
    median_original_features = median(original_features, na.rm = TRUE),
    mean_feature_reduction = mean(feature_reduction_ratio, na.rm = TRUE),
    median_feature_reduction = median(feature_reduction_ratio, na.rm = TRUE),
    mean_net_constraint_change = mean(net_constraint_change, na.rm = TRUE),
    median_net_constraint_change = median(net_constraint_change, na.rm = TRUE),
    mean_constraints_added = mean(constraints_added, na.rm = TRUE),
    median_constraints_added = median(constraints_added, na.rm = TRUE),
    mean_core_features_removed = mean(removed_core_features, na.rm = TRUE),
    mean_dead_features_removed = mean(removed_dead_features, na.rm = TRUE),
    mean_atomic_set_features_removed = mean(
      removed_atomic_set_features,
      na.rm = TRUE
    ),
    mean_evaluation_time_seconds = mean(evaluation_time_mean_seconds, na.rm = TRUE),
    standard_deviation_evaluation_time_seconds = sd(
      evaluation_time_mean_seconds,
      na.rm = TRUE
    ),
    median_evaluation_time_seconds = median(evaluation_time_mean_seconds, na.rm = TRUE),
    minimum_evaluation_time_seconds = min(evaluation_time_mean_seconds, na.rm = TRUE),
    maximum_evaluation_time_seconds = max(evaluation_time_mean_seconds, na.rm = TRUE),
    mean_seconds_per_feature = mean(evaluation_time_seconds_per_feature, na.rm = TRUE),
    median_seconds_per_feature = median(evaluation_time_seconds_per_feature, na.rm = TRUE),
    mean_timing_coefficient_of_variation = mean(
      evaluation_time_coefficient_of_variation,
      na.rm = TRUE
    )
  ) %>%
  mutate(across(where(is.numeric), ~ ifelse(is.nan(.x) | is.infinite(.x), NA_real_, .x)))

write_csv(
  model_size_group_comparison,
  file.path(table_directory, "comparison_less_and_more_than_1000_features.csv")
)

timing_by_feature_count_group <- stats %>%
  filter(!is.na(feature_count_group)) %>%
  group_by(feature_count_group) %>%
  summarise(
    feature_models = n(),
    timed_feature_models = sum(!is.na(evaluation_time_mean_seconds)),
    mean_evaluation_time_seconds = mean(evaluation_time_mean_seconds, na.rm = TRUE),
    standard_deviation_evaluation_time_seconds = sd(
      evaluation_time_mean_seconds,
      na.rm = TRUE
    ),
    minimum_evaluation_time_seconds = min(evaluation_time_mean_seconds, na.rm = TRUE),
    first_quartile_evaluation_time_seconds = quantile(
      evaluation_time_mean_seconds,
      0.25,
      na.rm = TRUE
    ),
    median_evaluation_time_seconds = median(evaluation_time_mean_seconds, na.rm = TRUE),
    third_quartile_evaluation_time_seconds = quantile(
      evaluation_time_mean_seconds,
      0.75,
      na.rm = TRUE
    ),
    maximum_evaluation_time_seconds = max(evaluation_time_mean_seconds, na.rm = TRUE),
    mean_seconds_per_feature = mean(evaluation_time_seconds_per_feature, na.rm = TRUE),
    median_seconds_per_feature = median(evaluation_time_seconds_per_feature, na.rm = TRUE),
    mean_timing_coefficient_of_variation = mean(
      evaluation_time_coefficient_of_variation,
      na.rm = TRUE
    )
  ) %>%
  mutate(across(where(is.numeric), ~ ifelse(is.nan(.x) | is.infinite(.x), NA_real_, .x)))

write_csv(
  timing_by_feature_count_group,
  file.path(table_directory, "evaluation_time_by_1000_feature_groups.csv")
)

attempt_overview <- tibble(
  successful_reductions = nrow(stats),
  failed_reductions = nrow(failures),
  total_attempts = nrow(stats) + nrow(failures),
  success_rate = if_else(total_attempts > 0, successful_reductions / total_attempts, NA_real_),
  failure_rate = if_else(total_attempts > 0, failed_reductions / total_attempts, NA_real_)
)
write_csv(attempt_overview, file.path(table_directory, "attempt_overview.csv"))

reduction_by_size <- stats %>%
  group_by(model_size_group) %>%
  summarise(
    models = n(),
    min_original_features = min(original_features, na.rm = TRUE),
    max_original_features = max(original_features, na.rm = TRUE),
    mean_feature_reduction = mean(feature_reduction_ratio, na.rm = TRUE),
    median_feature_reduction = median(feature_reduction_ratio, na.rm = TRUE),
    mean_constraint_reduction = mean(constraint_reduction_ratio, na.rm = TRUE),
    median_constraint_reduction = median(constraint_reduction_ratio, na.rm = TRUE)
  ) %>%
  mutate(across(where(is.numeric), ~ ifelse(is.infinite(.x), NA_real_, .x)))
write_csv(reduction_by_size, file.path(table_directory, "reduction_by_model_size.csv"))

removed_long <- stats %>%
  select(input_file, starts_with("removed_")) %>%
  pivot_longer(
    cols = c(removed_core_features, removed_dead_features, removed_atomic_set_features),
    names_to = "removal_type",
    values_to = "removed_features"
  ) %>%
  mutate(
    removal_type = recode(
      removal_type,
      removed_core_features = "Core features",
      removed_dead_features = "Dead features",
      removed_atomic_set_features = "Atomic-set features"
    )
  )

removal_summary <- removed_long %>%
  group_by(removal_type) %>%
  summarise(
    total_removed = sum(removed_features, na.rm = TRUE),
    mean_per_model = mean(removed_features, na.rm = TRUE),
    median_per_model = median(removed_features, na.rm = TRUE),
    maximum_per_model = max(removed_features, na.rm = TRUE),
    models_with_removals = sum(removed_features > 0, na.rm = TRUE),
    share_of_models_with_removals = mean(removed_features > 0, na.rm = TRUE)
  ) %>%
  mutate(
    share_of_all_removed_features = total_removed / sum(total_removed, na.rm = TRUE)
  )
write_csv(removal_summary, file.path(table_directory, "removal_type_summary.csv"))

summarise_removals_for_size_group <- function(data) {
  data %>%
    select(
      removed_core_features,
      removed_dead_features,
      removed_atomic_set_features
    ) %>%
    pivot_longer(
      everything(),
      names_to = "removal_type",
      values_to = "removed_features"
    ) %>%
    mutate(
      removal_type = recode(
        removal_type,
        removed_core_features = "Core features",
        removed_dead_features = "Dead features",
        removed_atomic_set_features = "Atomic-set features"
      )
    ) %>%
    group_by(removal_type) %>%
    summarise(
      feature_models = n(),
      total_removed = sum(removed_features, na.rm = TRUE),
      mean_per_model = mean(removed_features, na.rm = TRUE),
      median_per_model = median(removed_features, na.rm = TRUE),
      maximum_per_model = max(removed_features, na.rm = TRUE),
      models_with_removals = sum(removed_features > 0, na.rm = TRUE),
      share_of_models_with_removals = mean(removed_features > 0, na.rm = TRUE)
    ) %>%
    mutate(across(where(is.numeric), ~ ifelse(is.infinite(.x), NA_real_, .x)))
}

removal_summary_less_or_equal_1000 <- summarise_removals_for_size_group(
  stats_less_or_equal_1000
)
removal_summary_more_than_1000 <- summarise_removals_for_size_group(
  stats_more_than_1000
)

write_csv(
  removal_summary_less_or_equal_1000,
  file.path(
    table_directory,
    "removal_type_summary_less_or_equal_to_1000_features.csv"
  )
)
write_csv(
  removal_summary_more_than_1000,
  file.path(
    table_directory,
    "removal_type_summary_more_than_1000_features.csv"
  )
)

safe_paired_test <- function(original, simplified, test_name) {
  complete <- complete.cases(original, simplified)
  original <- original[complete]
  simplified <- simplified[complete]
  
  if (length(original) < 2 || all((original - simplified) == 0)) {
    return(tibble(
      test = test_name, sample_size = length(original), estimate = NA_real_,
      statistic = NA_real_, p_value = NA_real_, method = "Not enough variation"
    ))
  }
  
  test <- if (test_name == "Paired t-test") {
    t.test(original, simplified, paired = TRUE)
  } else {
    suppressWarnings(wilcox.test(original, simplified, paired = TRUE, exact = FALSE))
  }
  
  tidy(test) %>%
    transmute(
      test = test_name,
      sample_size = length(original),
      estimate = if ("estimate" %in% names(.)) estimate else NA_real_,
      statistic,
      p_value = p.value,
      method
    )
}

paired_tests <- bind_rows(
  safe_paired_test(stats$original_features, stats$simplified_features, "Paired t-test") %>%
    mutate(outcome = "Features"),
  safe_paired_test(stats$original_features, stats$simplified_features, "Wilcoxon signed-rank test") %>%
    mutate(outcome = "Features"),
  safe_paired_test(stats$original_constraints, stats$simplified_constraints, "Paired t-test") %>%
    mutate(outcome = "Constraints"),
  safe_paired_test(stats$original_constraints, stats$simplified_constraints, "Wilcoxon signed-rank test") %>%
    mutate(outcome = "Constraints")
) %>%
  select(outcome, everything())
write_csv(paired_tests, file.path(table_directory, "paired_reduction_tests.csv"))

correlation_pairs <- tribble(
  ~x, ~y,
  "original_features", "feature_reduction_ratio",
  "original_constraints", "constraint_reduction_ratio",
  "original_features", "original_constraints",
  "feature_reduction_ratio", "constraint_reduction_ratio",
  "removed_core_features", "feature_reduction_ratio",
  "removed_dead_features", "feature_reduction_ratio",
  "removed_atomic_set_features", "feature_reduction_ratio",
  "original_features", "evaluation_time_mean_seconds",
  "original_constraints", "evaluation_time_mean_seconds",
  "simplified_features", "evaluation_time_mean_seconds",
  "removed_features_reported", "evaluation_time_mean_seconds"
)

safe_spearman <- function(x_name, y_name) {
  pair <- stats %>% select(all_of(c(x_name, y_name))) %>% drop_na()
  if (nrow(pair) < 3 || n_distinct(pair[[1]]) < 2 || n_distinct(pair[[2]]) < 2) {
    return(tibble(
      x = x_name, y = y_name, sample_size = nrow(pair),
      rho = NA_real_, p_value = NA_real_
    ))
  }
  result <- suppressWarnings(cor.test(pair[[1]], pair[[2]], method = "spearman", exact = FALSE))
  tibble(
    x = x_name,
    y = y_name,
    sample_size = nrow(pair),
    rho = unname(result$estimate),
    p_value = result$p.value
  )
}

correlations <- map2_dfr(correlation_pairs$x, correlation_pairs$y, safe_spearman) %>%
  mutate(p_value_bh = p.adjust(p_value, method = "BH"))
write_csv(correlations, file.path(table_directory, "spearman_correlations.csv"))

timing_correlations <- correlations %>%
  filter(y == "evaluation_time_mean_seconds")
write_csv(
  timing_correlations,
  file.path(table_directory, "evaluation_time_spearman_correlations.csv")
)

# Exploratory regressions: coefficients describe associations, not causal effects.
feature_model_data <- stats %>%
  select(feature_reduction_ratio, original_features, original_constraints) %>%
  drop_na()
constraint_model_data <- stats %>%
  select(constraint_reduction_ratio, original_features, original_constraints) %>%
  drop_na()

regression_results <- tibble()
if (nrow(feature_model_data) >= 10) {
  feature_model <- lm(
    feature_reduction_ratio ~ log1p(original_features) + log1p(original_constraints),
    data = feature_model_data
  )
  regression_results <- bind_rows(
    regression_results,
    tidy(feature_model, conf.int = TRUE) %>% mutate(outcome = "Feature reduction ratio")
  )
}
if (nrow(constraint_model_data) >= 10) {
  constraint_model <- lm(
    constraint_reduction_ratio ~ log1p(original_features) + log1p(original_constraints),
    data = constraint_model_data
  )
  regression_results <- bind_rows(
    regression_results,
    tidy(constraint_model, conf.int = TRUE) %>% mutate(outcome = "Constraint reduction ratio")
  )
}
write_csv(regression_results, file.path(table_directory, "exploratory_regressions.csv"))

# The log-log timing model estimates how evaluation time scales with model size.
# Coefficients describe associations and should not be interpreted causally.
timing_model_data <- stats %>%
  select(
    evaluation_time_mean_seconds,
    original_features,
    original_constraints
  ) %>%
  filter(
    evaluation_time_mean_seconds >= 0,
    original_features >= 0,
    original_constraints >= 0
  ) %>%
  drop_na()

timing_regression_results <- tibble()
if (nrow(timing_model_data) >= 10) {
  timing_model <- lm(
    log1p(evaluation_time_mean_seconds) ~
      log1p(original_features) + log1p(original_constraints),
    data = timing_model_data
  )
  timing_regression_results <- tidy(timing_model, conf.int = TRUE) %>%
    mutate(
      outcome = "Log of complete evaluation time in seconds",
      sample_size = nrow(timing_model_data),
      .before = 1
    )
}
write_csv(
  timing_regression_results,
  file.path(table_directory, "evaluation_time_exploratory_regression.csv")
)

failure_summary <- failures %>%
  mutate(
    reason = if_else(is.na(reason) | str_trim(reason) == "", "Unspecified", str_trim(reason)),
    message = if_else(is.na(message), "", str_squish(message))
  ) %>%
  count(reason, sort = TRUE, name = "failures") %>%
  mutate(share_of_failures = failures / sum(failures))
write_csv(failure_summary, file.path(table_directory, "failure_reasons.csv"))

failure_messages <- failures %>%
  mutate(
    reason = if_else(is.na(reason) | str_trim(reason) == "", "Unspecified", str_trim(reason)),
    message = if_else(is.na(message) | str_trim(message) == "", "Unspecified", str_squish(message))
  ) %>%
  count(reason, message, sort = TRUE, name = "occurrences")
write_csv(failure_messages, file.path(table_directory, "failure_messages.csv"))

# full_reduction is analyzed without assuming whether the producer stored it as
# a Boolean flag, numeric measurement, or categorical label.
full_reduction_text <- str_to_lower(str_trim(as.character(stats$full_reduction)))
full_reduction_nonmissing <- full_reduction_text[!is.na(full_reduction_text) & full_reduction_text != ""]
logical_labels <- c("true", "false", "t", "f", "yes", "no", "1", "0")
full_reduction_numeric <- suppressWarnings(parse_double(full_reduction_nonmissing))

if (length(full_reduction_nonmissing) == 0) {
  full_reduction_summary <- tibble(
    detected_type = "empty", value = NA_character_, count = 0L, share = NA_real_
  )
} else if (all(full_reduction_nonmissing %in% logical_labels)) {
  normalized_logical <- if_else(
    full_reduction_nonmissing %in% c("true", "t", "yes", "1"), "TRUE", "FALSE"
  )
  full_reduction_summary <- tibble(value = normalized_logical) %>%
    count(value, name = "count") %>%
    mutate(
      detected_type = "logical",
      share = count / sum(count),
      .before = 1
    )
} else if (all(!is.na(full_reduction_numeric))) {
  full_reduction_summary <- tibble(
    detected_type = "numeric",
    statistic = c("count", "mean", "standard_deviation", "minimum", "median", "maximum"),
    value = c(
      length(full_reduction_numeric),
      mean(full_reduction_numeric),
      sd(full_reduction_numeric),
      min(full_reduction_numeric),
      median(full_reduction_numeric),
      max(full_reduction_numeric)
    )
  )
} else {
  full_reduction_summary <- tibble(value = full_reduction_nonmissing) %>%
    count(value, sort = TRUE, name = "count") %>%
    mutate(
      detected_type = "categorical",
      share = count / sum(count),
      .before = 1
    )
}
write_csv(full_reduction_summary, file.path(table_directory, "full_reduction_summary.csv"))

# Compare stats-meta.csv with values recalculated from equally named stats.csv columns.
meta_comparison <- meta %>%
  mutate(metric_normalized = str_replace_all(str_to_lower(str_trim(metric)), "[^a-z0-9]+", "_")) %>%
  rowwise() %>%
  mutate(
    metric_found = metric_normalized %in% names(stats),
    recalculated_mean = if (metric_found && is.numeric(stats[[metric_normalized]])) {
      mean(stats[[metric_normalized]], na.rm = TRUE)
    } else NA_real_,
    recalculated_standard_deviation = if (metric_found && is.numeric(stats[[metric_normalized]])) {
      sd(stats[[metric_normalized]], na.rm = TRUE)
    } else NA_real_,
    recalculated_count = if (metric_found && is.numeric(stats[[metric_normalized]])) {
      sum(!is.na(stats[[metric_normalized]]))
    } else NA_real_
  ) %>%
  ungroup() %>%
  mutate(
    mean_difference = mean - recalculated_mean,
    standard_deviation_difference = standard_deviation - recalculated_standard_deviation,
    count_difference = count - recalculated_count
  )
write_csv(meta_comparison, file.path(table_directory, "stats_meta_comparison.csv"))

ratio_consistency <- stats %>%
  summarise(
    feature_ratio_rows_compared = sum(!is.na(feature_ratio_difference)),
    max_absolute_feature_ratio_difference = max(abs(feature_ratio_difference), na.rm = TRUE),
    feature_ratio_mismatches_over_tolerance = sum(abs(feature_ratio_difference) > 1e-8, na.rm = TRUE),
    constraint_ratio_rows_compared = sum(!is.na(constraint_ratio_difference)),
    max_absolute_constraint_ratio_difference = max(abs(constraint_ratio_difference), na.rm = TRUE),
    constraint_ratio_mismatches_over_tolerance = sum(abs(constraint_ratio_difference) > 1e-8, na.rm = TRUE),
    removed_count_rows_compared = sum(!is.na(removed_count_difference)),
    removed_count_mismatches = sum(removed_count_difference != 0, na.rm = TRUE),
    timing_mean_rows_compared = sum(!is.na(evaluation_time_mean_difference_seconds)),
    max_absolute_timing_mean_difference_seconds = max(
      abs(evaluation_time_mean_difference_seconds),
      na.rm = TRUE
    ),
    timing_mean_mismatches_over_tolerance = sum(
      abs(evaluation_time_mean_difference_seconds) > 1e-6,
      na.rm = TRUE
    ),
    timing_standard_deviation_rows_compared = sum(
      !is.na(evaluation_time_standard_deviation_difference_seconds)
    ),
    max_absolute_timing_standard_deviation_difference_seconds = max(
      abs(evaluation_time_standard_deviation_difference_seconds),
      na.rm = TRUE
    ),
    timing_standard_deviation_mismatches_over_tolerance = sum(
      abs(evaluation_time_standard_deviation_difference_seconds) > 1e-6,
      na.rm = TRUE
    ),
    rows_without_all_three_timing_runs = sum(evaluation_time_runs_available < 3)
  ) %>%
  mutate(across(where(is.numeric), ~ ifelse(is.infinite(.x), NA_real_, .x)))
write_csv(ratio_consistency, file.path(table_directory, "consistency_checks.csv"))

theme_set(
  theme_minimal(base_size = 12) +
    theme(
      plot.title.position = "plot",
      plot.title = element_blank(),
      plot.subtitle = element_blank(),
      plot.caption = element_blank(),
      panel.grid.minor = element_blank(),
      legend.position = "bottom",
      text = element_text(color = "black"),
      axis.text = element_text(color = "black"),
      plot.background = element_rect(fill = "white", color = NA),
      panel.background = element_rect(fill = "white", color = NA),
      legend.background = element_rect(fill = "white", color = NA),
      legend.key = element_rect(fill = "white", color = NA),
      strip.background = element_rect(fill = "grey95", color = NA),
      strip.text = element_text(color = "black")
    )
)

save_pdf <- function(file_name, plot, width, height) {
  # Figure captions are supplied in LaTeX; exported PDFs contain only the
  # diagram, axis labels, facet labels, and any required legend.
  plot <- plot + labs(title = NULL, subtitle = NULL, caption = NULL)
  ggsave(
    filename = file.path(plot_directory, file_name),
    plot = plot,
    width = width,
    height = height,
    units = "in",
    device = grDevices::cairo_pdf,
    bg = "white"
  )
}

# More frequent integer labels for logarithmically spaced count axes.
# Large ranges use 1, 2, and 5 within each decade (10, 20, 50, 100, ...).
# Narrow ranges fall back to evenly spaced integer labels.
dense_count_axis_breaks <- function(limits) {
  finite_limits <- limits[is.finite(limits)]
  if (length(finite_limits) == 0 || max(finite_limits) < 0) {
    return(numeric())
  }

  lower <- max(0, min(finite_limits))
  upper <- max(finite_limits)
  if (upper <= 10) {
    first_integer <- ceiling(lower)
    last_integer <- floor(upper)
    if (first_integer > last_integer) {
      return(numeric())
    }
    return(seq.int(first_integer, last_integer))
  }

  decades <- 10 ^ seq.int(0, ceiling(log10(upper)))
  breaks <- sort(unique(c(0, as.vector(outer(c(1, 2, 5), decades)))))
  breaks <- breaks[breaks >= lower & breaks <= upper]
  if (length(breaks) < 3) {
    breaks <- sort(unique(round(pretty(c(lower, upper), n = 8))))
    breaks <- breaks[breaks >= lower & breaks <= upper]
  }
  breaks
}

timing_axis_breaks <- c(
  0, 0.0001, 0.001, 0.01, 0.1, 1, 10, 100, 1000, 10000
)

format_seconds_axis <- function(values) {
  vapply(
    values,
    function(value) {
      if (is.na(value)) {
        return(NA_character_)
      }
      if (value == 0) {
        return("0")
      }
      if (abs(value) < 0.001) {
        return(scales::number(value, accuracy = 0.0001))
      }
      if (abs(value) < 0.01) {
        return(scales::number(value, accuracy = 0.001))
      }
      if (abs(value) < 0.1) {
        return(scales::number(value, accuracy = 0.01))
      }
      if (abs(value) < 1) {
        return(scales::number(value, accuracy = 0.1))
      }
      scales::number(value, accuracy = 1, big.mark = ",")
    },
    character(1)
  )
}

make_timing_overview_plot <- function(
  data,
  prefixes,
  labels,
  total_prefix,
  file_name,
  height,
  show_individual_observations = FALSE,
  show_timeout_stages = FALSE,
  show_empty_steps = FALSE
) {
  plot_data <- data %>%
    mutate(
      component = factor(
        component,
        levels = rev(unname(labels[prefixes]))
      ),
      measurement_type = if_else(
        timing_prefix %in% total_prefix,
        "Complete step",
        "Contained processing step"
      )
    )

  plot <- ggplot(
    plot_data,
    aes(x = reported_mean_seconds, y = component, fill = measurement_type)
  ) +
    geom_boxplot(
      width = 0.62,
      alpha = 0.75,
      outlier.shape = if (show_individual_observations) NA else 19,
      outlier.alpha = 0.22,
      outlier.size = 1.1,
      na.rm = TRUE
    )

  if (show_individual_observations) {
    plot <- plot +
      geom_point(
        position = position_jitter(width = 0, height = 0.10),
        shape = 21,
        fill = "white",
        color = "grey25",
        size = 1.8,
        stroke = 0.35,
        alpha = 0.85,
        na.rm = TRUE
      )
  }

  if (show_timeout_stages && any(plot_data$is_timeout_stage, na.rm = TRUE)) {
    plot <- plot +
      geom_point(
        data = plot_data %>%
          filter(is_timeout_stage, !is.na(timeout_marker_seconds)),
        aes(x = timeout_marker_seconds, y = component),
        inherit.aes = FALSE,
        shape = 24,
        fill = "#D73027",
        color = "#8B0000",
        size = 3.2,
        stroke = 0.55,
        na.rm = TRUE
      )
  }

  plot <- plot +
    stat_summary(
      fun = mean,
      geom = "point",
      shape = 18,
      size = 2.8,
      color = "black",
      na.rm = TRUE
    ) +
    scale_x_continuous(
      trans = scales::pseudo_log_trans(sigma = 0.0001, base = 10),
      breaks = timing_axis_breaks,
      labels = format_seconds_axis
    ) +
    scale_y_discrete(drop = !show_empty_steps) +
    scale_fill_manual(
      values = c(
        "Complete step" = "#E07A1F",
        "Contained processing step" = "#8EC0DD"
      )
    ) +
    labs(
      x = "Time for one feature model (seconds)",
      y = NULL,
      fill = NULL
    )

  save_pdf(file_name, plot, width = 9.5, height = height)
  plot
}

p_evaluation_step_overview <- make_timing_overview_plot(
  data = evaluation_component_timing,
  prefixes = evaluation_overview_prefixes,
  labels = evaluation_overview_labels,
  total_prefix = "evaluation_time",
  file_name = "evaluation_processing_step_time_overview.pdf",
  height = 7
)

p_reduction_step_overview <- make_timing_overview_plot(
  data = reduction_component_timing,
  prefixes = reduction_overview_prefixes,
  labels = reduction_overview_labels,
  total_prefix = "reduction_time",
  file_name = "reduction_processing_step_time_overview.pdf",
  height = 6.2,
  show_empty_steps = TRUE
)

# Create processing-stage and timeout-stage overviews for the automotive
# models. The current automotive-stats.csv is an event log with one row per
# event; the former one-row-per-model schema remains supported as a fallback.
# The event log contains start and completion events for most stages. This is
# the deduplicated processing order that must remain visible on both y-axes,
# including stages with no observations because a model timed out earlier.
automotive_overview_prefixes <- c(
  "input_model_loading_time",
  "boolean_feature_type_validation_time",
  "initial_satisfiability_check_time",
  "core_feature_computation_time",
  "dead_feature_computation_time",
  "atomic_set_computation_time",
  "reduction_time",
  "reduction_satisfiability_check_time",
  "reduction_original_cnf_construction_time",
  "reduction_removal_plan_computation_time",
  "reduction_variable_projection_time",
  "reduction_feature_tree_rebuild_time",
  "reduction_structural_model_validation_time",
  "reduction_projected_constraint_addition_time",
  "reduction_reduced_model_validation_time",
  "simplified_model_storage_time",
  "reduced_model_reloading_time",
  "statistics_computation_time"
)
automotive_overview_labels <- c(
  input_model_loading_time = "Input model loading",
  boolean_feature_type_validation_time = "Boolean feature-type validation",
  initial_satisfiability_check_time = "Initial satisfiability check",
  core_feature_computation_time = "Core-feature computation",
  dead_feature_computation_time = "Dead-feature computation",
  atomic_set_computation_time = "Atomic-set computation",
  reduction_time = "Reduction",
  reduction_satisfiability_check_time = "Satisfiability check",
  reduction_original_cnf_construction_time = "Original CNF construction",
  reduction_removal_plan_computation_time = "Removal-plan computation",
  reduction_variable_projection_time = "Variable projection",
  reduction_feature_tree_rebuild_time = "Feature-tree rebuild",
  reduction_structural_model_validation_time = "Structural-model validation",
  reduction_projected_constraint_addition_time = "Projected-constraint addition",
  reduction_reduced_model_validation_time = "Reduced-model validation",
  simplified_model_storage_time = "Simplified-model storage",
  reduced_model_reloading_time = "Reduced-model reloading",
  statistics_computation_time = "Statistics computation"
)

normalise_automotive_stage <- function(stage) {
  stage <- str_remove(as.character(stage), "_seconds$")
  stage_prefix <- if_else(
    is.na(stage),
    NA_character_,
    if_else(str_ends(stage, "_time"), stage, paste0(stage, "_time"))
  )
  if_else(
    stage_prefix %in% automotive_overview_prefixes,
    stage_prefix,
    NA_character_
  )
}

automotive_plot_created <- FALSE
automotive_timeout_stage_plot_created <- FALSE
automotive_projection_time_plot_created <- FALSE
automotive_model_count <- 0L
automotive_projection_model_count <- 0L
automotive_projection_event_count <- 0L
automotive_component_timing <- NULL
automotive_timeout_stage_records <- tibble(
  input_file = character(),
  timing_prefix = character(),
  component = character(),
  component_order = integer()
)

if (!file.exists(automotive_stats_path)) {
  warning(
    "Automotive timeout plots skipped because the file was not found: ",
    automotive_stats_path,
    call. = FALSE
  )
} else {
  automotive_stats <- read_input(automotive_stats_path)

  if (nrow(automotive_stats) == 0) {
    warning(
      "Automotive timeout plots skipped because automotive-stats.csv has no rows.",
      call. = FALSE
    )
  } else {
    automotive_event_log_columns <- c(
      "sequence", "input_file", "model_status", "event", "step",
      "step_elapsed_seconds", "evaluation_elapsed_seconds",
      "reduction_elapsed_seconds"
    )
    automotive_is_event_log <- all(
      automotive_event_log_columns %in% names(automotive_stats)
    )

    if (automotive_is_event_log) {
      automotive_event_numeric_columns <- intersect(
        c(
          "sequence", "file_size_bytes", "step_elapsed_seconds",
          "evaluation_elapsed_seconds", "reduction_elapsed_seconds",
          "timeout_seconds", "original_features", "original_constraints",
          "simplified_features", "simplified_constraints",
          "feature_reduction_ratio", "constraint_reduction_ratio",
          "removed_core_features", "removed_dead_features",
          "removed_atomic_set_features", "projection_variables_completed",
          "projection_variables_total"
        ),
        names(automotive_stats)
      )
      automotive_stats <- automotive_stats %>%
        mutate(across(all_of(automotive_event_numeric_columns), to_numeric))

      automotive_timeout_events <- automotive_stats %>%
        filter(event == "model_timed_out" | model_status == "timed_out") %>%
        arrange(input_file, sequence) %>%
        group_by(input_file) %>%
        slice_tail(n = 1) %>%
        ungroup() %>%
        mutate(
          timeout_timing_prefix = normalise_automotive_stage(step)
        )

      automotive_model_count <- nrow(automotive_timeout_events)

      if (automotive_model_count == 0) {
        warning(
          paste(
            "Automotive timeout plots skipped because the event log contains",
            "no model_timed_out event or timed_out model status."
          ),
          call. = FALSE
        )
      } else {
        automotive_timeout_stage_records <- automotive_timeout_events %>%
          transmute(
            input_file,
            timing_prefix = timeout_timing_prefix,
            component = unname(
              automotive_overview_labels[timeout_timing_prefix]
            ),
            component_order = match(
              timeout_timing_prefix,
              automotive_overview_prefixes
            )
          ) %>%
          filter(!is.na(timing_prefix)) %>%
          distinct()

        unmapped_timeout_stages <- automotive_timeout_events %>%
          filter(!is.na(step), is.na(timeout_timing_prefix)) %>%
          distinct(step) %>%
          pull(step)
        if (length(unmapped_timeout_stages) > 0) {
          warning(
            "Could not map these automotive timeout stages: ",
            paste(unmapped_timeout_stages, collapse = ", "),
            call. = FALSE
          )
        }

        automotive_timeout_lookup <- automotive_timeout_events %>%
          transmute(
            input_file,
            timeout_timing_prefix,
            timeout_step = step,
            timeout_step_elapsed_seconds = step_elapsed_seconds
          )

        automotive_stage_timing <- automotive_stats %>%
          filter(input_file %in% automotive_timeout_lookup$input_file) %>%
          mutate(
            timing_prefix = normalise_automotive_stage(step),
            recorded_step_seconds = to_numeric(step_elapsed_seconds)
          ) %>%
          filter(
            !is.na(timing_prefix),
            !is.na(recorded_step_seconds),
            timing_prefix %in% setdiff(
              automotive_overview_prefixes,
              c("evaluation_time", "reduction_time")
            )
          ) %>%
          arrange(input_file, timing_prefix, sequence) %>%
          group_by(input_file, timing_prefix) %>%
          slice_tail(n = 1) %>%
          ungroup() %>%
          left_join(automotive_timeout_lookup, by = "input_file") %>%
          transmute(
            input_file,
            original_features,
            status = model_status,
            last_completed_stage = NA_character_,
            active_or_stuck_stage = timeout_step,
            timeout_timing_prefix,
            current_stage_elapsed_seconds = timeout_step_elapsed_seconds,
            timing_column = paste0(timing_prefix, "_event_seconds"),
            reported_mean_seconds = recorded_step_seconds,
            timing_prefix,
            is_timeout_stage = timing_prefix == timeout_timing_prefix,
            timeout_marker_seconds = if_else(
              is_timeout_stage,
              coalesce(
                timeout_step_elapsed_seconds,
                recorded_step_seconds
              ),
              NA_real_
            )
          )

        automotive_evaluation_timing <- automotive_timeout_events %>%
          filter(!is.na(evaluation_elapsed_seconds)) %>%
          transmute(
            input_file,
            original_features,
            status = model_status,
            last_completed_stage = NA_character_,
            active_or_stuck_stage = step,
            timeout_timing_prefix,
            current_stage_elapsed_seconds = step_elapsed_seconds,
            timing_column = "evaluation_time_event_seconds",
            reported_mean_seconds = evaluation_elapsed_seconds,
            timing_prefix = "evaluation_time",
            is_timeout_stage = timeout_timing_prefix == "evaluation_time",
            timeout_marker_seconds = if_else(
              is_timeout_stage,
              coalesce(step_elapsed_seconds, evaluation_elapsed_seconds),
              NA_real_
            )
          )

        automotive_reduction_timing <- automotive_timeout_events %>%
          filter(!is.na(reduction_elapsed_seconds)) %>%
          transmute(
            input_file,
            original_features,
            status = model_status,
            last_completed_stage = NA_character_,
            active_or_stuck_stage = step,
            timeout_timing_prefix,
            current_stage_elapsed_seconds = step_elapsed_seconds,
            timing_column = "reduction_time_event_seconds",
            reported_mean_seconds = reduction_elapsed_seconds,
            timing_prefix = "reduction_time",
            is_timeout_stage = timeout_timing_prefix == "reduction_time",
            timeout_marker_seconds = if_else(
              is_timeout_stage,
              coalesce(step_elapsed_seconds, reduction_elapsed_seconds),
              NA_real_
            )
          )

        automotive_component_timing <- bind_rows(
          automotive_evaluation_timing,
          automotive_stage_timing,
          automotive_reduction_timing
        ) %>%
          mutate(
            component = unname(
              automotive_overview_labels[timing_prefix]
            ),
            component_order = match(
              timing_prefix,
              automotive_overview_prefixes
            )
          )

      }
    } else {
      # Backward compatibility for the former one-row-per-model schema.
      if (!"input_file" %in% names(automotive_stats)) {
        automotive_stats$input_file <- paste0(
          "Automotive model ",
          seq_len(nrow(automotive_stats))
        )
      }
      automotive_model_count <- n_distinct(automotive_stats$input_file)

      automotive_numeric_columns <- intersect(
        c(
          timing_stats_columns,
          paste0(automotive_overview_prefixes, "_seconds"),
          "current_stage_elapsed_seconds"
        ),
        names(automotive_stats)
      )
      if (length(automotive_numeric_columns) > 0) {
        automotive_stats <- automotive_stats %>%
          mutate(across(all_of(automotive_numeric_columns), to_numeric))
      }

      if ("active_or_stuck_stage" %in% names(automotive_stats)) {
        automotive_stats <- automotive_stats %>%
          mutate(
            timeout_timing_prefix = normalise_automotive_stage(
              active_or_stuck_stage
            )
          )
      } else {
        automotive_stats$active_or_stuck_stage <- NA_character_
        automotive_stats$timeout_timing_prefix <- NA_character_
      }

      automotive_timeout_stage_records <- automotive_stats %>%
        transmute(
          input_file,
          timing_prefix = timeout_timing_prefix,
          component = unname(
            automotive_overview_labels[timeout_timing_prefix]
          ),
          component_order = match(
            timeout_timing_prefix,
            automotive_overview_prefixes
          )
        ) %>%
        filter(!is.na(timing_prefix)) %>%
        distinct()

      for (prefix in automotive_overview_prefixes) {
        mean_column <- paste0(prefix, "_mean_seconds")
        single_duration_column <- paste0(prefix, "_seconds")
        run_columns <- paste0(prefix, "_run_", 1:3, "_seconds")

        if (!mean_column %in% names(automotive_stats) &&
            single_duration_column %in% names(automotive_stats)) {
          automotive_stats[[mean_column]] <- to_numeric(
            automotive_stats[[single_duration_column]]
          )
        } else if (
          !mean_column %in% names(automotive_stats) &&
            all(run_columns %in% names(automotive_stats))
        ) {
          run_values <- as.matrix(
            automotive_stats[, run_columns, drop = FALSE]
          )
          calculated_means <- rowMeans(run_values, na.rm = TRUE)
          calculated_means[is.nan(calculated_means)] <- NA_real_
          automotive_stats[[mean_column]] <- calculated_means
        } else if (!mean_column %in% names(automotive_stats)) {
          automotive_stats[[mean_column]] <- NA_real_
        }

        if ("current_stage_elapsed_seconds" %in% names(automotive_stats)) {
          use_current_stage_time <-
            automotive_stats$timeout_timing_prefix == prefix &
            is.na(automotive_stats[[mean_column]]) &
            !is.na(automotive_stats$current_stage_elapsed_seconds)
          use_current_stage_time[is.na(use_current_stage_time)] <- FALSE
          automotive_stats[[mean_column]][use_current_stage_time] <-
            automotive_stats$current_stage_elapsed_seconds[
              use_current_stage_time
            ]
        }
      }

      automotive_available_prefixes <- automotive_overview_prefixes[
        vapply(
          paste0(automotive_overview_prefixes, "_mean_seconds"),
          function(column) {
            any(!is.na(automotive_stats[[column]]))
          },
          logical(1)
        )
      ]
      if (length(automotive_available_prefixes) > 0) {
        automotive_component_timing <- make_component_timing_data(
          automotive_stats,
          automotive_available_prefixes,
          automotive_overview_labels[automotive_available_prefixes]
        )
      }
    }

    if (!is.null(automotive_component_timing) &&
        any(!is.na(automotive_component_timing$reported_mean_seconds))) {
      automotive_available_prefixes <- automotive_overview_prefixes[
        automotive_overview_prefixes %in%
          unique(
            automotive_component_timing$timing_prefix[
              !is.na(automotive_component_timing$reported_mean_seconds)
            ]
          )
      ]
      automotive_component_timing <- automotive_component_timing %>%
        filter(timing_prefix %in% automotive_available_prefixes)

      automotive_reduction_component_timing <-
        automotive_component_timing %>%
        filter(timing_prefix %in% reduction_overview_prefixes) %>%
        mutate(
          component = unname(
            reduction_overview_labels[timing_prefix]
          ),
          component_order = match(
            timing_prefix,
            reduction_overview_prefixes
          )
        )

      automotive_component_timing_summary <-
        summarise_component_timing(
          automotive_reduction_component_timing
        ) %>%
        left_join(
          automotive_timeout_stage_records %>%
            count(timing_prefix, name = "timeouts_during_step"),
          by = "timing_prefix"
        ) %>%
        mutate(
          timeouts_during_step = coalesce(timeouts_during_step, 0L)
        )

      write_csv(
        automotive_component_timing_summary,
        file.path(
          table_directory,
          "automotive_reduction_processing_step_time_summary.csv"
        )
      )

      p_automotive_reduction_step_overview <- make_timing_overview_plot(
        data = automotive_reduction_component_timing,
        prefixes = reduction_overview_prefixes,
        labels = reduction_overview_labels,
        total_prefix = "reduction_time",
        file_name =
          "automotive_reduction_processing_step_time_overview.pdf",
        height = max(
          6.2,
          2.8 + 0.42 * length(reduction_overview_prefixes)
        ),
        show_individual_observations = TRUE,
        show_timeout_stages = TRUE,
        show_empty_steps = TRUE
      )
      automotive_plot_created <- TRUE

      automotive_timeout_stage_summary <- automotive_timeout_stage_records %>%
        group_by(component_order, component, timing_prefix) %>%
        summarise(timed_out_models = n_distinct(input_file)) %>%
        arrange(component_order)

      write_csv(
        automotive_timeout_stage_summary,
        file.path(
          table_directory,
          "automotive_timeout_stage_summary.csv"
        )
      )

      if (nrow(automotive_timeout_stage_summary) > 0) {
        automotive_timeout_stage_plot_data <-
          automotive_timeout_stage_summary %>%
          mutate(
            component = factor(
              component,
              levels = rev(
                unname(
                  automotive_overview_labels[
                    automotive_overview_prefixes
                  ]
                )
              )
            )
          )

        p_automotive_timeout_stage <- ggplot(
          automotive_timeout_stage_plot_data,
          aes(x = timed_out_models, y = component)
        ) +
          geom_col(width = 0.62, fill = "#D73027", alpha = 0.85) +
          geom_text(
            aes(label = timed_out_models),
            hjust = -0.25,
            color = "black",
            size = 3.8
          ) +
          scale_x_continuous(
            breaks = scales::breaks_width(1),
            expand = expansion(mult = c(0, 0.18))
          ) +
          scale_y_discrete(drop = FALSE) +
          labs(
            title = "Automotive models by timeout stage",
            subtitle = paste(
              "Each model is counted once using its final",
              "model_timed_out event."
            ),
            x = "Number of timed-out feature models",
            y = NULL
          )

        save_pdf(
          "automotive_timeout_stage_overview.pdf",
          p_automotive_timeout_stage,
          width = 8.5,
          height = max(
            4.5,
            2.8 + 0.42 * length(automotive_overview_prefixes)
          )
        )
        automotive_timeout_stage_plot_created <- TRUE
      }

      automotive_missing_prefixes <- setdiff(
        automotive_overview_prefixes,
        automotive_available_prefixes
      )
      if (length(automotive_missing_prefixes) > 0) {
        message(
          "Automotive stages shown without timing observations: ",
          paste(automotive_missing_prefixes, collapse = ", ")
        )
      }
    } else if (automotive_model_count > 0) {
      warning(
        "Automotive timeout plots skipped because no stage durations were recorded.",
        call. = FALSE
      )
    }
  }
}

if (!file.exists(automotive_projection_timings_path)) {
  warning(
    "Automotive projection-progress plot skipped because the file was not found: ",
    automotive_projection_timings_path,
    call. = FALSE
  )
} else {
  automotive_projection_timings <- read_input(
    automotive_projection_timings_path
  )

  projection_interval_column_candidates <- c(
    "time_since_previous_projections_seconds",
    "time_since_previous_projection_seconds"
  )
  projection_interval_source_column <- intersect(
    projection_interval_column_candidates,
    names(automotive_projection_timings)
  )
  if (length(projection_interval_source_column) == 0) {
    stop(
      paste(
        "automotive-projection-timings.csv must contain either",
        "time_since_previous_projection_seconds or",
        "time_since_previous_projections_seconds."
      ),
      call. = FALSE
    )
  }
  projection_interval_source_column <-
    projection_interval_source_column[[1]]
  automotive_projection_timings$time_since_previous_projection_seconds <-
    automotive_projection_timings[[projection_interval_source_column]]

  cumulative_time_column_candidates <- c(
    "cumulative_projection_time_seconds",
    "culumative_projection_time_seconds"
  )
  cumulative_time_source_column <- intersect(
    cumulative_time_column_candidates,
    names(automotive_projection_timings)
  )
  if (length(cumulative_time_source_column) == 0) {
    stop(
      paste(
        "automotive-projection-timings.csv must contain",
        "cumulative_projection_time_seconds."
      ),
      call. = FALSE
    )
  }
  cumulative_time_source_column <- cumulative_time_source_column[[1]]
  automotive_projection_timings$cumulative_projection_time_seconds <-
    automotive_projection_timings[[cumulative_time_source_column]]

  automotive_projection_required_columns <- c(
    "sequence",
    "timestamp_utc",
    "input_file",
    "projection_variables_completed",
    "projection_variables_total",
    "projections_completed_since_previous_event",
    "time_since_previous_projection_seconds",
    "cumulative_projection_time_seconds",
    "evaluation_elapsed_seconds"
  )
  assert_columns(
    automotive_projection_timings,
    automotive_projection_required_columns,
    "automotive-projection-timings.csv"
  )

  automotive_projection_numeric_columns <- c(
    "sequence",
    "projection_variables_completed",
    "projection_variables_total",
    "projections_completed_since_previous_event",
    "time_since_previous_projection_seconds",
    "cumulative_projection_time_seconds",
    "evaluation_elapsed_seconds"
  )

  automotive_projection_timings <- automotive_projection_timings %>%
    mutate(
      across(
        all_of(automotive_projection_numeric_columns),
        to_numeric
      )
    )

  invalid_projection_interval_rows <- automotive_projection_timings %>%
    filter(
      is.na(input_file) |
        !is.finite(time_since_previous_projection_seconds) |
        time_since_previous_projection_seconds < 0 |
        !is.finite(projection_variables_completed) |
        projection_variables_completed < 0
    )
  if (nrow(invalid_projection_interval_rows) > 0) {
    warning(
      scales::comma(nrow(invalid_projection_interval_rows)),
      paste(
        " automotive projection event(s) were excluded because the model,",
        "interval duration, or completed-projection count was invalid."
      ),
      call. = FALSE
    )
  }

  automotive_projection_recalculated_data <- automotive_projection_timings %>%
    filter(
      !is.na(input_file),
      is.finite(time_since_previous_projection_seconds),
      time_since_previous_projection_seconds >= 0,
      is.finite(projection_variables_completed),
      projection_variables_completed >= 0
    ) %>%
    arrange(input_file, sequence, timestamp_utc) %>%
    group_by(input_file) %>%
    mutate(
      reported_cumulative_projection_time_seconds =
        cumulative_projection_time_seconds,
      reported_evaluation_elapsed_seconds = evaluation_elapsed_seconds,
      projection_start_evaluation_elapsed_seconds = {
        reported_start_offsets <-
          reported_evaluation_elapsed_seconds -
          reported_cumulative_projection_time_seconds
        finite_start_offsets <- reported_start_offsets[
          is.finite(reported_start_offsets)
        ]
        if (length(finite_start_offsets) > 0) {
          finite_start_offsets[[1]]
        } else {
          NA_real_
        }
      },
      recalculated_cumulative_projection_time_seconds = cumsum(
        time_since_previous_projection_seconds
      ),
      recalculated_evaluation_elapsed_seconds =
        projection_start_evaluation_elapsed_seconds +
        recalculated_cumulative_projection_time_seconds,
      cumulative_projection_time_adjustment_seconds =
        recalculated_cumulative_projection_time_seconds -
        reported_cumulative_projection_time_seconds,
      evaluation_elapsed_time_adjustment_seconds =
        recalculated_evaluation_elapsed_seconds -
        reported_evaluation_elapsed_seconds,
      projection_interval_source_column =
        .env$projection_interval_source_column,
      reported_cumulative_source_column =
        .env$cumulative_time_source_column
    ) %>%
    ungroup() %>%
    mutate(
      model = str_remove(
        str_replace(input_file, "^.*[/\\\\]", ""),
        "\\.uvl$"
      )
    ) %>%
    arrange(input_file, sequence, timestamp_utc)

  non_positive_projection_duration_rows <-
    automotive_projection_recalculated_data %>%
    filter(time_since_previous_projection_seconds <= 0)
  if (nrow(non_positive_projection_duration_rows) > 0) {
    warning(
      scales::comma(nrow(non_positive_projection_duration_rows)),
      paste(
        " projection event(s) were excluded from the logarithmic plot",
        "because their interval duration was zero."
      ),
      call. = FALSE
    )
  }

  automotive_projection_plot_data <-
    automotive_projection_recalculated_data %>%
    filter(time_since_previous_projection_seconds > 0)

  automotive_projection_model_count <- n_distinct(
    automotive_projection_plot_data$input_file
  )
  automotive_projection_event_count <- nrow(
    automotive_projection_plot_data
  )

  obsolete_projection_plot_outputs <- c(
    file.path(
      plot_directory,
      "automotive_projection_progress_over_time.pdf"
    ),
    file.path(
      table_directory,
      "automotive_projection_progress_plot_data.csv"
    )
  )
  unlink(
    obsolete_projection_plot_outputs[
      file.exists(obsolete_projection_plot_outputs)
    ]
  )

  if (automotive_projection_event_count == 0) {
    warning(
      paste(
        "Automotive projection-time plot skipped because",
        paste(
          "automotive-projection-timings.csv contains no valid positive",
          "interval durations."
        )
      ),
      call. = FALSE
    )
  } else {
    non_monotonic_projection_models <- automotive_projection_plot_data %>%
      group_by(input_file) %>%
      summarise(
        completed_count_decreases = any(
          diff(projection_variables_completed) < 0
        )
      ) %>%
      filter(completed_count_decreases) %>%
      pull(input_file)

    if (length(non_monotonic_projection_models) > 0) {
      warning(
        paste(
          "Completed-projection counts are not monotonic for:",
          paste(non_monotonic_projection_models, collapse = ", ")
        ),
        call. = FALSE
      )
    }

    write_csv(
      automotive_projection_plot_data,
      file.path(
        table_directory,
        "automotive_projection_time_plot_data.csv"
      )
    )

    p_automotive_projection_time <- ggplot(
      automotive_projection_plot_data,
      aes(
        x = projection_variables_completed,
        y = time_since_previous_projection_seconds,
        color = model,
        group = input_file
      )
    ) +
      geom_line(linewidth = 0.45, alpha = 0.55) +
      geom_point(size = 0.9, alpha = 0.55) +
      scale_x_continuous(
        breaks = scales::breaks_pretty(n = 8),
        labels = scales::label_number(big.mark = ",", accuracy = 1),
        expand = expansion(mult = c(0, 0.03))
      ) +
      scale_y_log10(
        breaks = timing_axis_breaks[timing_axis_breaks > 0],
        labels = format_seconds_axis,
        expand = expansion(mult = c(0, 0.05))
      ) +
      labs(
        x = "Number of projection variables completed",
        y = "Time since previous projection (seconds)",
        color = "Automotive model"
      )

    save_pdf(
      "automotive_projection_time_by_completed_projections.pdf",
      p_automotive_projection_time,
      width = 9.5,
      height = 5.8
    )
    automotive_projection_time_plot_created <- TRUE
  }
}

timing_plot_data <- stats %>%
  filter(
    !is.na(original_features),
    !is.na(evaluation_time_mean_seconds),
    original_features >= 0,
    evaluation_time_mean_seconds >= 0
  ) %>%
  mutate(
    timing_lower_seconds = pmax(
      evaluation_time_mean_seconds - evaluation_time_standard_deviation_seconds,
      0
    ),
    timing_upper_seconds =
      evaluation_time_mean_seconds + evaluation_time_standard_deviation_seconds,
    feature_count_group = factor(
      feature_count_group,
      levels = c("1,000 features or fewer", "More than 1,000 features")
    )
  )

p_time_size <- ggplot(
  timing_plot_data,
  aes(x = original_features, y = evaluation_time_mean_seconds)
) +
  geom_vline(xintercept = 1000, color = "grey40", linetype = "dashed", linewidth = 0.6) +
  geom_errorbar(
    aes(ymin = timing_lower_seconds, ymax = timing_upper_seconds),
    color = "grey55",
    alpha = 0.3,
    width = 0,
    na.rm = TRUE
  ) +
  geom_point(aes(color = feature_count_group), alpha = 0.6, size = 1.9, na.rm = TRUE) +
  scale_x_continuous(
    trans = "log1p",
    labels = scales::label_number(big.mark = ",")
  ) +
  scale_y_continuous(
    trans = "log1p",
    breaks = c(
      0, 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 20, 25, 30,
      45, 60, 120, 300, 600, 1800, 3600
    ),
    labels = function(values) paste0(format_seconds_axis(values), " s")
  ) +
  scale_color_manual(
    values = c(
      "1,000 features or fewer" = "#2878B5",
      "More than 1,000 features" = "#D1495B"
    ),
    drop = FALSE
  ) +
  labs(
    x = "Number of features before reduction",
    y = "Mean evaluation time",
    color = NULL
  )
save_pdf("evaluation_time_by_model_size.pdf", p_time_size, width = 8.5, height = 5.5)

p_time_size_up_to_30_seconds <- p_time_size +
  coord_cartesian(ylim = c(0, 30)) +
  labs(
    title = "Complete evaluation time by initial model size (up to 30 seconds)",
    subtitle = paste(
      "This detailed view shows models with mean evaluation times up to 30 seconds.",
      "Each point is the reported mean of three complete evaluations."
    )
  )
save_pdf(
  "evaluation_time_by_model_size_up_to_30_seconds.pdf",
  p_time_size_up_to_30_seconds,
  width = 8.5,
  height = 5.5
)

p_time_per_feature <- ggplot(
  timing_plot_data,
  aes(x = original_features, y = evaluation_time_seconds_per_feature)
) +
  geom_vline(xintercept = 1000, color = "grey40", linetype = "dashed", linewidth = 0.6) +
  geom_point(aes(color = feature_count_group), alpha = 0.6, size = 1.9, na.rm = TRUE) +
  scale_x_continuous(
    trans = "log1p",
    labels = scales::label_number(big.mark = ",")
  ) +
  scale_y_continuous(
    trans = "log1p",
    labels = scales::label_number(accuracy = 0.0001, big.mark = ",", suffix = " s")
  ) +
  scale_color_manual(
    values = c(
      "1,000 features or fewer" = "#2878B5",
      "More than 1,000 features" = "#D1495B"
    ),
    drop = FALSE
  ) +
  labs(
    title = "Evaluation time per original feature",
    subtitle = paste(
      "Higher values indicate that evaluation time grows faster than the original feature count.",
      "Both axes use logarithmic spacing, and the dashed line marks 1,000 features."
    ),
    x = "Number of features before reduction",
    y = "Mean evaluation time per original feature",
    color = NULL
  )
save_pdf(
  "evaluation_time_per_feature_by_model_size.pdf",
  p_time_per_feature,
  width = 8.5,
  height = 5.5
)

p_time_variability <- ggplot(
  timing_plot_data,
  aes(x = original_features, y = evaluation_time_coefficient_of_variation)
) +
  geom_vline(xintercept = 1000, color = "grey40", linetype = "dashed", linewidth = 0.6) +
  geom_point(aes(color = feature_count_group), alpha = 0.6, size = 1.9, na.rm = TRUE) +
  scale_x_continuous(
    trans = "log1p",
    labels = scales::label_number(big.mark = ",")
  ) +
  scale_y_continuous(
    labels = scales::label_percent(accuracy = 1),
    expand = expansion(mult = c(0.02, 0.08))
  ) +
  scale_color_manual(
    values = c(
      "1,000 features or fewer" = "#2878B5",
      "More than 1,000 features" = "#D1495B"
    ),
    drop = FALSE
  ) +
  labs(
    title = "Variation between the three evaluation runs",
    subtitle = paste(
      "Variation is the standard deviation divided by the mean evaluation time.",
      "The horizontal axis uses logarithmic spacing, and the dashed line marks 1,000 features."
    ),
    x = "Number of features before reduction",
    y = "Relative variation between runs",
    color = NULL
  )
save_pdf(
  "evaluation_time_variability_by_model_size.pdf",
  p_time_variability,
  width = 8.5,
  height = 5.5
)

make_relative_change_plot <- function(
  ratio_column,
  element_label,
  color,
  file_name
) {
  plot_data <- stats %>%
    transmute(relative_change = .data[[ratio_column]])

  plot <- ggplot(plot_data, aes(x = relative_change)) +
    geom_vline(
      xintercept = 0,
      color = "grey35",
      linetype = "dashed",
      linewidth = 0.6
    ) +
    geom_histogram(
      bins = 30,
      fill = color,
      color = "white",
      alpha = 0.88,
      na.rm = TRUE
    ) +
    scale_x_continuous(labels = scales::label_percent(accuracy = 1)) +
    labs(
      title = paste("Relative change in", str_to_lower(element_label)),
      subtitle = paste(
        "Positive values mean elements were removed; negative values mean elements were added.",
        "The dashed line marks no change."
      ),
      x = "Change relative to the original feature model",
      y = "Number of feature models"
    )

  save_pdf(file_name, plot, width = 8, height = 5.2)
  plot
}

p_feature_change <- make_relative_change_plot(
  ratio_column = "feature_reduction_ratio",
  element_label = "Features",
  color = "#2878B5",
  file_name = "feature_change_distribution.pdf"
)

p_constraint_change <- make_relative_change_plot(
  ratio_column = "constraint_reduction_ratio",
  element_label = "Constraints",
  color = "#E07A1F",
  file_name = "constraint_change_distribution.pdf"
)

make_before_after_scatterplot <- function(
  original_column,
  simplified_column,
  element_label,
  color,
  file_name
) {
  plot <- ggplot(
    stats,
    aes(x = .data[[original_column]], y = .data[[simplified_column]])
  ) +
    geom_abline(
      slope = 1,
      intercept = 0,
      linetype = "dashed",
      color = "grey45"
    ) +
    geom_point(alpha = 0.55, size = 1.8, color = color, na.rm = TRUE) +
    scale_x_continuous(
      trans = "log1p",
      breaks = dense_count_axis_breaks,
      labels = scales::label_number(big.mark = ",", accuracy = 1)
    ) +
    scale_y_continuous(
      trans = "log1p",
      breaks = dense_count_axis_breaks,
      labels = scales::label_number(big.mark = ",", accuracy = 1)
    ) +
    coord_equal() +
    labs(
      title = paste(element_label, "before and after reduction"),
      subtitle = paste(
        "Each point represents one successfully processed feature model.",
        "Points below the dashed line indicate removals; points above it indicate additions.",
        "Both axes use logarithmic spacing."
      ),
      x = paste("Number of", str_to_lower(element_label), "before reduction"),
      y = paste("Number of", str_to_lower(element_label), "after reduction")
    ) +
    theme(axis.text.x = element_text(angle = 45, hjust = 1))

  save_pdf(file_name, plot, width = 7.2, height = 6.2)
  plot
}

# Remove obsolete before/after PDFs from earlier script versions. Each of the
# four replacement files below contains exactly one diagram.
obsolete_before_after_plot_files <- c(
  "model_size_before_after_boxplot.pdf",
  "model_size_before_after_scatterplot.pdf",
  "features_before_after_scatterplot.pdf",
  "constraints_before_after_scatterplot.pdf"
)
obsolete_before_after_plot_paths <- file.path(
  plot_directory,
  obsolete_before_after_plot_files
)
unlink(obsolete_before_after_plot_paths[file.exists(obsolete_before_after_plot_paths)])

p_features_before_after <- make_before_after_scatterplot(
  original_column = "original_features",
  simplified_column = "simplified_features",
  element_label = "Features",
  color = "#2878B5",
  file_name = "original_vs_simplified_features.pdf"
)

p_constraints_before_after <- make_before_after_scatterplot(
  original_column = "original_constraints",
  simplified_column = "simplified_constraints",
  element_label = "Constraints",
  color = "#E07A1F",
  file_name = "original_vs_simplified_constraints.pdf"
)

make_before_after_boxplot <- function(
  original_column,
  simplified_column,
  element_label,
  after_color,
  file_name
) {
  plot_data <- stats %>%
    transmute(
      `Before reduction` = .data[[original_column]],
      `After reduction` = .data[[simplified_column]]
    ) %>%
    pivot_longer(
      cols = everything(),
      names_to = "measurement",
      values_to = "count"
    ) %>%
    filter(is.finite(count)) %>%
    mutate(
      measurement = factor(
        measurement,
        levels = c("Before reduction", "After reduction")
      )
    )

  plot <- ggplot(
    plot_data,
    aes(x = measurement, y = count, fill = measurement)
  ) +
    geom_boxplot(
      width = 0.62,
      alpha = 0.82,
      outlier.alpha = 0.25,
      outlier.size = 1,
      na.rm = TRUE
    ) +
    scale_fill_manual(
      values = c(
        "Before reduction" = "#7A7A7A",
        "After reduction" = after_color
      )
    ) +
    scale_y_continuous(
      trans = "log1p",
      breaks = dense_count_axis_breaks,
      labels = scales::label_number(big.mark = ",", accuracy = 1)
    ) +
    labs(
      title = paste(element_label, "before and after reduction"),
      subtitle = paste(
        "Each box summarizes the distribution across successfully processed feature models.",
        "The vertical axis uses logarithmic spacing."
      ),
      x = NULL,
      y = paste("Number of", str_to_lower(element_label))
    ) +
    theme(legend.position = "none")

  save_pdf(file_name, plot, width = 7.2, height = 5.6)
  plot
}

p_features_before_after_boxplot <- make_before_after_boxplot(
  original_column = "original_features",
  simplified_column = "simplified_features",
  element_label = "Features",
  after_color = "#2878B5",
  file_name = "features_before_after_boxplot.pdf"
)

p_constraints_before_after_boxplot <- make_before_after_boxplot(
  original_column = "original_constraints",
  simplified_column = "simplified_constraints",
  element_label = "Constraints",
  after_color = "#E07A1F",
  file_name = "constraints_before_after_boxplot.pdf"
)

make_before_after_histogram <- function(
  original_column,
  simplified_column,
  element_label,
  after_color,
  file_name
) {
  plot_data <- stats %>%
    transmute(
      input_file,
      before = .data[[original_column]],
      after = .data[[simplified_column]]
    ) %>%
    filter(
      is.finite(before),
      is.finite(after),
      before >= 0,
      after >= 0
    ) %>%
    pivot_longer(
      cols = c(before, after),
      names_to = "measurement",
      values_to = "count"
    ) %>%
    mutate(
      measurement = recode(
        measurement,
        before = "Before reduction",
        after = "After reduction"
      ),
      measurement = factor(
        measurement,
        levels = c("Before reduction", "After reduction")
      ),
      quantity = element_label,
      source_column = if_else(
        measurement == "Before reduction",
        original_column,
        simplified_column
      ),
      pdf_file = file_name,
      .after = input_file
    )

  plot <- ggplot(plot_data, aes(x = count, fill = measurement)) +
    geom_histogram(
      bins = 30,
      position = "identity",
      color = "white",
      linewidth = 0.2,
      alpha = 0.55,
      na.rm = TRUE
    ) +
    scale_fill_manual(
      values = c(
        "Before reduction" = "#6F6F6F",
        "After reduction" = after_color
      )
    ) +
    scale_x_continuous(
      trans = "log1p",
      breaks = c(0, 1, 10, 100, 1000, 10000, 100000),
      labels = scales::label_number(big.mark = ",")
    ) +
    scale_y_continuous(
      breaks = scales::breaks_pretty(),
      labels = scales::label_number(big.mark = ","),
      expand = expansion(mult = c(0, 0.08))
    ) +
    labs(
      title = paste(
        "Distribution of",
        str_to_lower(element_label),
        "before and after reduction"
      ),
      subtitle = paste(
        "Both distributions use identical bins; overlapping bars are semi-transparent.",
        "The horizontal axis uses logarithmic spacing."
      ),
      x = paste("Number of", str_to_lower(element_label)),
      y = "Number of feature models",
      fill = "Model state"
    )

  built_histogram_data <- ggplot_build(plot)$data[[1]] %>%
    as_tibble()
  measurement_levels <- levels(plot_data$measurement)

  if (!all(built_histogram_data$group %in% seq_along(measurement_levels))) {
    stop(
      "Could not map calculated histogram groups to before/after measurements.",
      call. = FALSE
    )
  }

  calculated_bins <- built_histogram_data %>%
    mutate(
      quantity = element_label,
      measurement = factor(
        measurement_levels[group],
        levels = measurement_levels
      ),
      source_column = if_else(
        measurement == "Before reduction",
        original_column,
        simplified_column
      ),
      pdf_file = file_name,
      .before = 1
    ) %>%
    group_by(quantity, measurement) %>%
    arrange(xmin, .by_group = TRUE) %>%
    mutate(bin_index = row_number()) %>%
    ungroup() %>%
    transmute(
      quantity,
      measurement,
      source_column,
      pdf_file,
      bin_index,
      bin_lower_count = pmax(expm1(xmin), 0),
      bin_midpoint_count = pmax(expm1(x), 0),
      bin_upper_count = pmax(expm1(xmax), 0),
      bin_lower_log1p = xmin,
      bin_midpoint_log1p = x,
      bin_upper_log1p = xmax,
      feature_models = as.integer(count),
      density,
      count_relative_to_group_max = ncount,
      density_relative_to_group_max = ndensity,
      interval_closed = "right",
      x_transformation = "log1p"
    )

  save_pdf(file_name, plot, width = 8, height = 5.2)
  list(
    plot = plot,
    input_values = plot_data %>%
      transmute(
        input_file,
        quantity,
        measurement,
        source_column,
        pdf_file,
        value = count
      ),
    calculated_bins = calculated_bins
  )
}

obsolete_individual_histogram_files <- c(
  "original_features_histogram.pdf",
  "original_constraints_histogram.pdf",
  "simplified_features_histogram.pdf",
  "simplified_constraints_histogram.pdf"
)
obsolete_individual_histogram_paths <- file.path(
  plot_directory,
  obsolete_individual_histogram_files
)
unlink(
  obsolete_individual_histogram_paths[
    file.exists(obsolete_individual_histogram_paths)
  ]
)

features_before_after_histogram <- make_before_after_histogram(
  original_column = "original_features",
  simplified_column = "simplified_features",
  element_label = "Features",
  after_color = "#2878B5",
  file_name = "features_before_after_histogram.pdf"
)
p_features_before_after_histogram <- features_before_after_histogram$plot

constraints_before_after_histogram <- make_before_after_histogram(
  original_column = "original_constraints",
  simplified_column = "simplified_constraints",
  element_label = "Constraints",
  after_color = "#E07A1F",
  file_name = "constraints_before_after_histogram.pdf"
)
p_constraints_before_after_histogram <- constraints_before_after_histogram$plot

before_after_histogram_input_values <- bind_rows(
  features_before_after_histogram$input_values,
  constraints_before_after_histogram$input_values
) %>%
  arrange(quantity, measurement, input_file)

before_after_histogram_bin_statistics <- bind_rows(
  features_before_after_histogram$calculated_bins,
  constraints_before_after_histogram$calculated_bins
) %>%
  arrange(quantity, measurement, bin_index)

write_csv(
  before_after_histogram_input_values,
  file.path(table_directory, "before_after_histogram_input_values.csv")
)
write_csv(
  before_after_histogram_bin_statistics,
  file.path(table_directory, "before_after_histogram_bin_statistics.csv")
)

p3 <- ggplot(stats, aes(x = original_features, y = feature_reduction_ratio)) +
  geom_point(alpha = 0.45, color = "#2878B5", na.rm = TRUE) +
  geom_smooth(method = "loess", formula = y ~ x, se = TRUE, color = "#133C55", na.rm = TRUE) +
  scale_x_continuous(trans = "log1p", labels = scales::label_number(big.mark = ",")) +
  scale_y_continuous(labels = scales::label_percent(accuracy = 1), limits = c(0, 1)) +
  labs(
    title = "Feature reduction by initial model size",
    subtitle = paste(
      "Each point represents one successfully processed feature model.",
      "The horizontal axis uses logarithmic spacing."
    ),
    x = "Number of features before reduction",
    y = "Percentage of features removed"
  )
save_pdf("feature_reduction_by_model_size.pdf", p3, width = 8, height = 5)

p4 <- ggplot(removal_summary, aes(x = reorder(removal_type, total_removed), y = total_removed, fill = removal_type)) +
  geom_col(width = 0.7, show.legend = FALSE) +
  coord_flip() +
  scale_fill_manual(values = c("Core features" = "#2878B5", "Dead features" = "#D1495B", "Atomic-set features" = "#59A14F")) +
  scale_y_continuous(labels = scales::label_number(big.mark = ","), expand = expansion(mult = c(0, 0.08))) +
  labs(
    title = "Features removed by analysis type",
    subtitle = "Totals across all successfully processed feature models",
    x = NULL,
    y = "Number of features removed"
  )
save_pdf("removed_feature_composition.pdf", p4, width = 8, height = 4.5)

make_removal_plot <- function(removed_column, feature_label, color, file_name) {
  plot <- ggplot(stats, aes(x = original_features, y = .data[[removed_column]])) +
    geom_point(alpha = 0.5, size = 1.8, color = color, na.rm = TRUE) +
    scale_x_continuous(
      trans = "log1p",
      labels = scales::label_number(big.mark = ",")
    ) +
    scale_y_continuous(
      breaks = scales::breaks_pretty(),
      labels = scales::label_number(big.mark = ","),
      expand = expansion(mult = c(0.02, 0.08))
    ) +
    labs(
      title = paste(feature_label, "removed by initial model size"),
      subtitle = paste(
        "Each point represents one successfully processed feature model.",
        "The horizontal axis uses logarithmic spacing."
      ),
      x = "Number of features before reduction",
      y = paste("Number of", str_to_lower(feature_label), "removed")
    )
  
  save_pdf(file_name, plot, width = 8, height = 5)
  plot
}

p_core <- make_removal_plot(
  removed_column = "removed_core_features",
  feature_label = "Core features",
  color = "#2878B5",
  file_name = "core_features_removed_by_model_size.pdf"
)

p_dead <- make_removal_plot(
  removed_column = "removed_dead_features",
  feature_label = "Dead features",
  color = "#D1495B",
  file_name = "dead_features_removed_by_model_size.pdf"
)

p_atomic <- make_removal_plot(
  removed_column = "removed_atomic_set_features",
  feature_label = "Atomic-set features",
  color = "#59A14F",
  file_name = "atomic_set_features_removed_by_model_size.pdf"
)

removed_features_by_type <- stats %>%
  select(
    input_file,
    original_features,
    constraints_added,
    removed_core_features,
    removed_dead_features,
    removed_atomic_set_features
  ) %>%
  pivot_longer(
    cols = c(
      removed_core_features,
      removed_dead_features,
      removed_atomic_set_features
    ),
    names_to = "removal_column",
    values_to = "features_removed"
  ) %>%
  mutate(
    removal_type = recode(
      removal_column,
      removed_core_features = "Core features",
      removed_dead_features = "Dead features",
      removed_atomic_set_features = "Atomic-set features"
    ),
    removal_type = factor(
      removal_type,
      levels = c(
        "Core features",
        "Dead features",
        "Atomic-set features"
      )
    )
  )

p_removed_features_by_model_size_combined <- ggplot(
  removed_features_by_type,
  aes(
    x = original_features,
    y = features_removed,
    color = removal_type
  )
) +
  geom_point(alpha = 0.5, size = 1.8, na.rm = TRUE) +
  facet_wrap(~ removal_type, ncol = 1, scales = "free_y") +
  scale_x_continuous(
    trans = "log1p",
    breaks = dense_count_axis_breaks,
    labels = scales::label_number(big.mark = ",", accuracy = 1)
  ) +
  scale_y_continuous(
    breaks = scales::breaks_pretty(),
    labels = scales::label_number(big.mark = ","),
    expand = expansion(mult = c(0.02, 0.08))
  ) +
  scale_color_manual(
    values = c(
      "Core features" = "#2878B5",
      "Dead features" = "#D1495B",
      "Atomic-set features" = "#59A14F"
    ),
    guide = "none"
  ) +
  labs(
    title = "Features removed by initial model size and analysis type",
    subtitle = paste(
      "Each point represents one successfully processed feature model.",
      "The horizontal axis uses logarithmic spacing."
    ),
    x = "Number of features before reduction",
    y = "Number of features removed"
  ) +
  theme(axis.text.x = element_text(angle = 45, hjust = 1))

save_pdf(
  "features_removed_by_model_size_combined.pdf",
  p_removed_features_by_model_size_combined,
  width = 8.5,
  height = 10
)

make_constraints_added_plot <- function(
  x_column,
  x_label,
  title,
  file_name,
  color = "#E07A1F"
) {
  plot <- ggplot(stats, aes(x = .data[[x_column]], y = constraints_added)) +
    geom_point(alpha = 0.5, size = 1.8, color = color, na.rm = TRUE) +
    scale_x_continuous(
      trans = "log1p",
      labels = scales::label_number(big.mark = ",")
    ) +
    scale_y_continuous(
      breaks = scales::breaks_pretty(),
      labels = scales::label_number(big.mark = ","),
      expand = expansion(mult = c(0.02, 0.08))
    ) +
    labs(
      title = title,
      subtitle = paste(
        "Each point represents one successfully processed feature model.",
        "Net additions are simplified minus original constraints; values below zero are shown as zero.",
        "The horizontal axis uses logarithmic spacing."
      ),
      x = x_label,
      y = "Number of constraints added"
    )

  save_pdf(file_name, plot, width = 8, height = 5)
  plot
}

p_constraints_by_model_size <- make_constraints_added_plot(
  x_column = "original_features",
  x_label = "Number of features before reduction",
  title = "Constraints added by initial model size",
  file_name = "constrains_added_by_model_size.pdf"
)

p_constraints_by_atomic_sets <- make_constraints_added_plot(
  x_column = "removed_atomic_set_features",
  x_label = "Number of atomic-set features removed",
  title = "Constraints added and atomic-set features removed",
  file_name = "constrained_added_by_atomicSets_removed.pdf",
  color = "#59A14F"
)

p_constraints_by_dead_features <- make_constraints_added_plot(
  x_column = "removed_dead_features",
  x_label = "Number of dead features removed",
  title = "Constraints added and dead features removed",
  file_name = "constrained_added_by_deadFeatures_removed.pdf",
  color = "#D1495B"
)

p_constraints_by_core_features <- make_constraints_added_plot(
  x_column = "removed_core_features",
  x_label = "Number of core features removed",
  title = "Constraints added and core features removed",
  file_name = "constrained_added_by_coreFeatures_removed.pdf",
  color = "#2878B5"
)

p_constraints_added_by_removed_feature_type <- ggplot(
  removed_features_by_type,
  aes(
    x = features_removed,
    y = constraints_added,
    color = removal_type
  )
) +
  geom_point(alpha = 0.5, size = 1.8, na.rm = TRUE) +
  facet_wrap(~ removal_type, ncol = 1, scales = "free_x") +
  scale_x_continuous(
    trans = "log1p",
    breaks = dense_count_axis_breaks,
    labels = scales::label_number(big.mark = ",", accuracy = 1)
  ) +
  scale_y_continuous(
    breaks = scales::breaks_pretty(),
    labels = scales::label_number(big.mark = ","),
    expand = expansion(mult = c(0.02, 0.08))
  ) +
  scale_color_manual(
    values = c(
      "Core features" = "#2878B5",
      "Dead features" = "#D1495B",
      "Atomic-set features" = "#59A14F"
    ),
    guide = "none"
  ) +
  labs(
    title = "Constraints added by removed feature type",
    subtitle = paste(
      "Each point represents one successfully processed feature model.",
      paste(
        "Net additions are simplified minus original constraints;",
        "values below zero are shown as zero."
      ),
      "The horizontal axes use logarithmic spacing."
    ),
    x = "Number of features removed",
    y = "Number of constraints added"
  ) +
  theme(axis.text.x = element_text(angle = 45, hjust = 1))

save_pdf(
  "constraints_added_by_removed_feature_type.pdf",
  p_constraints_added_by_removed_feature_type,
  width = 8.5,
  height = 10
)

if (nrow(failure_summary) > 0) {
  p5 <- failure_summary %>%
    slice_max(failures, n = 15, with_ties = FALSE) %>%
    ggplot(aes(x = reorder(reason, failures), y = failures)) +
    geom_col(fill = "#D1495B", width = 0.7) +
    coord_flip() +
    scale_y_continuous(breaks = scales::breaks_pretty(), expand = expansion(mult = c(0, 0.08))) +
    labs(
      x = NULL,
      y = "Number of failed reductions"
    )
  save_pdf("failure_reasons.pdf", p5, width = 9, height = 6)
}

outlier_bounds <- stats %>%
  summarise(
    feature_lower = quantile(feature_reduction_ratio, 0.25, na.rm = TRUE) -
      1.5 * IQR(feature_reduction_ratio, na.rm = TRUE),
    feature_upper = quantile(feature_reduction_ratio, 0.75, na.rm = TRUE) +
      1.5 * IQR(feature_reduction_ratio, na.rm = TRUE),
    constraint_lower = quantile(constraint_reduction_ratio, 0.25, na.rm = TRUE) -
      1.5 * IQR(constraint_reduction_ratio, na.rm = TRUE),
    constraint_upper = quantile(constraint_reduction_ratio, 0.75, na.rm = TRUE) +
      1.5 * IQR(constraint_reduction_ratio, na.rm = TRUE)
  )

outliers <- stats %>%
  filter(
    feature_reduction_ratio < outlier_bounds$feature_lower |
      feature_reduction_ratio > outlier_bounds$feature_upper |
      constraint_reduction_ratio < outlier_bounds$constraint_lower |
      constraint_reduction_ratio > outlier_bounds$constraint_upper
  ) %>%
  select(
    input_file, original_features, simplified_features, feature_reduction_ratio,
    original_constraints, simplified_constraints, constraint_reduction_ratio
  )
write_csv(outliers, file.path(table_directory, "reduction_ratio_outliers_iqr.csv"))

slowest_models <- stats %>%
  filter(!is.na(evaluation_time_mean_seconds)) %>%
  arrange(desc(evaluation_time_mean_seconds)) %>%
  select(
    input_file,
    output_file,
    original_features,
    original_constraints,
    feature_count_group,
    all_of(evaluation_run_columns),
    evaluation_time_mean_seconds,
    evaluation_time_standard_deviation_seconds,
    evaluation_time_seconds_per_feature,
    evaluation_time_coefficient_of_variation
  ) %>%
  slice_head(n = 25)
write_csv(
  slowest_models,
  file.path(table_directory, "evaluation_time_25_slowest_models.csv")
)

most_variable_timing <- stats %>%
  filter(!is.na(evaluation_time_coefficient_of_variation)) %>%
  arrange(desc(evaluation_time_coefficient_of_variation)) %>%
  select(
    input_file,
    original_features,
    original_constraints,
    feature_count_group,
    all_of(evaluation_run_columns),
    evaluation_time_mean_seconds,
    evaluation_time_standard_deviation_seconds,
    evaluation_time_coefficient_of_variation
  ) %>%
  slice_head(n = 25)
write_csv(
  most_variable_timing,
  file.path(table_directory, "evaluation_time_25_most_variable_models.csv")
)

cat("\nFEATURE-MODEL REDUCTION ANALYSIS\n")
cat("================================\n")
cat("Successful reductions:", scales::comma(nrow(stats)), "\n")
cat("Failed reductions:    ", scales::comma(nrow(failures)), "\n")
cat("Success rate:         ", scales::percent(attempt_overview$success_rate, accuracy = 0.1), "\n\n")
cat("Models with 1,000 features or fewer:", scales::comma(nrow(stats_less_or_equal_1000)), "\n")
cat("Models with more than 1,000 features:", scales::comma(nrow(stats_more_than_1000)), "\n\n")

cat("Feature and constraint counts before and after reduction by original model size:\n")
cat(
  "Change is calculated per model as after minus before; positive values mean additions,",
  "and negative values mean removals.\n"
)
print(
  before_after_count_summary_by_size %>%
    mutate(
      across(
        c(mean, median, standard_deviation),
        ~ round(.x, digits = 3)
      )
    ),
  n = nrow(before_after_count_summary_by_size),
  width = Inf
)
cat("\n")

cat("Mean feature reduction:   ",
    scales::percent(mean(stats$feature_reduction_ratio, na.rm = TRUE), accuracy = 0.1), "\n")
cat("Median feature reduction: ",
    scales::percent(median(stats$feature_reduction_ratio, na.rm = TRUE), accuracy = 0.1), "\n")
cat("Mean constraint reduction:   ",
    scales::percent(mean(stats$constraint_reduction_ratio, na.rm = TRUE), accuracy = 0.1), "\n")
cat("Median constraint reduction: ",
    scales::percent(median(stats$constraint_reduction_ratio, na.rm = TRUE), accuracy = 0.1), "\n\n")

cat("Mean complete evaluation time:   ",
    scales::number(mean(stats$evaluation_time_mean_seconds, na.rm = TRUE), accuracy = 0.001),
    " seconds\n")
cat("Median complete evaluation time: ",
    scales::number(median(stats$evaluation_time_mean_seconds, na.rm = TRUE), accuracy = 0.001),
    " seconds\n")
cat("Mean evaluation time (<= 1,000 features): ",
    scales::number(
      mean(stats_less_or_equal_1000$evaluation_time_mean_seconds, na.rm = TRUE),
      accuracy = 0.001
    ),
    " seconds\n")
cat("Mean evaluation time (> 1,000 features):  ",
    scales::number(
      mean(stats_more_than_1000$evaluation_time_mean_seconds, na.rm = TRUE),
      accuracy = 0.001
    ),
    " seconds\n\n")

cat("Processing steps ordered by mean time across feature models:\n")
print(
  evaluation_component_timing_summary %>%
    filter(timing_prefix != "evaluation_time") %>%
    arrange(desc(mean_seconds)) %>%
    select(component, feature_models, mean_seconds, median_seconds),
  n = nrow(evaluation_component_timing_summary) - 1
)
cat("\n")

cat(
  "Reduction processing-step plot source: ",
  reduction_timing_source_name,
  "\n\n",
  sep = ""
)

if (automotive_plot_created) {
  cat(
    "Automotive timeout models plotted: ",
    scales::comma(automotive_model_count),
    "\nAutomotive reduction plot: ",
    file.path(
      plot_directory,
      "automotive_reduction_processing_step_time_overview.pdf"
    ),
    "\n\n",
    sep = ""
  )
}

if (automotive_timeout_stage_plot_created) {
  cat(
    "Automotive timeout-stage plot: ",
    file.path(
      plot_directory,
      "automotive_timeout_stage_overview.pdf"
    ),
    "\n\n",
    sep = ""
  )
}

if (automotive_projection_time_plot_created) {
  cat(
    "Automotive projection events plotted: ",
    scales::comma(automotive_projection_event_count),
    " across ",
    scales::comma(automotive_projection_model_count),
    " model(s)\nAutomotive projection-time plot: ",
    file.path(
      plot_directory,
      "automotive_projection_time_by_completed_projections.pdf"
    ),
    "\n\n",
    sep = ""
  )
}

cat("Top failure reasons:\n")
print(failure_summary %>% slice_head(n = 10), n = 10)
cat("\nSelected Spearman correlations (BH-adjusted p-values):\n")
print(correlations, n = nrow(correlations))
cat("\nResults written to:", normalizePath(output_directory, winslash = "/", mustWork = FALSE), "\n")

manifest <- tibble(
  output = c(
    list.files(table_directory, full.names = TRUE),
    list.files(plot_directory, full.names = TRUE)
  ),
  type = if_else(str_ends(output, ".csv"), "table", "plot")
) %>%
  mutate(output = normalizePath(output, winslash = "/", mustWork = FALSE))
write_csv(manifest, file.path(output_directory, "analysis_manifest.csv"))
