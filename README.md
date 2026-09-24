# Relwind

Relwind is a Java library and server plugin that implemements **entity relationships in Hytale's ECS**. 

## Features

The core idea behind Relwind is the concept of a _Relationship_: a relationship is a link between two (or more) ecs entities that can
(optionally) hold data.

To achieve this result in the Hytale ECS, it's common to store entity references inside of components, which can become
repetitive to maintain and usually ends up with scattered patterns or various utilities. 
Because of this, and by getting inspiration from Flecs' relationships ideas, Relwind was created to automatically handle 
and standardize generic ecs scene graphs while also maintaining a reverse lookup automatically. 
Various examples, present in `relwind-examples`, can give ideas of various usages that this library could provide.

Other features are the following:

- **Relationship queries:** combine component and relationship conditions, select link data, and traverse reachable entities with an explicit depth limit.
- **Wildcard conditions:** `existsAny` tests links across a registry's same-Store types. `enumerateAny` binds each matching link with its relationship type and data.
- **Link reads:** `hasTarget` checks one loaded link. `forEachLink` and `forEachIncomingLink` visit loaded links across relationship types, including bridge types, with their type and data.
- **Clear targets:** `clearTargets` removes a source's loaded and away links of one type. A command buffer queues the clear until it drains.
- **Exclusive types:** `putTarget` replaces the current target and its data, including a target that is away. `addTarget` rejects a second target.
- **Symmetric types:** `symmetric()` keeps twin links and their data together when a command adds, changes or removes a link.
- **Lifecycle traits:** choose whether links survive transfers and temporary deactivation, and whether deleting a target also deletes its linked sources.
- **Persistence:** named relationship types save their links; unnamed types remain in memory. Retained links can remain unresolved while a linked entity is unavailable.
- **ECS integration:** native command buffers and relationship-aware ticking, change, event, and lifecycle systems.
- **Entity and block entity support:** relationships within `EntityStore` or `ChunkStore`
- **Cross-Store relationships and systems (wip)**: relationships across different stores (`EntityStore`, `ChunkStore` and custom `StoreInstallation`(s))

## Build

Requirements:

- JDK **25 or newer**; Maven compiles with `--release 25`.
- Maven **3.9.x**.
- Access to Maven Central and Hytale's Maven repository for the first build.

From the repository root:

```sh
mvn clean install
```

This builds all four modules, runs the default tests, generates source and Javadoc JARs, and installs the artifacts in your local Maven repository for use by another plugin project. Tests tagged `slow` are excluded from the default run.

### Hytale versions

Relwind supports one server version for each release channel:

| Channel | Hytale version | Build command |
| --- | --- | --- |
| Pre-release (default) | `0.7.0-pre.3.1` | `mvn clean install` |
| Release | `0.6.8` | `mvn -Dhytale.channel=release clean install` |

## Install on a server

1. Build for your server's Hytale channel.
2. Copy `relwind/target/relwind-0.1.0-SNAPSHOT.jar` into the server's `mods/` directory.
3. Start the server with the plugins that use Relwind.

The `relwind` JAR bundles `relwind-component` and `relwind-coreserver`. Do not install those library JARs separately, or use the `original-`, `-sources`, or `-javadoc` JARs as the server plugin.

For the playable examples, also install `relwind-examples/target/relwind-examples-0.1.0-SNAPSHOT.jar`. Use a test world: some example commands remove entities.

## Use Relwind in your plugin

After building and installing Relwind locally, add this dependency to your plugin's `pom.xml`, alongside your matching Hytale Server dependency:

```xml
<dependency>
    <groupId>dev.hytalemodding.blovien</groupId>
    <artifactId>relwind</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

Relwind is supplied by the server plugin at runtime; do not shade a second copy into your plugin.

Merge this entry into your plugin's `manifest.json` dependencies:

```json
{
  "Dependencies": {
    "Blovien:Relwind": "*"
  }
}
```

The dependency ensures Relwind is set up before your plugin accesses `Relwind.get()`. The [example plugin](relwind-examples/src/main/java/dev/hytalemodding/blovien/relwind/examples/RelwindExamplePlugin.java) shows registration alongside ordinary Hytale components and systems.

## Project layout

| Module | Responsibility |
| --- | --- |
| [`relwind-component`](relwind-component) | Generic relationship API, storage, queries, lifecycle traits and persistence contracts. |
| [`relwind-coreserver`](relwind-coreserver) | Hytale integration: entity and block identities, Store lifecycle, player transitions, and saving. |
| [`relwind`](relwind) | Server plugin entry point and bundled distribution JAR. |
| [`relwind-examples`](relwind-examples) | Separate playable example plugin. |

For a custom Store kind, start with [`StoreInstallation`](relwind-component/src/main/java/dev/hytalemodding/blovien/relwind/StoreInstallation.java), [`PersistenceIdentity`](relwind-component/src/main/java/dev/hytalemodding/blovien/relwind/PersistenceIdentity.java), and [`StoreRuntime`](relwind-component/src/main/java/dev/hytalemodding/blovien/relwind/StoreRuntime.java). Ordinary server plugins use the installations already exposed by `Relwind.get()`.

## Development and documentation

Run the default tests without packaging:

```sh
mvn test
```

Run the component module's slow tests separately. Select the channel explicitly because enabling another Maven profile disables the default channel profile:

```sh
mvn -pl relwind-component -Pintegrate -Dhytale.channel=pre-release test
```

For release-channel testing, use `-Dhytale.channel=release`.

## LLM usage

LLM assistance was used in the following areas:
- code review
- testing coverage
- benchmarking code
- minor ci setups

The rest of the implementation is done entirely by me.

## License

Relwind is licensed under the **GNU Lesser General Public License, version 3.0 only** (`LGPL-3.0-only`). See [LICENSE](LICENSE) for the full terms.
