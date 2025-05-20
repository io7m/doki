/*
 * Copyright © 2025 Mark Raynsford <code@io7m.com> https://www.io7m.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class Doki
{
  private static final Logger LOGGER;
  private static volatile String JOB_CURRENT = "";
  private static final Pattern VALID_NAME =
    Pattern.compile("[a-z_0-9]{0,64}");

  static {
    System.setProperty(
      "java.util.logging.SimpleFormatter.format",
      "[%4$s] %5$s %n"
    );
    LOGGER = Logger.getLogger("Doki");
  }

  private final SyncConfiguration configuration;
  private Map<String, Duration> durations = new HashMap<>();
  private boolean failed;

  private Doki(
    final SyncConfiguration inConfiguration)
  {
    this.configuration =
      Objects.requireNonNull(inConfiguration, "configuration");
  }

  public static void main(
    final String[] args)
    throws Exception
  {
    if (args.length != 1) {
      LOGGER.info("Usage: file");
      System.exit(1);
      return;
    }

    checkSSH();
    checkRsync();

    final var file =
      Paths.get(args[0]);
    final var properties =
      new Properties();

    try (final var stream = Files.newInputStream(file)) {
      properties.load(stream);
    }

    final var configuration =
      SyncConfiguration.create(properties);
    final var tool =
      new Doki(configuration);

    tool.execute();
    System.exit(tool.failed ? 1 : 0);
  }

  private static void checkRsync()
    throws Exception
  {
    final var proc =
      new ProcessBuilder()
        .command(List.of("rsync", "--version"))
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start();

    final var f0 =
      logProcessError("rsync", proc);
    final var f1 =
      logProcessOutput("rsync", proc);

    proc.waitFor(5L, TimeUnit.SECONDS);

    if (proc.exitValue() != 0) {
      LOGGER.severe("[%s] Unable to execute rsync.".formatted(JOB_CURRENT));
    }

    LOGGER.info("[%s] rsync appears to be functional.".formatted(JOB_CURRENT));
    CompletableFuture.allOf(f0, f1).get();
  }

  private static void checkSSH()
    throws Exception
  {
    final var proc =
      new ProcessBuilder()
        .command(List.of("ssh", "-V"))
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start();

    final var f0 =
      logProcessError("ssh", proc);
    final var f1 =
      logProcessOutput("ssh", proc);

    proc.waitFor(5L, TimeUnit.SECONDS);

    if (proc.exitValue() != 0) {
      LOGGER.severe("Unable to execute ssh.");
    }

    LOGGER.info("ssh appears to be functional.");
    CompletableFuture.allOf(f0, f1).get();
  }

  private static CompletableFuture<Void> logProcessOutput(
    final String name,
    final Process proc)
  {
    final var future = new CompletableFuture<Void>();
    Thread.ofVirtual().start(() -> {
      try (final var output =
             new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
        for (final var line : output.lines().toList()) {
          LOGGER.info("[%s] %s (stdout): %s"
                        .formatted(JOB_CURRENT, name, line));
        }
        future.complete(null);
      } catch (final Throwable e) {
        LOGGER.severe("[%s] Failed to read stdout: %s"
                        .formatted(JOB_CURRENT, e));
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  private static CompletableFuture<Void> logProcessError(
    final String name,
    final Process proc)
  {
    final var future = new CompletableFuture<Void>();
    Thread.ofVirtual().start(() -> {
      try (final var error =
             new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
        for (final var line : error.lines().toList()) {
          LOGGER.info(
            "[%s] %s (stderr): %s".formatted(JOB_CURRENT, name, line));
        }
        future.complete(null);
      } catch (final Throwable e) {
        LOGGER.severe("[%s] Failed to read stderr: %s"
                        .formatted(JOB_CURRENT, e));
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  private void execute()
  {
    final var jobsShuffled = new ArrayList<>(this.configuration.jobs.keySet());
    Collections.shuffle(jobsShuffled);

    for (final var jobName : jobsShuffled) {
      this.executeJob(this.configuration.jobs.get(jobName));
    }
  }

  private void executeJob(
    final SyncJob value)
  {
    try {
      JOB_CURRENT = value.name;
      LOGGER.info("[%s] Executing".formatted(JOB_CURRENT));
      this.runRsyncForJob(value);
      final var metricsFile = this.saveMetricsForJob(value);
      this.runCopyMetricsFile(value, metricsFile);
    } catch (final Throwable e) {
      LOGGER.severe("[%s] Failed: %s".formatted(JOB_CURRENT, e));
      this.failed = true;
    }
  }

  private void runCopyMetricsFile(
    final SyncJob job,
    final Path metricsFile)
    throws Exception
  {
    final var commands = new ArrayList<String>();
    commands.add("scp");
    commands.add("-o");
    commands.add("BatchMode=yes");
    commands.add(metricsFile.toString());
    commands.add(job.targetHost + ":" + job.targetMetricsDir);

    LOGGER.info("[%s] Executing: %s".formatted(JOB_CURRENT, commands));

    final var proc =
      new ProcessBuilder()
        .command(commands)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start();

    final var f0 =
      logProcessError("scp", proc);
    final var f1 =
      logProcessOutput("scp", proc);

    proc.waitFor();

    if (proc.exitValue() != 0) {
      LOGGER.severe("[%s] scp failed!".formatted(JOB_CURRENT));
      throw new IOException("scp failed.");
    }

    CompletableFuture.allOf(f0, f1).get();
  }

  private Path saveMetricsForJob(
    final SyncJob value)
    throws IOException
  {
    final var duration =
      this.durations.getOrDefault(value.name, Duration.ZERO);

    final var now =
      OffsetDateTime.now(ZoneId.of("UTC"));

    final var text = new StringBuilder(256);
    text.append(
      "# HELP backup_time_last The last time a backup was received\n");
    text.append(
      "# TYPE backup_time_last gauge\n");
    text.append(
      "backup_time_last{host=\"%s\",directory=\"%s\"} %s\n"
        .formatted(
          this.configuration.host,
          value.sourceDir,
          Long.toUnsignedString(now.toEpochSecond())
        )
    );

    text.append(
      "# HELP backup_duration_last The duration of the last backup\n");
    text.append(
      "# TYPE backup_duration_last gauge\n");
    text.append(
      "backup_duration_last{host=\"%s\",directory=\"%s\"} %s\n"
        .formatted(
          this.configuration.host,
          value.sourceDir,
          Long.toUnsignedString(duration.getSeconds())
        )
    );

    final var fileName =
      "backup_%s_%s.prom".formatted(this.configuration.host, value.name);

    final var outputFile =
      value.sourceMetricsDir.resolve(fileName)
        .toAbsolutePath();
    final var outputFileTemp =
      value.sourceMetricsDir.resolve(fileName + ".tmp")
        .toAbsolutePath();

    Files.writeString(
      outputFileTemp,
      text.toString(),
      WRITE,
      TRUNCATE_EXISTING,
      CREATE
    );
    Files.move(
      outputFileTemp,
      outputFile,
      REPLACE_EXISTING,
      ATOMIC_MOVE
    );
    return outputFile;
  }

  private void runRsyncForJob(
    final SyncJob job)
    throws Exception
  {
    Exception exception = null;

    for (int index = 0; index < 3; ++index) {
      try {
        this.runRsyncForJobOnce(job);
        return;
      } catch (final Exception e) {
        LOGGER.severe("[%s] rsync failed: %s".formatted(JOB_CURRENT, e));

        if (exception == null) {
          exception = e;
        } else {
          exception.addSuppressed(e);
        }

        try {
          LOGGER.info("[%s] Pausing for retry...".formatted(JOB_CURRENT));
          Thread.sleep(3_000L);
        } catch (final InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }

    LOGGER.severe("[%s] Gave up retrying backup job.".formatted(JOB_CURRENT));
    throw exception;
  }

  private void runRsyncForJobOnce(
    final SyncJob job)
    throws Exception
  {
    final var timeThen = OffsetDateTime.now();

    final var commands = new ArrayList<String>();
    commands.add("rsync");
    commands.add("--archive");
    commands.add("--verbose");
    commands.add("--compress-level=9");
    commands.add("--delete");
    commands.add("--hard-links");
    commands.add("--sparse");
    commands.add("--progress");
    commands.add("-e");
    commands.add("ssh -o BatchMode=yes");

    if (this.configuration.dryRun()) {
      commands.add("--dry-run");
    }

    commands.add(job.sourceDir);
    commands.add(job.targetHost + ":" + job.targetDir + "/");

    LOGGER.info("[%s] Executing: %s".formatted(JOB_CURRENT, commands));

    final var proc =
      new ProcessBuilder()
        .command(commands)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start();

    final var f0 =
      logProcessError("rsync", proc);
    final var f1 =
      logProcessOutput("rsync", proc);

    proc.waitFor();

    if (proc.exitValue() != 0) {
      LOGGER.severe("[%s] Rsync failed!".formatted(JOB_CURRENT));
      throw new IOException("Rsync failed.");
    }

    CompletableFuture.allOf(f0, f1).get();

    final var timeNow = OffsetDateTime.now();
    this.durations.put(job.name, Duration.between(timeThen, timeNow));
  }

  private record SyncConfiguration(
    boolean dryRun,
    String host,
    Map<String, SyncJob> jobs)
  {
    SyncConfiguration
    {
      Objects.requireNonNull(host, "host");

      if (!VALID_NAME.matcher(host).matches()) {
        throw new IllegalArgumentException(
          "Host names must match the pattern %s".formatted(VALID_NAME)
        );
      }

      jobs = Map.copyOf(jobs);
    }

    public static SyncConfiguration create(
      final Properties properties)
    {
      final var jobs =
        new HashMap<String, SyncJob>();
      final var dryRun =
        Boolean.parseBoolean(
          properties.getProperty("Doki.DryRun", "false")
        );

      final var host =
        getPropertyChecked(properties, "Doki.Host");

      final var jobNames =
        Stream.of(getPropertyChecked(properties, "Doki.Jobs").split("\\s+"))
          .map(String::trim)
          .toList();

      for (final var jobName : jobNames) {
        final var targetHost =
          getPropertyChecked(
            properties,
            "Doki.%s.TargetHost".formatted(jobName)
          );
        final var targetDir =
          getPropertyChecked(
            properties,
            "Doki.%s.Target".formatted(jobName)
          );
        final var sourceDir =
          getPropertyChecked(
            properties,
            "Doki.%s.Source".formatted(jobName)
          );
        final var sourceMetricsDir =
          getPropertyChecked(
            properties,
            "Doki.%s.SourceMetricsDir".formatted(jobName)
          );
        final var targetMetricsFile =
          getPropertyChecked(
            properties,
            "Doki.%s.TargetMetricsDir".formatted(jobName)
          );

        jobs.put(
          jobName,
          new SyncJob(
            jobName,
            sourceDir,
            Paths.get(sourceMetricsDir),
            targetHost,
            targetDir,
            targetMetricsFile
          )
        );
      }

      return new SyncConfiguration(dryRun, host, jobs);
    }

    private static String getPropertyChecked(
      final Properties properties,
      final String name)
    {
      final var value =
        properties.getProperty(name);

      if (value == null) {
        throw new IllegalArgumentException(
          "Missing required property: %s".formatted(name)
        );
      }

      return value;
    }
  }

  private record SyncJob(
    String name,
    String sourceDir,
    Path sourceMetricsDir,
    String targetHost,
    String targetDir,
    String targetMetricsDir)
  {
    SyncJob
    {
      Objects.requireNonNull(name, "name");

      if (!VALID_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
          "Job names must match the pattern %s"
            .formatted(VALID_NAME)
        );
      }
    }
  }
}
