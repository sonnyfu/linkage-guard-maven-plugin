# linkage-guard-maven-plugin

Static bytecode linkage analyzer and Maven plugin that catches `NoSuchMethodError`, `NoClassDefFoundError`, `NoSuchFieldError`, `AbstractMethodError`, `IncompatibleClassChangeError`, `IllegalAccessError`, `ClassCastException`, duplicate-class jar hell, and `javax.*` / `jakarta.*` namespace mixes **before runtime**.

## Build

```bash
./mvnw.cmd package
```

This produces:
- `target/linkage-guard-maven-plugin-0.1.0-SNAPSHOT.jar` (Maven plugin)
- `target/linkage-guard-maven-plugin-0.1.0-SNAPSHOT-cli.jar` (standalone shaded CLI)

## Maven Plugin Usage

### 1. Bind `check` to `verify`

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.github.linkageguard</groupId>
      <artifactId>linkage-guard-maven-plugin</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <executions>
        <execution>
          <goals>
            <goal>check</goal>
          </goals>
        </execution>
      </executions>
      <configuration>
        <scope>ALL</scope>
        <failOn>ERROR</failOn>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### 2. Run a "what-if" dependency upgrade check

```bash
mvn linkage-guard:upgrade -Dlinkage-guard.set=com.google.guava:guava:33.0.0-jre
```

## Standalone CLI Usage

```bash
java -jar target/linkage-guard-maven-plugin-0.1.0-SNAPSHOT-cli.jar --help
java -jar target/linkage-guard-maven-plugin-0.1.0-SNAPSHOT-cli.jar check --project . --scope ALL
java -jar target/linkage-guard-maven-plugin-0.1.0-SNAPSHOT-cli.jar upgrade --project . --set groupId:artifactId:version --explain
```
# linkage-guard-maven-plugin
