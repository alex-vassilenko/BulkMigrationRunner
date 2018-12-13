## BulkMigrationRunner

## Use-case: Index Migration
Migrate a series of Elasticsearch indices between clusters in different locations.

## Anatomy of the problem
1. Physical distance
2. Busy network with constant failures
3. 1 to 1 mapping
4. 25 Indices
5. 2.7 Tb worth of data
6. Reindex API was not an option

## Architectural proposal
1. Single threaded java application scrolls 1 index and writes it to the new cluster.
2. Application implements `wait and retry` and `last resort` logic for failures.
3. Multiple containers run on Kubernetes namespace to parallelise execution.
4. Reach the maximum optimal utilization of network resources.
