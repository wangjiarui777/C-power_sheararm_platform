# Backup and recovery runbook

Targets: RPO ≤ 15 minutes and RTO ≤ 2 hours.

- MySQL: daily full backup, binary logs archived every 15 minutes to NAS.
- Redis: AOF `everysec`, daily RDB, and at least 24 hours of Stream retention.
- IoTDB: daily metadata/data snapshot; replay missing feature messages from Redis
  Streams and edge buffers.
- Attachments, model artifacts, production configuration and SHA-256 manifests:
  daily incremental copy to an independent NAS.

Quarterly restore drill:

1. Provision a clean Windows Server with isolated networking.
2. Restore MySQL full backup, then replay binlogs to the selected timestamp.
3. Restore Redis and IoTDB snapshots.
4. Restore attachments/models and verify every manifest hash.
5. Install the previous known-good release and run Flyway validation.
6. Run login, ingestion, diagnosis, alarm, report and attachment smoke tests.
7. Record elapsed time, missing event IDs, manual steps and corrective actions.
