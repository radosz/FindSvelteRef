# Find-Ref-Svelte 🔍

A comprehensive Svelte/SvelteKit file analyzer and refactoring tool that provides detailed insights into CSS usage, dead code detection, unused component analysis, imports, and code statistics.

![Groovy](https://img.shields.io/badge/Groovy-4298B8?style=for-the-badge&logo=Apache%20Groovy&logoColor=white)
![Svelte](https://img.shields.io/badge/Svelte-4A4A55?style=for-the-badge&logo=svelte&logoColor=FF3E00)
![SvelteKit](https://img.shields.io/badge/SvelteKit-FF3E00?style=for-the-badge&logo=Svelte&logoColor=white)

## 🚀 Features

### Core Analysis
- **CSS Analysis**: Deep analysis of style blocks, selectors, and CSS overrides
- **Dead Code Detection**: Identify unused JavaScript/TypeScript functions and variables
- **Unused Component Analysis**: Find imported components that are never used
- **Reference Tracking**: Track variable usage, imports, and component references
- **Import Analysis**: Analyze ES6 imports and their usage patterns

### Advanced Capabilities
- **CSS Override Detection**: Identify CSS property conflicts and specificity issues
- **Dynamic CSS Pattern Detection**: Smart detection avoids false positives for patterns like `toast-{variable}`
- **Svelte Binding Support**: Detect usage in Svelte's reactive bindings `{functionName}`
- **Git Integration**: Analyze specific commits and compare changes between commits
- **Multiple Output Formats**: Text, JSON, and CSV output support
- **Focused Filtering**: Target specific types of issues for focused refactoring

### Refactoring Support
- **Actionable Recommendations**: Get specific suggestions for code cleanup
- **Comprehensive Reports**: All-in-one refactoring analysis with prioritized suggestions
- **Performance Statistics**: Comprehensive metrics and statistics

## 📦 Installation

### Quick Install
```bash
curl -fsSL https://raw.githubusercontent.com/radosz/FindSvelteRef/main/install.sh | bash
```

### Manual Installation
1. Clone the repository:
```bash
git clone git@github.com:radosz/FindSvelteRef.git
cd FindSvelteRef
```

2. Make the script executable:
```bash
chmod +x find-ref-svelte.groovy
```

3. Install globally (optional):
```bash
sudo ln -s $(pwd)/find-ref-svelte.groovy /usr/local/bin/find-ref-svelte
```

## 🛠️ Usage

### Basic Analysis
```bash
# Analyze a SvelteKit project
find-ref-svelte ./my-svelte-project

# Analyze with verbose output
find-ref-svelte -v ./my-svelte-project

# Analyze specific file
find-ref-svelte ./src/routes/+page.svelte
```

### Focused Refactoring Analysis
```bash
# Focus on CSS issues only
find-ref-svelte --filter-css-issues ./project

# Find dead code (unused functions/variables)
find-ref-svelte --filter-dead-code ./project

# Find unused component imports
find-ref-svelte --filter-unused-components ./project

# Comprehensive refactoring report
find-ref-svelte --filter-all-issues ./project
```

### Output Formats
```bash
# JSON output
find-ref-svelte -f json ./project

# CSV output
find-ref-svelte -f csv ./project

# Save to file
find-ref-svelte -o analysis-report.txt ./project
```

### Git Integration
```bash
# Analyze specific commit
find-ref-svelte --git-commit abc123 ./project

# Compare two commits
find-ref-svelte --compare-commits abc123,def456 ./project
```

## 📊 Sample Output

### CSS Issues Report
```
🎨 CSS Issues Found:
================================================================================
File: /project/src/routes/+page.svelte

Unused CSS Classes:
- '.unused-button' declared at style block line 23
  Properties: background-color, padding, border-radius
  ❌ Never used in markup

CSS Override Issues:
- 'display' property overridden at [68:1] (specificity: 10)
  Previous declaration at [60:1]
  ⚠️  Consider consolidating or using more specific selectors

Summary: 1 unused CSS class, 1 override issue
```

### Dead Code Report
```
💀 Dead Code Found:
================================================================================
File: /project/src/lib/utils.js

Unused Functions:
- 'formatDate' declared at line 15
  ❌ Function is defined but never called
  💡 Recommendation: Remove if truly unused or export for external use

- 'validateEmail' declared at line 28
  ❌ Function is defined but never called

Summary: 2 unused functions detected
```

### Unused Components Report
```
🧩 Unused Components Found:
================================================================================
File: /project/src/routes/dashboard.svelte

Unused Component Imports:
- 'LoadingSpinner' imported from '$lib/components/LoadingSpinner.svelte'
  ❌ Component imported but never used in markup
  💡 Recommendation: Remove import or use the component

- 'ErrorBoundary' imported from '$lib/components/ErrorBoundary.svelte'
  ❌ Component imported but never used

Summary: 2 unused component imports
```

### Comprehensive Refactoring Report
```
🔧 Comprehensive Refactoring Analysis:
================================================================================

Priority Refactoring Opportunities:

🎨 CSS Issues (Priority: Medium)
- 3 unused CSS classes across 2 files
- 1 CSS override conflict

💀 Dead Code (Priority: High)
- 5 unused functions across 3 files
- 2 unused variables

🧩 Unused Components (Priority: Medium)
- 2 unused component imports

💡 Refactoring Recommendations:
1. Remove 5 unused functions to reduce bundle size
2. Clean up 2 unused component imports
3. Consolidate CSS overrides in dashboard.svelte
4. Review unused CSS classes for removal

Estimated cleanup impact: ~15% reduction in bundle size
```

## 📋 Command Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `<inputPath>` | Directory or file to analyze | Required |
| `-o, --output` | Output file path | Console |
| `-f, --format` | Output format (text, json, csv) | text |
| `-r, --recursive` | Recursive directory analysis | true |
| `-v, --verbose` | Verbose output | false |
| `--extensions` | File extensions to analyze | .svelte |
| `--filter-css-issues` | Show only CSS-related issues | - |
| `--filter-dead-code` | Show only dead code (unused functions/variables) | - |
| `--filter-unused-components` | Show only unused component imports | - |
| `--filter-all-issues` | Comprehensive refactoring report | - |
| `--git-commit` | Analyze specific git commit | - |
| `--compare-commits` | Compare two commits (hash1,hash2) | - |
| `--git-working-dir` | Git repository directory | current |
| `-h, --help` | Show help message | - |
| `-V, --version` | Show version | - |

## 🎯 Use Cases

### 1. Pre-Refactoring Analysis
Get a comprehensive overview before starting refactoring:
```bash
find-ref-svelte --filter-all-issues ./src
```

### 2. CSS Cleanup
Identify unused CSS for bundle size optimization:
```bash
find-ref-svelte --filter-css-issues ./src
```

### 3. Dead Code Elimination
Find and remove unused functions to improve performance:
```bash
find-ref-svelte --filter-dead-code ./src
```

### 4. Component Import Cleanup
Clean up unused component imports:
```bash
find-ref-svelte --filter-unused-components ./src
```

### 5. CI/CD Integration
Add to your build pipeline for automated refactoring insights:
```bash
# In your GitHub Actions or GitLab CI
find-ref-svelte --filter-all-issues -f json ./src > refactoring-report.json
```

## 🧪 Testing

The tool includes a comprehensive test suite:

```bash
# Run all tests
groovy run-tests.groovy

# The test suite covers:
# ✅ CSS issue detection and dynamic pattern handling
# ✅ Dead code detection (unused functions/variables)
# ✅ Unused component import detection
# ✅ Multiple output formats (JSON, CSV)
# ✅ Error handling and edge cases
```

## 🔧 Configuration

The tool automatically excludes common build directories:
- `node_modules`
- `.svelte-kit`
- `.git`
- `dist`
- `build`
- `.next`
- `.nuxt`

## 🐛 Troubleshooting

### Common Issues

**"No files found to analyze"**
- Ensure you're running from the correct directory
- Check that `.svelte` files exist in the specified path
- Use `-v` flag for verbose output to debug

**"Permission denied"**
- Make sure the script is executable: `chmod +x find-ref-svelte.groovy`
- Check file permissions in the target directory

**Groovy not found**
- Install Groovy: `brew install groovy` (macOS) or `apt install groovy` (Linux)

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/new-feature`
3. Commit changes: `git commit -am 'Add new feature'`
4. Push to branch: `git push origin feature/new-feature`
5. Submit a Pull Request

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- [Svelte](https://svelte.dev/) - The amazing reactive framework
- [SvelteKit](https://kit.svelte.dev/) - The full-stack Svelte framework
- [Apache Groovy](https://groovy-lang.org/) - The dynamic language for the JVM

---

**Powered by [Forgecode](https://app.forgecode.dev/app/) and Claude Sonnet 4**

<div align="center">
  <img src="https://app.forgecode.dev/logo.svg" alt="Fogecode Logo" width="100" height="100">
  <br>
  <em>Built with AI-powered development tools</em>
</div>