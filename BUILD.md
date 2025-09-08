# Lavalink Plugin Development Guide

This is a project template for creating [Lavalink](https://github.com/lavalink-devs/Lavalink) plugins. Supports both Java and Kotlin (version `2.20.2`) development.

## How to Build Your Plugin

### Prerequisites
- Java 17 or higher
- Git

### Build Steps

1. **Clone or download the project**
```bash
git clone <your-repository-url>
cd <your-project-directory>
```

2. Set execute permissions (Linux/macOS)
```bash
chmod +x ./gradlew
```

3. Build the project
```bash
# Linux/macOS
./gradlew build

# Windows
./gradlew.bat build
```

4. Locate build artifacts
- After compilation, JAR files will be in the build/libs/ directory
- File name typically follows the pattern <project-name>-<version>.jar


## How to Test Your Plugin
### Local Testing
1. Prepare configuration file

- Place an `application.yml` file in the project root directory
- For configuration examples, see Lavalink official documentation

2. Start test environment
```bash
# Linux/macOS
./gradlew runLavalink

# Windows
./gradlew.bat runLavalink
```
3. Testing workflow
- Plugin will be automatically loaded
- Test plugin functionality through Lavalink's REST API or WebSocket
- After code changes, re-run `./gradlew runLavalink` to reload

### Unit Testing
Run project tests:
```bash
# Linux/macOS
./gradlew test

# Windows
./gradlew.bat test
```
### Common Gradle Commands
```bash
# Clean build artifacts
./gradlew clean

# Compile without running tests
./gradlew assemble

# Build and run all tests
./gradlew build

# View project dependencies
./gradlew dependencies

# Generate test reports
./gradlew test --scan
```
