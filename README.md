# lavabili-plugin
This plugin is rebuilt for latest Lavalink (v4) based on [(hoyiliang/lavabili-plugin)](https://github.com/hoyiliang/lavabili-plugin).

A lavalink plugin written to add Bilibili as an additional audio playing source.

## How?
Configuring the plugin:
```
lavalink:
  plugins:
    # Replace VERSION with the current version as shown by the Releases tab or a long commit hash for snapshots.
    - dependency: "com.github.ParrotXray:lavabili-plugin:VERSION"
      snapshot: false
      repository: "https://jitpack.io"
```

Configuring the plugin:
```
plugins:
  lavabili:
    sources:
      enable: true
    playlistPageCount: -1  # optional, -1 means no limit
```