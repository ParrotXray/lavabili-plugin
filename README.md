# lavabili-plugin
<img src="https://github.com/user-attachments/assets/1bfb4369-6438-4e5e-9e6e-72b94cb69a37" alt="Alt Text" style="width:25%; height:auto;">

## What?
A lavalink plugin written to add Bilibili as an additional audio playing source.

## Why?
This plugin is rebuilt for latest Lavalink (v4) based on [(hoyiliang/lavabili-plugin)](https://github.com/hoyiliang/lavabili-plugin).

Differences:
+ Removed unknown & unresolvable build dependencies.
+ `artworkUrl` extraction from **Bilibili** videos.
+ Support for older bilibili videos that uses `aid` videoId.

## How?
Configuring the plugin:
```
...
lavalink:
  plugins:
    # Replace VERSION with the current version as shown by the Releases tab or a long commit hash for snapshots.
    - dependency: "com.github.ParrotXray:lavabili-plugin:1.0.0"
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

**Note: The decision to use `MIT License` is derived from [(ParrotXray/lavabili-plugin)](https://github.com/ParrotXray/lavabili-plugin).**