# B-01 判据的可复跑证据：类型闭合需要显式类型见证

design.md §16.2「推导规则」与 §7.1 B-01 把「零适配层」写成<b>有条件</b>成立：
`patches` 拼接 `scripts` 得到有序混合清单时**必须带类型见证**，
不带见证的字面写法在 javac 17 下编译不过。本目录留存这两个探针，用来随时复跑该结论。

两个文件都**不参与 Maven 构建**（本目录不是 source root），只由 javac 手工编译。

## 前置

```bash
cd backend && mvn -q -pl plugin -am compile     # 产出 backend/plugin/target/classes
```

## 反例（预期：编译失败）

从仓库根目录：

```bash
mkdir -p target/javaprobe-out
javac -encoding UTF-8 -cp backend/plugin/target/classes -d target/javaprobe-out \
      backend/plugin-stub/acceptance/javaprobe/ProbeNoWitness.java
```

MERC-14（stage 1）实测输出，javac 17.0.16（`javac -version` 同值）：

```text
backend\plugin-stub\acceptance\javaprobe\ProbeNoWitness.java:18: 错误: 不兼容的类型: List<INT#1>无法转换为List<DeployExtensionStepDeclaration>
        return Stream.concat(patches.stream(), scripts.stream()).toList();
                                                                       ^
  其中, INT#1是交叉类型:
    INT#1扩展Record,DeployExtensionStepDeclaration
1 个错误
```

退出码 1。

> 与 design.md 引文的一处措辞差异（不影响结论）：设计里写 `INT#1 extends Record,Step`，
> 实际打印的上界名是 `Record,DeployExtensionStepDeclaration`（`Step` 是该接口的简写）。
> 报错主体 `List<INT#1>无法转换为List<DeployExtensionStepDeclaration>` 与设计引文逐字一致。

## 正例（预期：编译通过）

```bash
javac -encoding UTF-8 -cp backend/plugin/target/classes -d target/javaprobe-out \
      backend/plugin-stub/acceptance/javaprobe/ProbeWithWitness.java
```

退出码 0，无输出。

## 为什么这条反例值得常驻

它是「不加适配层/包装类型/字段复制」这一结论的**唯一直接证据**：
结论成立（不需要适配器），但成立的条件是类型见证，而不是「两个 List 自然就能拼成一个 List」。
少了这条证据，下游会把解析顺序 ② 写成 `List<Object>` 再补一层包装，
而那一层包装正是 §16.2 判定「sealed 上界是悬空类型」时要消除的形状。

同一断言的**可自动跑版本**在：

- `backend/plugin/src/test/java/com/gameplatform/plugin/extension/deploy/DeployExtensionStepDeclarationTest.java`
  （正例：类型闭合 + 元素身份不变 + 声明序）
- `backend/plugin-stub/src/test/java/com/gameplatform/plugin/stub/StubPluginLoadTest.java`
  （正例的加载期版本：从插件 JAR 读出的目录条目同样拼得成一个 `List<DeployExtensionStepDeclaration>`）
