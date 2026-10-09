# FeatJAR-feature-model-assistance

This repository contains classes for conveniently using typical functionality that was available under FeatureIDE.

## License

This repository belongs to [FeatJAR](https://github.com/FeatureIDE/FeatJAR), a collection of Java libraries for feature-oriented software development.
FeatJAR is released under the GNU Lesser General Public License v3.0.

## Evaluation timing statistics

`evaluate-simplifier` evaluates every model three times and writes `stats.csv` next to the configured output directory. Each timing prefix below produces five columns:

- `<prefix>_time_run_1_seconds`
- `<prefix>_time_run_2_seconds`
- `<prefix>_time_run_3_seconds`
- `<prefix>_time_mean_seconds`
- `<prefix>_time_standard_deviation_seconds`

All values are wall-clock seconds measured with `System.nanoTime()`. Optional computations have a value of zero when their corresponding simplification option is disabled.

| Timing prefix | Measured operation |
| --- | --- |
| `input_model_loading` | Parse the input UVL model. |
| `boolean_feature_type_validation` | Validate that all input features are Boolean. |
| `initial_satisfiability_check` | Check the loaded model before collecting reduction information. |
| `core_feature_computation` | Compute core features for the removal statistics. |
| `dead_feature_computation` | Compute dead features for the removal statistics. |
| `atomic_set_computation` | Compute atomic-set features for the removal statistics. |
| `reduction` | Run the complete reduction pipeline. This encloses all `reduction_*` timings below. |
| `simplified_model_storage` | Serialize the reduced model as UVL. |
| `reduced_model_reloading` | Parse the persisted reduced UVL model used for the result statistics. |
| `statistics_computation` | Compare the loaded input with the reloaded reduced model and calculate counts and ratios. |
| `evaluation` | Complete end-to-end evaluation of one run. |

The reduction pipeline is split into these non-overlapping phases:

| Timing prefix | Measured reduction phase |
| --- | --- |
| `reduction_satisfiability_check` | Check satisfiability at the reducer boundary. |
| `reduction_original_cnf_construction` | Transform the original feature model into CNF. |
| `reduction_removal_plan_computation` | Compute substitutions, representatives, and the reduced propositional expression. |
| `reduction_variable_projection` | Existentially project removed variables with the CNF slicer. |
| `reduction_feature_tree_rebuild` | Clone and structurally rebuild the feature tree. |
| `reduction_structural_model_validation` | Encode the rebuilt structure and check that it does not exclude projected configurations. |
| `reduction_projected_constraint_addition` | Add projected clauses not entailed by the structural model. |
| `reduction_reduced_model_validation` | Check semantic equivalence between the final reduced model and the projection. |

`reduction` overlaps its detailed phases by design, and `evaluation` overlaps all top-level phases. The detailed reduction phases do not overlap each other, so they can be compared directly to locate scaling bottlenecks. `stats-meta.csv` also contains an across-model aggregate for every per-model mean timing.

## Automotive timeout diagnosis

Run the `main` method of `de.featjar.featureide.cli.EvalFailedModels` directly from the IDE to evaluate the five automotive models excluded from the regular evaluation. It takes no command-line arguments and locates the `feature-model-assistance` module from the working directory.

The diagnostic uses the same enabled core/dead and atomic-set reduction as `evaluate-simplifier`, with a default timeout of 3,600 seconds (one hour) per model. Models and their steps execute sequentially; no models are evaluated in parallel. A single worker thread is retained so that the controlling main thread can interrupt a blocking analysis when its timeout expires.

It writes reduced models and `automotive-stats.csv` to:

```text
src/main/resources/output/automotive_scaling/
```

The CSV is a chronological event log, not one mutable summary row per model. Every event is assigned a global `sequence` number and persisted atomically before execution continues. A step produces `step_started` followed by `step_completed`; reduction and model boundaries have their own events. If a timeout expires, `model_timed_out` records the active step and its elapsed time before the worker is interrupted. If the process is stopped manually, the final persisted `step_started` row identifies where it was running.

Important diagnostic columns are:

| Column | Meaning |
| --- | --- |
| `sequence` | Global execution order across all models and steps. |
| `timestamp_utc` | Time at which the event was persisted. |
| `model_status` | `running`, `completed`, `timed_out`, `failed`, or `missing`. |
| `event` | `model_started`, `step_started`, `step_completed`, `reduction_started`, `reduction_completed`, or a terminal model event. |
| `step` | Step associated with the event. On `model_timed_out`, this is the step that exceeded the timeout. |
| `step_elapsed_seconds` | Completed step duration, or time spent in the step when timeout/failure occurred. |
| `file_size_bytes`, `original_features`, `original_constraints` | Model-size dimensions for scaling comparisons. Counts become available immediately after loading. |
| `projection_variables_completed`, `projection_variables_total` | Latest progress reported by the CNF projection. |
| `evaluation_elapsed_seconds`, `reduction_elapsed_seconds` | Total diagnostic and reduction wall-clock times at the event. |
| `message` | Timeout or failure explanation for terminal events. |

For short smoke runs or a different output location, the defaults can optionally be overridden with JVM properties `featjar.automotiveEvaluation.timeoutSeconds` and `featjar.automotiveEvaluation.outputDirectory`. These are not required for the normal IDE run.

## Thorough evaluation

Run the `main` method of `de.featjar.featureide.cli.EvalThorough` directly from the IDE. It takes no command-line arguments and evaluates the bundled UVLHub corpus in sorted path order, excluding only these five known automotive timeout models:

- `dataset_8/uvl/automotive2_4.uvl`
- `dataset_9/uvl/automotive02_01.uvl`
- `dataset_9/uvl/automotive02_02.uvl`
- `dataset_9/uvl/automotive02_03.uvl`
- `dataset_9/uvl/automotive02_04.uvl`

Models are processed sequentially. Every individual loading, analysis, reduction, storage, reload, and statistics step has a fresh five-minute timeout; the complete processing of one model may therefore take longer than five minutes. The main thread supervises one worker thread so it can interrupt the active step when that limit is reached.

The evaluation appends each event immediately to:

```text
src/main/resources/output/uvlhub_bulk_2026_08_18/stats-thorough.csv
```

Reduced models are written below `OUTPUT_THOROUGH/` in that same directory, preserving their corpus-relative paths. The CSV uses one global `sequence` column, so `model_started`, `step_started`, `step_completed`, reduction-boundary, and terminal events appear in processing order. A `step_timed_out` or `model_failed` row contains the active `step` and its elapsed time, while the projection progress columns preserve the most recent slicer progress.

The normal run needs no configuration. JVM properties `featjar.thoroughEvaluation.inputDirectory`, `featjar.thoroughEvaluation.outputDirectory`, `featjar.thoroughEvaluation.stepTimeoutMillis`, and `featjar.thoroughEvaluation.maxModels` are available for isolated smoke runs; `maxModels=0` means all eligible models.
