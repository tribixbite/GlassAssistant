# GitHub Actions CI/CD

## Automatic APK Builds

This repository is configured with GitHub Actions to automatically build APKs on every commit.

### Workflow Features

✅ **Automatic Builds**
- Triggers on every push to `main` and `develop` branches
- Builds on pull requests to `main`
- Manual trigger available via workflow_dispatch

✅ **Build Artifacts**
- Debug APK uploaded for every build
- Release APK attempted (unsigned)
- 30-day retention for artifacts
- Unique naming with build numbers

✅ **Automatic Releases**
- Creates GitHub releases for main branch commits
- Tags releases with version from build.gradle.kts
- Attaches debug APK to releases

### View Build Status

🔗 **Actions Page:** https://github.com/tribixbite/GlassAssistant/actions

### Download APKs

1. Go to [Actions tab](https://github.com/tribixbite/GlassAssistant/actions)
2. Click on a successful workflow run
3. Scroll down to "Artifacts"
4. Download `app-debug-{build-number}.zip`
5. Extract and install APK on Glass

### Manual Build Trigger

1. Go to [Actions tab](https://github.com/tribixbite/GlassAssistant/actions/workflows/build-apk.yml)
2. Click "Run workflow"
3. Select branch
4. Click "Run workflow" button

### Build Configuration

The workflow uses:
- **Java:** JDK 17 (Temurin distribution)
- **Android SDK:** API 19 and 34
- **Build Tools:** 34.0.0
- **Gradle:** Wrapper included in repository

### Troubleshooting

If builds fail:
1. Check the [Actions logs](https://github.com/tribixbite/GlassAssistant/actions)
2. Common issues:
   - License acceptance: Already handled in workflow
   - Out of memory: Workflow uses default GitHub runner memory
   - Missing dependencies: Check build.gradle.kts

### Local Testing

Test the workflow locally with [act](https://github.com/nektos/act):

```bash
# Install act
brew install act  # macOS
# or
curl https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash  # Linux

# Test workflow
act push
```

### Workflow File

Located at: `.github/workflows/build-apk.yml`

### Build Status Badges

```markdown
[![Build APK](https://github.com/tribixbite/GlassAssistant/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tribixbite/GlassAssistant/actions/workflows/build-apk.yml)
[![Latest Release](https://img.shields.io/github/v/release/tribixbite/GlassAssistant)](https://github.com/tribixbite/GlassAssistant/releases)
```