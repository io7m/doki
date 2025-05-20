doki
===

A small, monitorable script for syncing directories to backup servers.

## Usage

```
$ java Doki.java config.properties
```

An example `config.properties` file:

```
Doki.Host=server01
Doki.DryRun=false
Doki.Jobs=db05 photo02 objects03

Doki.db05.Source=/src/db05/
Doki.db05.SourceMetricsDir=/backup_metrics
Doki.db05.Target=/backup/db05/
Doki.db05.TargetHost=db@backup02
Doki.db05.TargetMetricsDir=/backup_metrics/

Doki.photo02.Source=/src/photo02/
Doki.photo02.SourceMetricsDir=/backup_metrics
Doki.photo02.Target=/backup/photo02/
Doki.photo02.TargetHost=photo@backup02
Doki.photo02.TargetMetricsDir=/backup_metrics/

Doki.objects03.Source=/src/objects03/
Doki.objects03.SourceMetricsDir=/backup_metrics
Doki.objects03.Target=/backup/objects03/
Doki.objects03.TargetHost=objects@backup02
Doki.objects03.TargetMetricsDir=/backup_metrics/
```

[Prometheus](https://prometheus.io) metrics files will be generated and
copied into the target metrics directory. These can be served with the 
`textfile` collector to monitor backups.

```
# HELP backup_time_last The last time a backup was received
# TYPE backup_time_last gauge
backup_time_last{host="server01",directory="/src/db05/"} 1747746987
# HELP backup_duration_last The duration of the last backup
# TYPE backup_duration_last gauge
backup_duration_last{host="server01",directory="/src/db05/"} 0
```
