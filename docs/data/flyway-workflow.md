# Flyway 迁移工作流

## 目录结构

```
voice-shopping-infrastructure/src/main/resources/
└── db/
    └── migration/
        └── V1__init_schema.sql      # 初始 schema（扩展 + 9 张表）
```

## 命名规范

```
V{版本号}__{描述}.sql
```

- `V` 前缀 — 版本化迁移（仅执行一次）
- `{版本号}` — 递增整数（1, 2, 3, ...）
- `__` — 双下划线分隔符
- `{描述}` — snake_case 格式描述
- `.sql` — 文件扩展名

## 配置

Flyway 配置位于 `voice-shopping-web/src/main/resources/application.yml`：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: "0"
    validate-on-migrate: true
```

## 开发流程

### 1. 创建新迁移

需要变更 schema 时：

1. 创建新文件，版本号递增
2. 编写 SQL（DDL、DML 或数据迁移）
3. 重启应用本地验证

示例：
```
V2__add_product_category_column.sql
V3__create_recommendation_log_table.sql
```

### 2. 执行迁移

迁移在应用启动时自动执行。也可手动操作：

```bash
# 手动执行迁移
mvn flyway:migrate -pl voice-shopping-infrastructure

# 校验文件校验和
mvn flyway:validate -pl voice-shopping-infrastructure

# 查看迁移状态
mvn flyway:info -pl voice-shopping-infrastructure
```

### 3. 规则

- **禁止修改已执行的迁移文件。** Flyway 会校验每个文件的 checksum，任何改动都会导致校验失败。
- **始终创建新迁移** 来变更 schema。
- **一个逻辑变更一个文件。** 关联变更可放在同一文件中。
- **利用事务。** PostgreSQL DDL 支持事务，失败的迁移会自动回滚。

### 4. 回滚策略

Flyway 社区版不支持自动回滚。可选方案：

- **只进模式：** 编写新迁移来反向操作（如 `V4__drop_xxx_column.sql`）。
- **手动修复：** 使用 `flyway:repair` 修复 schema 历史，然后应用修正后的迁移。
- **数据库备份：** 关键变更前先做 PG 备份。

### 5. CI/CD 集成

在 CI 流程中加入校验步骤，部署前发现 checksum 漂移：

```bash
mvn flyway:validate -pl voice-shopping-infrastructure
```

## 已有迁移

| 版本 | 文件 | 说明 |
|------|------|------|
| 1 | V1__init_schema.sql | 扩展 + 9 张表 + 索引 + 触发器 |
