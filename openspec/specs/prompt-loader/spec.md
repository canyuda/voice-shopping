## Purpose

从 classpath `prompts/` 目录统一加载 Prompt 模板文件的 Spring Bean，避免各 Agent Builder 分散处理文件读取。

## Requirements

### Requirement: PromptLoader 从 classpath 加载 Prompt 文件

系统 SHALL 提供 `PromptLoader` Spring Bean，从 classpath 下的 `prompts/` 目录加载 Prompt 模板文件。

PromptLoader SHALL 读取文件内容并以 UTF-8 编码的 String 形式返回。

#### Scenario: 成功加载 Prompt 文件
- **WHEN** 调用 `load("intent.txt")` 且文件存在于 classpath 的 `prompts/` 目录下
- **THEN** 返回该文件的完整文本内容（UTF-8 编码）

#### Scenario: 文件不存在时快速失败
- **WHEN** 调用 `load("nonexistent.txt")` 且文件不存在于 classpath
- **THEN** 抛出 `RuntimeException`，消息包含失败的文件路径

### Requirement: PromptLoader 路径前缀管理

PromptLoader SHALL 自动处理 `prompts/` 路径前缀，调用方仅需传入文件名。

#### Scenario: 仅传文件名
- **WHEN** 调用 `load("intent.txt")`
- **THEN** PromptLoader 自动拼接为 `prompts/intent.txt` 并从 classpath 加载
