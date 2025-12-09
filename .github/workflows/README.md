# GitHub Actions Workflows

This directory contains automated workflows for building and releasing the mod.

## Workflows

### 1. Build and Release (`build.yml`)
**Triggers:**
- Push to `main` branch
- Git tags starting with `v*`
- Pull requests to `main`
- Manual workflow dispatch

**Actions:**
- Builds the mod with automatic versioning
- For tagged releases (e.g., `v1.2.3`): Uses the tag as version
- For regular commits: Uses `1.0.0-build.N` format
- Uploads build artifacts (retained for 90 days)
- Creates GitHub releases for tagged versions

**Example:**
```bash
# Create a release
git tag v1.0.0
git push origin v1.0.0
# → Builds and creates release with version 1.0.0
```

### 2. Development Build (`dev-build.yml`)
**Triggers:**
- Push to `dev`, `develop` branches
- Push to `feature/**` branches
- Manual workflow dispatch

**Actions:**
- Builds development versions
- Version format: `1.0.0-dev.BRANCH.BUILD_NUMBER`
- Uploads artifacts (retained for 30 days)

**Example:**
```bash
git checkout -b feature/new-ai
git push origin feature/new-ai
# → Builds version 1.0.0-dev.feature-new-ai.123
```

### 3. Pull Request Check (`pr-check.yml`)
**Triggers:**
- Pull requests to `main`, `dev`, or `develop` branches

**Actions:**
- Runs Gradle checks
- Test builds the mod
- Comments on PR with build status

## Versioning System

### Automatic Version Numbers

The version is automatically determined based on the build type:

| Build Type | Format | Example |
|------------|--------|---------|
| Tagged Release | `v{tag}` | `1.0.0` |
| Main Branch Build | `1.0.0-build.{run_number}` | `1.0.0-build.42` |
| Development Build | `1.0.0-dev.{branch}.{run_number}` | `1.0.0-dev.feature-ai.15` |

### Version Configuration

Versions are managed through:
- **gradle.properties**: `mod_version` property
- **build.gradle**: Reads from `mod_version` or defaults to `1.0.0`
- **GitHub Actions**: Dynamically updates `mod_version` before building

## Creating Releases

### Stable Release
```bash
# Update to release version
git tag v1.0.0
git push origin v1.0.0
```

This will:
1. Trigger the build workflow
2. Build with version `1.0.0`
3. Upload artifacts
4. Create a GitHub release with auto-generated notes

### Pre-release/Beta
```bash
git tag v1.0.0-beta.1
git push origin v1.0.0-beta.1
```

### Development Snapshot
Just push to a development branch - no tagging needed:
```bash
git checkout dev
git push origin dev
# → Creates 1.0.0-dev.dev.N build
```

## Build Artifacts

All builds produce artifacts uploaded to GitHub:
- **Releases**: Attached to the GitHub release
- **Main builds**: Available in Actions tab (90 days)
- **Dev builds**: Available in Actions tab (30 days)

## Requirements

GitHub Actions automatically:
- Sets up JDK 17
- Caches Gradle dependencies
- Downloads Gradle wrapper if needed
- Builds using `./gradlew build`

No local setup required - everything runs in CI.

## Manual Workflow Dispatch

All workflows can be triggered manually:
1. Go to "Actions" tab on GitHub
2. Select the workflow
3. Click "Run workflow"
4. Choose branch and run

## Troubleshooting

### Build Fails
- Check the Actions tab for detailed logs
- Verify `gradle.properties` has `mod_version` defined
- Ensure `build.gradle` references `mod_version`

### Version Not Updating
- Verify the workflow has write permissions
- Check that `sed` command in workflow is correct
- Look for version in build logs

### Release Not Created
- Only tag pushes create releases
- Tags must start with `v` (e.g., `v1.0.0`)
- Check `GITHUB_TOKEN` permissions

## Local Testing

To test builds locally with automatic versioning:

```bash
# Set version manually
./gradlew build -Pmod_version=1.0.0-local

# Use default version from gradle.properties
./gradlew build
```
