# Find-Ref-Svelte 🔍

A comprehensive Svelte/SvelteKit file analyzer that provides detailed insights into CSS usage, component references, imports, and code statistics.

![Groovy](https://img.shields.io/badge/Groovy-4298B8?style=for-the-badge&logo=Apache%20Groovy&logoColor=white)
![Svelte](https://img.shields.io/badge/Svelte-4A4A55?style=for-the-badge&logo=svelte&logoColor=FF3E00)
![SvelteKit](https://img.shields.io/badge/SvelteKit-FF3E00?style=for-the-badge&logo=Svelte&logoColor=white)

## 🚀 Features

- **CSS Analysis**: Deep analysis of style blocks, selectors, and CSS overrides
- **Reference Tracking**: Track variable usage, imports, and component references
- **Import Analysis**: Analyze ES6 imports and their usage patterns
- **CSS Override Detection**: Identify CSS property conflicts and specificity issues
- **Unused CSS Detection**: Find unused CSS classes and selectors
- **Git Integration**: Analyze specific commits and compare changes between commits
- **Multiple Output Formats**: Text, JSON, and CSV output support
- **SvelteKit Compatible**: Properly handles SvelteKit project structure
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

### Basic Usage
```bash
# Analyze a SvelteKit project
find-ref-svelte ./my-svelte-project

# Analyze with verbose output
find-ref-svelte -v ./my-svelte-project

# Analyze specific file
find-ref-svelte ./src/routes/+page.svelte
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

### Advanced Options
```bash
# Custom file extensions
find-ref-svelte --extensions .svelte,.vue ./project

# Non-recursive analysis
find-ref-svelte --recursive=false ./src/components
```

## 📊 Sample Output

### CSS Analysis
```
Title: /project/src/routes/+page.svelte

CSS Analysis:
- <style> declaration count: 1
- Style blocks:
  1. [Range 58:1 - 105:9] - Scoped styles
- Count of imported CSS: 0

CSS Override Analysis:
- Property conflicts detected: 3
  1. 'display' property overridden at [68:1] (specificity: 10)
     Previous declaration at [60:1]
  2. 'color' property overridden at [99:1] (specificity: 10)
     Previous declaration at [78:1]

CSS Selector Usage Analysis:
Classes:
- '.container' declared at [58:1] in Block 1
  Properties: display, flex-direction, align-items
  Used in HTML at: div [106:145]
- '.unused-class' declared at [67:1] in Block 1
  Properties: color, font-size
  ⚠️  Unused CSS class

Reference Analysis:
Variables:
- 'data' (let) declared at [9:10]
  Used at: [9:14], [11:13]

Import Analysis:
ES6 Imports:
- 'goto' from '$app/navigation' [imported at 2:3]
- 'page' from '$app/stores' [imported at 3:3]

Statistics:
- styleBlockCount: 1
- cssImportCount: 0
- variableCount: 2
- importCount: 2
- cssOverrideCount: 3
- cssSelectorCount: 5
- usedCSSSelectors: 4
- unusedCSSSelectors: 1
- totalLines: 106
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
| `--git-commit` | Analyze specific git commit | - |
| `--compare-commits` | Compare two commits (hash1,hash2) | - |
| `--git-working-dir` | Git repository directory | current |
| `-h, --help` | Show help message | - |
| `-V, --version` | Show version | - |

## 🎯 Use Cases

### 1. CSS Cleanup
Identify unused CSS classes and selectors to reduce bundle size:
```bash
find-ref-svelte -f json ./src | jq '.[] | select(.statistics.unusedCSSSelectors > 0)'
```

### 2. Refactoring Analysis
Before refactoring, understand component dependencies:
```bash
find-ref-svelte -v ./src/lib/components
```

### 3. Performance Auditing
Find components with high CSS override counts:
```bash
find-ref-svelte -f csv ./src > analysis.csv
# Import CSV into spreadsheet for analysis
```

### 4. Git Workflow Integration
Compare changes between commits:
```bash
find-ref-svelte --compare-commits HEAD~1,HEAD ./src
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
