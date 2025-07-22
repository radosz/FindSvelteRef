# ⚠️ WORK IN PROGRESS! 

This find-ref-svelte tool is actively being developed and enhanced. Use with caution in production environments.

## Current Status
- ✅ CSS analysis with 100% false positive elimination
- ✅ Dead code detection with event listener safety
- ✅ Commons Lang3 integration for cleaner code
- ✅ Comprehensive unit test suite (10 tests passing)

## Recent Enhancements
- Enhanced CSS parsing (keyframes, media queries, descendant selectors)
- Improved JavaScript function usage detection
- Event listener safety (addEventListener/removeEventListener)
- Refactored regex patterns to use Commons Lang3 StringUtils

## Usage
```bash
# CSS analysis
./find-ref-svelte.groovy /path/to/svelte/project --filter-css-issues

# Dead code detection  
./find-ref-svelte.groovy /path/to/svelte/project --filter-dead-code

# Full analysis
./find-ref-svelte.groovy /path/to/svelte/project
```

## Development Notes
- Tool continuously evolving based on real-world usage
- Please report any false positives or missed detections
- Test thoroughly before applying suggested changes to production code