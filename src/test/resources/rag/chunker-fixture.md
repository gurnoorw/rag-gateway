# Running batch work on a cluster

This fixture is written for the chunker tests (t09). It has headings at three levels, short and long paragraphs, a list, a code block, and sentences with abbreviations and version numbers, so a splitter that cuts on every full stop will fail. It contains no single sentence longer than the default chunk size.

## Why batch work is different

A web service runs until someone stops it. A batch task runs until it finishes, and then it should stop using resources. The scheduler has to know the difference.

A batch controller tracks how many runs succeeded. When the count reaches the target, the controller stops creating new runs. If a run fails, the controller can start a replacement, up to a retry limit.

## Configuring retries and deadlines

The retry limit counts failed runs, not failed containers. A run that fails because its node was drained is usually counted as well, e.g. when the node is replaced during an upgrade. Version 1.2 of the example tool changed this rule, so read the release notes before you rely on it. The deadline is a separate setting: it limits the wall-clock time of the whole task, across every retry, and it wins over the retry limit when both apply. A task that hits its deadline is marked failed even if retries remain, and its running work is stopped. Dr. Smith's team measured that most failed runs in their cluster came from out-of-memory kills rather than application errors, i.e. from limits that were set too low, so they raised the memory limit before they raised the retry limit. That order matters: more retries of a run that cannot fit in memory only waste time and capacity. Each retry also waits a little longer than the one before it, starting at ten seconds and doubling up to six minutes, which spreads retries out when many runs fail together. Logs of failed runs are kept until the task itself is deleted, which makes it possible to compare the first failure with the last one. A cleanup setting can delete finished tasks automatically after a number of seconds, and it applies to both successful and failed tasks. Teams that need the logs for longer should copy them out before the cleanup runs, for example to object storage. The controller does not guarantee that two runs never overlap: a replacement can start while the old run is still shutting down. Work that must not run twice at the same time needs its own lock, such as a lease in a database. The same caution applies to output files, which should be written under a name that includes the run's id and then renamed when complete.

### Choosing a retry limit

Start with a small number, such as three. Raise it only when failures are caused by something outside the task, like a flaky network. If the same input fails every time, more retries will not help.

### Choosing a deadline

Measure how long a normal run takes. Set the deadline to a few times that value, so a slow run still finishes but a stuck run is stopped.

## Running work in parallel

Some tasks split into independent pieces. The controller can run several pieces at once and count each success. The useful settings are:

- the number of successes needed to finish the task;
- the number of runs allowed at the same time;
- whether each run receives a fixed index, from zero up to the count minus one.

With fixed indexes, each run reads its index from an environment variable and processes its own slice of the input. Without them, the runs pull work from a shared queue until the queue is empty.

## An example definition

The definition below runs five pieces, two at a time, and gives up after three failed runs or ten minutes.

```yaml
kind: BatchTask
spec:
  completions: 5
  parallelism: 2
  retryLimit: 3
  deadlineSeconds: 600
```

Apply it, then watch the runs start. Two runs begin at once. When one finishes, the next one starts, until five have succeeded.

## Cleaning up

Finished tasks stay in the system until they are deleted. Delete them by hand, or set the cleanup time so they are removed automatically. Deleting a task also deletes its runs and their logs.

## Summary

Batch work needs a target, a retry limit and a deadline. Set the memory limit from measurements first, keep retries low, and make the work safe to run twice.
