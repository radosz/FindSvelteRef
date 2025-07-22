# Groovy Script for Svelte File Analysis (find-ref-svelte)

## Objective
Create a comprehensive Groovy script that analyzes Svelte files and provides detailed statistics in English, including CSS information, reference analysis, and import analysis. The script should be executable system-wide via symbolic link and extensible to handle files with any extension.

## Implementation Plan

1. **Set up Groovy Script Foundation**
   - Dependencies: None
   - Notes: Create executable Groovy script with proper shebang and @Grab dependencies
   - Files: `/usr/local/bin/find-ref-svelte` (symbolic link target)
   - Status: Not Started

2. **Implement File Discovery and Traversal**
   - Dependencies: Task 1
   - Notes: Recursively find all Svelte files in specified directory, extensible for other file types
   - Files: Main script file
   - Status: Not Started

3. **Develop CSS Analysis Module**
   - Dependencies: Task 2
   - Notes: Parse and analyze CSS within Svelte files, including style blocks and imports
   - Files: Main script file
   - Status: Not Started

4. **Implement Reference Analysis System**
   - Dependencies: Task 2
   - Notes: Track variables, let declarations, and their usage with line/column numbers
   - Files: Main script file
   - Status: Not Started

5. **Create Import Analysis Module**
   - Dependencies: Task 2
   - Notes: Analyze all import statements and their usage patterns
   - Files: Main script file
   - Status: Not Started

6. **Develop CSS Override Detection**
   - Dependencies: Task 3
   - Notes: Identify CSS property overrides and conflicts within style blocks
   - Files: Main script file
   - Status: Not Started

7. **Implement Range-based Analysis**
   - Dependencies: Tasks 3, 4, 5
   - Notes: Provide line range information for all detected elements
   - Files: Main script file
   - Status: Not Started

8. **Create Output Formatting System**
   - Dependencies: Tasks 3, 4, 5, 6, 7
   - Notes: Format analysis results in clear, readable English output
   - Files: Main script file
   - Status: Not Started

9. **Add Command Line Interface**
   - Dependencies: Task 8
   - Notes: Handle command line arguments for directory paths and options
   - Files: Main script file
   - Status: Not Started

10. **Create Symbolic Link Installation**
    - Dependencies: Task 9
    - Notes: Install script as system-wide executable via symbolic link
    - Files: `/usr/local/bin/find-ref-svelte`
    - Status: Not Started

11. **Implement Error Handling and Validation**
    - Dependencies: All previous tasks
    - Notes: Add comprehensive error handling and input validation
    - Files: Main script file
    - Status: Not Started

12. **Add Extension Support for Other File Types**
    - Dependencies: Task 11
    - Notes: Make the analysis system extensible for files with any extension
    - Files: Main script file
    - Status: Not Started

## Verification Criteria
- Script successfully analyzes Svelte files and provides comprehensive statistics
- CSS analysis includes style block detection, import tracking, and override identification
- Reference analysis tracks variables and their usage with accurate line/column positions
- Import analysis provides complete dependency mapping
- Output is formatted in clear, readable English
- Script is executable system-wide via symbolic link
- Error handling prevents crashes on malformed files
- System is extensible to other file types beyond Svelte

## Potential Risks and Mitigations

1. **Complex Svelte File Parsing**
   Mitigation: Use Apache Commons StringUtils instead of regex for robust text processing, implement incremental parsing approach

2. **Performance Issues with Large Codebases**
   Mitigation: Implement streaming file processing, add progress indicators, optimize memory usage

3. **CSS Override Detection Complexity**
   Mitigation: Build CSS specificity calculator, implement cascade rule analysis, use structured data for CSS properties

4. **Symbolic Link Permission Issues**
   Mitigation: Provide clear installation instructions, include permission checking, offer alternative installation methods

5. **Dependency Management with @Grab**
   Mitigation: Use well-established libraries, implement fallback mechanisms, document required dependencies

## Alternative Approaches

1. **Node.js Script**: Use JavaScript/TypeScript with existing Svelte parsing libraries for more native Svelte support
2. **Python Script**: Leverage Python's extensive text processing libraries and AST parsing capabilities
3. **Shell Script with Multiple Tools**: Combine existing command-line tools (grep, awk, sed) for simpler implementation
4. **Java Application**: Create a full Java application with Maven/Gradle for more robust dependency management
5. **Svelte Plugin**: Develop as a Svelte compiler plugin for deeper integration with the build process