#!/usr/bin/env groovy

@Grapes([
    @Grab(group='org.apache.commons', module='commons-lang3', version='3.18.0'),
    @Grab('commons-io:commons-io:2.11.0'),
    @Grab('info.picocli:picocli:4.7.0'),
    @Grab('com.fasterxml.jackson.core:jackson-core:2.15.2'),
    @Grab('org.apache.commons:commons-csv:1.9.0'),
    @Grab('org.slf4j:slf4j-simple:2.0.7')
])

import groovy.json.JsonBuilder
import org.apache.commons.lang3.StringUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.Option
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import java.nio.file.*
import java.util.concurrent.Callable
// Removed regex import - using Commons Lang3 StringUtils instead
import groovy.transform.Field

@Command(name = "find-ref-svelte", mixinStandardHelpOptions = true, version = "1.0.0",
         description = "Analyzes Svelte files for CSS, references, and imports with detailed statistics")
class SvelteAnalyzer implements Callable<Integer> {

    @Parameters(index = "0", description = "Directory or file to analyze")
    String inputPath

    @Option(names = ["-o", "--output"], description = "Output file (default: console)")
    String outputFile

    @Option(names = ["-f", "--format"], description = "Output format: text, json, csv (default: text)")
    String format = "text"

    @Option(names = ["-v", "--verbose"], description = "Verbose output")
    boolean verbose = false

    @Option(names = ["--extensions"], description = "File extensions to analyze (default: .svelte)")
    String extensions = ".svelte"
    @Option(names = ["--git-commit"], description = "Analyze specific git commit hash")
    String gitCommit

    @Option(names = ["--compare-commits"], description = "Compare two git commits (format: hash1,hash2)")
    String compareCommits

    @Option(names = ["--git-working-dir"], description = "Git repository directory (default: current directory)")
    String gitWorkingDir = "."

    @Option(names = ["--filter-css-issues"], description = "Show only CSS-related issues that need fixing")
    boolean filterCssIssues = false

    @Option(names = ["--filter-dead-code"], description = "Show only unused JavaScript/TypeScript functions and variables")
    boolean filterDeadCode = false

    @Option(names = ["--filter-unused-components"], description = "Show only unused Svelte components")
    boolean filterUnusedComponents = false

    @Option(names = ["--filter-all-issues"], description = "Show all refactoring issues (CSS + dead code + unused components)")
    boolean filterAllIssues = false

    // Data structures for analysis results
    class AnalysisResult {
        String filePath
        List<StyleBlock> styleBlocks = []
        List<CSSImport> cssImports = []
        List<Variable> variables = []
        List<ImportStatement> imports = []
        List<CSSOverride> cssOverrides = []
        List<CSSSelector> cssSelectors = []
        List<JavaScriptFunction> jsFunctions = []
        List<ComponentUsage> componentUsages = []
        List<ComponentImport> componentImports = []
        Map<String, Object> statistics = [:]
    }

    class StyleBlock {
        int startLine, endLine, startCol, endCol
        String content
        Map<String, String> attributes = [:]
        boolean isScoped = true
        String language = "css"
    }

    class CSSImport {
        String path
        String absolutePath
        int line, column
        String type // @import, link, etc
    }

    class Variable {
        String name
        String type // let, const, var, reactive, prop, store
        int declarationLine, declarationCol
        List<Usage> usages = []
        List<String> dependencies = []
    }

    class Usage {
        int line, column
        String context
    }

    class ImportStatement {
        String module
        String absolutePath
        List<String> symbols = []
        int line, column
        String type // es6, dynamic, commonjs
        List<Usage> usages = []
    }

    class CSSOverride {
        String property
        int line, column
        String selector
        int specificity
        boolean important
        String conflictsWith
    }
    class CSSSelector {
        String selector
        String type // class, id, element, attribute
        String name // the actual class/id name
        int declarationLine, declarationColumn
        List<String> properties = []
        List<Usage> htmlUsages = []
        List<CSSOverride> overrides = []
        String sourceBlock // which style block it comes from
    }

    class CSSUsage extends Usage {
        String element
        String attribute // class, id, etc
    }

    class JavaScriptFunction {
        String name
        String type // function, arrow, method, getter, setter
        int declarationLine, declarationColumn
        List<String> parameters = []
        List<Usage> usages = []
        boolean isExported = false
        boolean isAsync = false
        String scope // global, class, local
    }

    class ComponentUsage extends Usage {
        String componentName
        String tagName
        Map<String, String> props = [:]
    }

    class ComponentImport {
        String componentName
        String filePath
        int line, column
        boolean isUsed = false
        List<ComponentUsage> usages = []
    }
    class GitCommitAnalysis {
        String commitHash
        String commitMessage
        String commitDate
        String author
        AnalysisResult analysisResult
        List<String> modifiedFiles = []
    }

    class ComparisonResult {
        GitCommitAnalysis commit1
        GitCommitAnalysis commit2
        Map<String, Object> differences = [:]
        List<String> addedFiles = []
        List<String> removedFiles = []
        List<String> modifiedFiles = []
    }

    // Location tracking utility
    class LocationTracker {
        private String content
        private int[] lineStarts

        LocationTracker(String content) {
            this.content = content
            calculateLineStarts()
        }

        private void calculateLineStarts() {
            List<Integer> starts = [0]
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    starts.add(i + 1)
                }
            }
            lineStarts = starts as int[]
        }

        Map<String, Integer> getLineColumn(int offset) {
            int line = Arrays.binarySearch(lineStarts, offset)
            if (line < 0) line = -line - 2
            int column = offset - lineStarts[line]
            return [line: line + 1, column: column + 1]
        }

        String formatRange(int startOffset, int endOffset) {
            def start = getLineColumn(startOffset)
            def end = getLineColumn(endOffset)
            if (start.line == end.line) {
                return "[${start.line}:${start.column}-${end.column}]"
            } else {
                return "[${start.line}:${start.column} - ${end.line}:${end.column}]"
            }
        }
    }

    // Git operations helper
    class GitHelper {
        private String workingDir
        
        GitHelper(String workingDir) {
            this.workingDir = workingDir
        }
        
        boolean isGitRepository() {
            try {
                def result = executeGitCommand("rev-parse --git-dir")
                return result.exitCode == 0
            } catch (Exception e) {
                return false
            }
        }
        
        String getCurrentBranch() {
            def result = executeGitCommand("rev-parse --abbrev-ref HEAD")
            return result.exitCode == 0 ? result.output.trim() : "unknown"
        }
        
        String getCurrentCommitHash() {
            def result = executeGitCommand("rev-parse HEAD")
            return result.exitCode == 0 ? result.output.trim() : "unknown"
        }
        
        GitCommitAnalysis getCommitInfo(String commitHash) {
            GitCommitAnalysis analysis = new GitCommitAnalysis()
            analysis.commitHash = commitHash
            
            // Get commit info
            def infoResult = executeGitCommand("show --format='%H|%s|%ad|%an' --date=iso --name-only ${commitHash}")
            if (infoResult.exitCode == 0) {
                String[] lines = infoResult.output.split("\n")
                if (lines.length > 0) {
                    String[] parts = StringUtils.split(lines[0], "|")
                    if (parts.length >= 4) {
                        analysis.commitHash = parts[0]
                        analysis.commitMessage = parts[1]
                        analysis.commitDate = parts[2]
                        analysis.author = parts[3]
                    }
                }
                
                // Extract modified files
                for (int i = 1; i < lines.length; i++) {
                    String file = lines[i].trim()
                    if (file && (file.endsWith(".svelte") || matchesExtensions(file))) {
                        analysis.modifiedFiles.add(file)
                    }
                }
            }
            
            return analysis
        }
        
        boolean checkoutCommit(String commitHash) {
            def result = executeGitCommand("checkout ${commitHash}")
            return result.exitCode == 0
        }
        
        boolean checkoutBranch(String branch) {
            def result = executeGitCommand("checkout ${branch}")
            return result.exitCode == 0
        }
        
        List<String> getCommitDiff(String commit1, String commit2) {
            def result = executeGitCommand("diff --name-only ${commit1} ${commit2}")
            if (result.exitCode == 0) {
                return result.output.split("\n").findAll { it.trim() && matchesExtensions(it) }
            }
            return []
        }
        
        private Map executeGitCommand(String command) {
            try {
                Process proc = "git ${command}".execute(null, new File(workingDir))
                proc.waitFor()
                String output = proc.inputStream.text
                String error = proc.errorStream.text
                return [exitCode: proc.exitValue(), output: output, error: error]
            } catch (Exception e) {
                return [exitCode: 1, output: "", error: e.message]
            }
        }
        
        private boolean matchesExtensions(String fileName) {
            String[] extensionArray = extensions.split(",").collect { it.trim() }
            return extensionArray.any { ext -> 
                fileName.endsWith(ext.startsWith(".") ? ext : "." + ext)
            }
        }
    }

    // Svelte file parser
    class SvelteFileParser {
        private String content
        private LocationTracker tracker

        SvelteFileParser(String content) {
            this.content = content
            this.tracker = new LocationTracker(content)
        }

        AnalysisResult parseFile(String filePath) {
            AnalysisResult result = new AnalysisResult(filePath: filePath)
            
            // Parse style blocks
            result.styleBlocks = parseStyleBlocks()
            
            // Parse script sections for variables and imports
            def scriptSections = parseScriptSections()
            scriptSections.each { script ->
                result.variables.addAll(parseVariables(script))
                result.imports.addAll(parseImports(script))
            }

            // Parse CSS imports from style blocks
            result.cssImports = parseCSSImports(result.styleBlocks)

            // Parse CSS selectors and their HTML usage
            result.cssSelectors = parseCSSSelectors(result.styleBlocks)
            findCSSUsageInHTML(result.cssSelectors, content)

            // Analyze CSS overrides
            result.cssOverrides = analyzeCSSOverrides(result.styleBlocks)

            // Parse JavaScript functions from script sections
            scriptSections.each { script ->
                result.jsFunctions.addAll(parseJavaScriptFunctions(script))
            }
            
            // Find function usage throughout the file
            findJavaScriptUsage(result.jsFunctions, content)
            
            // Parse component imports and usage
            result.componentImports = parseComponentImports(result.imports)
            result.componentUsages = parseComponentUsage(content)
            
            // Link component imports with their usage
            linkComponentUsage(result.componentImports, result.componentUsages)

            // Calculate statistics
            result.statistics = calculateStatistics(result)

            return result
        }

        private List<StyleBlock> parseStyleBlocks() {
            List<StyleBlock> blocks = []
            int searchStart = 0

            while (true) {
                int styleStart = StringUtils.indexOf(content, "<style", searchStart)
                if (styleStart == -1) break

                int tagEnd = StringUtils.indexOf(content, ">", styleStart)
                if (tagEnd == -1) break

                int styleEnd = StringUtils.indexOf(content, "</style>", tagEnd)
                if (styleEnd == -1) break

                StyleBlock block = new StyleBlock()
                def startPos = tracker.getLineColumn(styleStart)
                def endPos = tracker.getLineColumn(styleEnd + 8)
                
                block.startLine = startPos.line
                block.startCol = startPos.column
                block.endLine = endPos.line
                block.endCol = endPos.column

                // Parse attributes
                String tag = content.substring(styleStart, tagEnd + 1)
                parseStyleAttributes(tag, block)

                // Extract content
                block.content = content.substring(tagEnd + 1, styleEnd).trim()

                blocks.add(block)
                searchStart = styleEnd + 8
            }

            return blocks
        }

        private void parseStyleAttributes(String tag, StyleBlock block) {
            if (StringUtils.contains(tag, "global")) {
                block.isScoped = false
            }
            
            // Parse lang attribute
            int langStart = StringUtils.indexOf(tag, "lang=")
            if (langStart != -1) {
                int quoteStart = -1
                for (int i = langStart; i < tag.length(); i++) {
                    if (tag.charAt(i) == '"' || tag.charAt(i) == "'") {
                        quoteStart = i
                        break
                    }
                }
                if (quoteStart != -1) {
                    char quote = tag.charAt(quoteStart)
                    int quoteEnd = StringUtils.indexOf(tag, quote as String, quoteStart + 1)
                    if (quoteEnd != -1) {
                        block.language = tag.substring(quoteStart + 1, quoteEnd)
                    }
                }
            }
        }

        private List<Map> parseScriptSections() {
            List<Map> sections = []
            int searchStart = 0

            while (true) {
                int scriptStart = StringUtils.indexOf(content, "<script", searchStart)
                if (scriptStart == -1) break

                int tagEnd = StringUtils.indexOf(content, ">", scriptStart)
                if (tagEnd == -1) break

                int scriptEnd = StringUtils.indexOf(content, "</script>", tagEnd)
                if (scriptEnd == -1) break

                String scriptContent = content.substring(tagEnd + 1, scriptEnd)
                String tag = content.substring(scriptStart, tagEnd + 1)
                
                sections.add([
                    content: scriptContent,
                    tag: tag,
                    startOffset: tagEnd + 1,
                    endOffset: scriptEnd
                ])

                searchStart = scriptEnd + 9
            }

            return sections
        }

        private List<Variable> parseVariables(Map script) {
            List<Variable> variables = []
            String scriptContent = script.content
            int baseOffset = script.startOffset

            // Parse let declarations
            parseVariableDeclarations(scriptContent, baseOffset, "let", variables)
            parseVariableDeclarations(scriptContent, baseOffset, "const", variables)
            parseVariableDeclarations(scriptContent, baseOffset, "var", variables)

            // Parse export let (props)
            parseExportLet(scriptContent, baseOffset, variables)

            // Parse reactive declarations
            parseReactiveDeclarations(scriptContent, baseOffset, variables)

            // Find usages for all variables
            variables.each { variable ->
                variable.usages = findVariableUsages(variable.name, scriptContent, baseOffset)
            }

            return variables
        }

        private void parseVariableDeclarations(String content, int baseOffset, String type, List<Variable> variables) {
            int searchStart = 0
            
            while (true) {
                int letPos = StringUtils.indexOf(content, "$type ", searchStart)
                if (letPos == -1) break

                // Find variable name
                int nameStart = letPos + type.length() + 1
                int nameEnd = findVariableNameEnd(content, nameStart)
                
                if (nameEnd > nameStart) {
                    String varName = content.substring(nameStart, nameEnd).trim()
                    
                    // Handle destructuring - extract first variable for simplicity
                    if (varName.startsWith("{") || varName.startsWith("[")) {
                        // Skip destructuring for now - could be enhanced
                        searchStart = nameEnd
                        continue
                    }

                    Variable variable = new Variable()
                    variable.name = varName
                    variable.type = type
                    
                    def pos = tracker.getLineColumn(baseOffset + letPos)
                    variable.declarationLine = pos.line
                    variable.declarationCol = pos.column

                    variables.add(variable)
                }

                searchStart = nameEnd > searchStart ? nameEnd : searchStart + 1
            }
        }

        private void parseExportLet(String content, int baseOffset, List<Variable> variables) {
            int searchStart = 0
            
            while (true) {
                int exportPos = StringUtils.indexOf(content, "export let ", searchStart)
                if (exportPos == -1) break

                int nameStart = exportPos + 11
                int nameEnd = findVariableNameEnd(content, nameStart)
                
                if (nameEnd > nameStart) {
                    String varName = content.substring(nameStart, nameEnd).trim()
                    
                    Variable variable = new Variable()
                    variable.name = varName
                    variable.type = "prop"
                    
                    def pos = tracker.getLineColumn(baseOffset + exportPos)
                    variable.declarationLine = pos.line
                    variable.declarationCol = pos.column

                    variables.add(variable)
                }

                searchStart = nameEnd
            }
        }

        private void parseReactiveDeclarations(String content, int baseOffset, List<Variable> variables) {
            int searchStart = 0
            
            while (true) {
                int reactivePos = StringUtils.indexOf(content, "\$:", searchStart)
                if (reactivePos == -1) break

                // Find the variable being declared
                int nameStart = reactivePos + 2
                while (nameStart < content.length() && Character.isWhitespace(content.charAt(nameStart))) {
                    nameStart++
                }

                int nameEnd = findVariableNameEnd(content, nameStart)
                
                if (nameEnd > nameStart) {
                    String varName = content.substring(nameStart, nameEnd).trim()
                    
                    Variable variable = new Variable()
                    variable.name = varName
                    variable.type = "reactive"
                    
                    def pos = tracker.getLineColumn(baseOffset + reactivePos)
                    variable.declarationLine = pos.line
                    variable.declarationCol = pos.column

                    variables.add(variable)
                }

                searchStart = nameEnd
            }
        }

        private int findVariableNameEnd(String content, int start) {
            int i = start
            while (i < content.length()) {
                char c = content.charAt(i)
                if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                    break
                }
                i++
            }
            return i
        }

        private List<Usage> findVariableUsages(String varName, String content, int baseOffset) {
            List<Usage> usages = []
            int searchStart = 0

            while (true) {
                int pos = StringUtils.indexOf(content, varName, searchStart)
                if (pos == -1) break

                // Check if this is a word boundary
                boolean validUsage = true
                if (pos > 0) {
                    char before = content.charAt(pos - 1)
                    if (Character.isLetterOrDigit(before) || before == '_' || before == '$') {
                        validUsage = false
                    }
                }
                if (pos + varName.length() < content.length()) {
                    char after = content.charAt(pos + varName.length())
                    if (Character.isLetterOrDigit(after) || after == '_' || after == '$') {
                        validUsage = false
                    }
                }

                if (validUsage) {
                    Usage usage = new Usage()
                    def position = tracker.getLineColumn(baseOffset + pos)
                    usage.line = position.line
                    usage.column = position.column
                    usage.context = extractContext(content, pos)
                    usages.add(usage)
                }

                searchStart = pos + 1
            }

            return usages
        }

        private String extractContext(String content, int position) {
            int lineStart = position
            while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') {
                lineStart--
            }
            int lineEnd = position
            while (lineEnd < content.length() && content.charAt(lineEnd) != '\n') {
                lineEnd++
            }
            return content.substring(lineStart, lineEnd).trim()
        }

        private List<ImportStatement> parseImports(Map script) {
            List<ImportStatement> imports = []
            String scriptContent = script.content
            int baseOffset = script.startOffset

            // Parse ES6 imports
            parseES6Imports(scriptContent, baseOffset, imports)

            // Parse dynamic imports
            parseDynamicImports(scriptContent, baseOffset, imports)

            return imports
        }

        private void parseES6Imports(String content, int baseOffset, List<ImportStatement> imports) {
            int searchStart = 0
            
            while (true) {
                int importPos = StringUtils.indexOf(content, "import ", searchStart)
                if (importPos == -1) break

                int fromPos = StringUtils.indexOf(content, " from ", importPos)
                if (fromPos == -1) {
                    searchStart = importPos + 7
                    continue
                }

                int quoteStart = -1
                for (int i = fromPos; i < content.length(); i++) {
                    if (content.charAt(i) == '"' || content.charAt(i) == "'") {
                        quoteStart = i
                        break
                    }
                }
                if (quoteStart == -1) {
                    searchStart = fromPos + 6
                    continue
                }

                char quote = content.charAt(quoteStart)
                int quoteEnd = StringUtils.indexOf(content, quote as String, quoteStart + 1)
                if (quoteEnd == -1) {
                    searchStart = quoteStart + 1
                    continue
                }

                ImportStatement importStmt = new ImportStatement()
                importStmt.module = content.substring(quoteStart + 1, quoteEnd)
                importStmt.type = "es6"
                
                def pos = tracker.getLineColumn(baseOffset + importPos)
                importStmt.line = pos.line
                importStmt.column = pos.column

                // Parse imported symbols
                String importClause = content.substring(importPos + 7, fromPos).trim()
                importStmt.symbols = parseImportSymbols(importClause)

                imports.add(importStmt)
                searchStart = quoteEnd + 1
            }
        }

        private void parseDynamicImports(String content, int baseOffset, List<ImportStatement> imports) {
            int searchStart = 0
            
            while (true) {
                int importPos = StringUtils.indexOf(content, "import(", searchStart)
                if (importPos == -1) break

                int quoteStart = -1
                for (int i = importPos; i < content.length(); i++) {
                    if (content.charAt(i) == '"' || content.charAt(i) == "'") {
                        quoteStart = i
                        break
                    }
                }
                if (quoteStart == -1) {
                    searchStart = importPos + 7
                    continue
                }

                char quote = content.charAt(quoteStart)
                int quoteEnd = StringUtils.indexOf(content, quote as String, quoteStart + 1)
                if (quoteEnd == -1) {
                    searchStart = quoteStart + 1
                    continue
                }

                ImportStatement importStmt = new ImportStatement()
                importStmt.module = content.substring(quoteStart + 1, quoteEnd)
                importStmt.type = "dynamic"
                
                def pos = tracker.getLineColumn(baseOffset + importPos)
                importStmt.line = pos.line
                importStmt.column = pos.column

                imports.add(importStmt)
                searchStart = quoteEnd + 1
            }
        }

        private List<String> parseImportSymbols(String importClause) {
            List<String> symbols = []
            
            // Handle default import
            if (!importClause.contains("{") && !importClause.contains("*")) {
                symbols.add(importClause.trim())
                return symbols
            }

            // Handle named imports
            int braceStart = StringUtils.indexOf(importClause, "{")
            int braceEnd = StringUtils.indexOf(importClause, "}")
            if (braceStart != -1 && braceEnd != -1) {
                String namedImports = importClause.substring(braceStart + 1, braceEnd)
                symbols.addAll(namedImports.split(",").collect { it.trim() })
            }

            return symbols
        }

        private List<CSSImport> parseCSSImports(List<StyleBlock> styleBlocks) {
            List<CSSImport> imports = []

            styleBlocks.each { block ->
                int searchStart = 0
                
                while (true) {
                    int importPos = StringUtils.indexOf(block.content, "@import", searchStart)
                    if (importPos == -1) break

                    int quoteStart = -1
                    for (int i = importPos; i < block.content.length(); i++) {
                        if (block.content.charAt(i) == '"' || block.content.charAt(i) == "'") {
                            quoteStart = i
                            break
                        }
                    }
                    if (quoteStart == -1) {
                        searchStart = importPos + 7
                        continue
                    }

                    char quote = block.content.charAt(quoteStart)
                    int quoteEnd = StringUtils.indexOf(block.content, quote as String, quoteStart + 1)
                    if (quoteEnd == -1) {
                        searchStart = quoteStart + 1
                        continue
                    }

                    CSSImport cssImport = new CSSImport()
                    cssImport.path = block.content.substring(quoteStart + 1, quoteEnd)
                    cssImport.type = "@import"
                    cssImport.line = block.startLine + StringUtils.countMatches(
                        block.content.substring(0, importPos), "\n")
                    cssImport.column = importPos - block.content.lastIndexOf("\n", importPos)

                    imports.add(cssImport)
                    searchStart = quoteEnd + 1
                }
            }

            return imports
        }

        private List<CSSOverride> analyzeCSSOverrides(List<StyleBlock> styleBlocks) {
            List<CSSOverride> overrides = []
            Map<String, List<Map>> propertyDeclarations = [:]

            // Collect all CSS property declarations
            styleBlocks.each { block ->
                parseCSSProperties(block, propertyDeclarations)
            }

            // Identify overrides - only within the same selector
            propertyDeclarations.each { property, declarations ->
                if (declarations.size() > 1) {
                    // Group declarations by selector
                    Map<String, List> declarationsBySelector = declarations.groupBy { it.selector }
                    
                    // Check for conflicts only within the same selector
                    declarationsBySelector.each { selector, selectorDeclarations ->
                        if (selectorDeclarations.size() > 1) {
                            // Sort by line number to find override order
                            selectorDeclarations.sort { it.line }
                            
                            for (int i = 1; i < selectorDeclarations.size(); i++) {
                                def current = selectorDeclarations[i]
                                def previous = selectorDeclarations[i-1]
                                
                                CSSOverride override = new CSSOverride()
                                override.property = property
                                override.line = current.line
                                override.column = current.column
                                override.selector = current.selector
                                override.specificity = calculateSpecificity(current.selector)
                                override.important = current.important
                                override.conflictsWith = "Previous declaration at [${previous.line}:${previous.column}]"
                                
                                overrides.add(override)
                            }
                        }
                    }
                }
            }

            return overrides
        }

        private void parseCSSProperties(StyleBlock block, Map<String, List<Map>> propertyDeclarations) {
            String[] lines = block.content.split("\n")
            String currentSelector = ""

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim()
                
                if (line.contains("{")) {
                    // Selector line
                    currentSelector = StringUtils.substringBefore(line, "{").trim()
                } else if (line.contains(":") && !line.startsWith("//") && !line.startsWith("/*")) {
                    // Property line
                    String property = StringUtils.substringBefore(line, ":").trim()
                    boolean important = line.contains("!important")
                    
                    if (!propertyDeclarations.containsKey(property)) {
                        propertyDeclarations[property] = []
                    }
                    
                    propertyDeclarations[property].add([
                        line: block.startLine + i,
                        column: line.indexOf(property) + 1,
                        selector: currentSelector,
                        important: important
                    ])
                }
            }
        }

        private int calculateSpecificity(String selector) {
            int specificity = 0
            
            // Count IDs (weight: 100)
            specificity += StringUtils.countMatches(selector, "#") * 100
            
            // Count classes, attributes, pseudo-classes (weight: 10)
            specificity += StringUtils.countMatches(selector, ".") * 10
            specificity += StringUtils.countMatches(selector, "[") * 10
            specificity += StringUtils.countMatches(selector, ":") * 10
            
            // Calculate CSS specificity using StringUtils methods instead of regex
            // Split on common CSS combinator characters
            List<String> parts = []
            String current = ""
            for (int i = 0; i < selector.length(); i++) {
                char c = selector.charAt(i)
                if (c == ' ' || c == '>' || c == '+' || c == '~') {
                    if (current.trim()) {
                        parts.add(current.trim())
                        current = ""
                    }
                } else {
                    current += c
                }
            }
            if (current.trim()) {
                parts.add(current.trim())
            }
            for (String part : parts) {
                if (part.trim() && !StringUtils.contains(part, ".") && !StringUtils.contains(part, "#") && !StringUtils.contains(part, "[")) {
                    specificity += 1
                }
            }
            
            return specificity
        }
        
        // Helper function to extract selector name without regex
        private String extractSelectorName(String selector) {
            // Find first occurrence of CSS combinator characters
            char[] stopChars = [' ', ':', '>', '+', '~', '.', '['] as char[]
            
            int stopIndex = selector.length()
            for (char stopChar : stopChars) {
                int index = StringUtils.indexOf(selector, stopChar as String)
                if (index != -1 && index < stopIndex) {
                    stopIndex = index
                }
            }
            
            return selector.substring(0, stopIndex)
        }

        private Map<String, Object> calculateStatistics(AnalysisResult result) {
            return [
                styleBlockCount: result.styleBlocks.size(),
                cssImportCount: result.cssImports.size(),
                variableCount: result.variables.size(),
                importCount: result.imports.size(),
                cssOverrideCount: result.cssOverrides.size(),
                cssSelectorCount: result.cssSelectors.size(),
                usedCSSSelectors: result.cssSelectors.count { !it.htmlUsages.isEmpty() },
                unusedCSSSelectors: result.cssSelectors.count { it.htmlUsages.isEmpty() },
                jsFunctionCount: result.jsFunctions.size(),
                usedJSFunctions: result.jsFunctions.count { !it.usages.isEmpty() },
                unusedJSFunctions: result.jsFunctions.count { it.usages.isEmpty() },
                componentImportCount: result.componentImports.size(),
                usedComponents: result.componentImports.count { it.isUsed },
                unusedComponents: result.componentImports.count { !it.isUsed },
                totalLines: StringUtils.countMatches(content, "\n") + 1
            ]
        }
        
        private List<JavaScriptFunction> parseJavaScriptFunctions(Map script) {
            List<JavaScriptFunction> functions = []
            String scriptContent = script.content
            
            // Enhanced function detection using Commons Lang3 StringUtils - more readable than regex
            // Parse different function declaration patterns systematically
            
            // 1. Regular function declarations: function name()
            findFunctionPattern(scriptContent, script, functions, "function ", "(", "function")
            
            // 2. Const arrow functions: const name = () =>
            findConstArrowFunctions(scriptContent, script, functions)
            
            // 3. Let arrow functions: let name = () =>
            findLetArrowFunctions(scriptContent, script, functions)
            
            // 4. Object method declarations: name: () =>
            findObjectMethods(scriptContent, script, functions)
            
            // 5. Simple method declarations: name() {
            findSimpleMethods(scriptContent, script, functions)
            
            // 6. Async function declarations
            findAsyncFunctions(scriptContent, script, functions)
            
            return functions
        }
        
        private void findFunctionPattern(String content, Map script, List<JavaScriptFunction> functions, String prefix, String suffix, String type) {
            int searchStart = 0
            while (true) {
                int funcPos = StringUtils.indexOf(content, prefix, searchStart)
                if (funcPos == -1) break
                
                int nameStart = funcPos + prefix.length()
                while (nameStart < content.length() && Character.isWhitespace(content.charAt(nameStart))) {
                    nameStart++
                }
                
                int nameEnd = nameStart
                while (nameEnd < content.length() && (Character.isLetterOrDigit(content.charAt(nameEnd)) || content.charAt(nameEnd) == '_' || content.charAt(nameEnd) == '$')) {
                    nameEnd++
                }
                
                if (nameEnd > nameStart) {
                    int suffixPos = StringUtils.indexOf(content, suffix, nameEnd)
                    if (suffixPos != -1 && suffixPos - nameEnd < 10) { // Reasonable distance
                        String funcName = content.substring(nameStart, nameEnd)
                        
                        // Skip if already found
                        if (functions.any { it.name == funcName }) {
                            searchStart = nameEnd
                            continue
                        }
                        
                        JavaScriptFunction func = new JavaScriptFunction()
                        func.name = funcName
                        func.type = type
                        
                        // Check if async
                        String beforeFunc = content.substring(Math.max(0, funcPos - 10), funcPos)
                        func.isAsync = StringUtils.contains(beforeFunc, "async")
                        
                        // Check if exported
                        String beforeMatch = content.substring(Math.max(0, funcPos - 20), funcPos)
                        func.isExported = StringUtils.contains(beforeMatch, "export")
                        
                        // Get line position
                        def pos = tracker.getLineColumn(script.startOffset + funcPos)
                        func.declarationLine = pos.line
                        func.declarationColumn = pos.column
                        
                        functions.add(func)
                    }
                }
                
                searchStart = nameEnd > searchStart ? nameEnd : searchStart + 1
            }
        }
        
        private void findConstArrowFunctions(String content, Map script, List<JavaScriptFunction> functions) {
            int searchStart = 0
            while (true) {
                int constPos = StringUtils.indexOf(content, "const ", searchStart)
                if (constPos == -1) break
                
                int nameStart = constPos + 6
                while (nameStart < content.length() && Character.isWhitespace(content.charAt(nameStart))) {
                    nameStart++
                }
                
                int nameEnd = nameStart
                while (nameEnd < content.length() && (Character.isLetterOrDigit(content.charAt(nameEnd)) || content.charAt(nameEnd) == '_' || content.charAt(nameEnd) == '$')) {
                    nameEnd++
                }
                
                if (nameEnd > nameStart) {
                    // Look for = () => pattern
                    int equalPos = StringUtils.indexOf(content, "=", nameEnd)
                    if (equalPos != -1 && equalPos - nameEnd < 10) {
                        int arrowStart = StringUtils.indexOf(content, "=>", equalPos)
                        if (arrowStart != -1 && arrowStart - equalPos < 20) {
                            String funcName = content.substring(nameStart, nameEnd)
                            
                            // Skip if already found
                            if (functions.any { it.name == funcName }) {
                                searchStart = nameEnd
                                continue
                            }
                            
                            JavaScriptFunction func = new JavaScriptFunction()
                            func.name = funcName
                            func.type = "arrow"
                            
                            // Check if async
                            String beforeFunc = content.substring(Math.max(0, constPos - 10), constPos)
                            func.isAsync = StringUtils.contains(beforeFunc, "async")
                            
                            // Check if exported
                            String beforeMatch = content.substring(Math.max(0, constPos - 20), constPos)
                            func.isExported = StringUtils.contains(beforeMatch, "export")
                            
                            // Get line position
                            def pos = tracker.getLineColumn(script.startOffset + constPos)
                            func.declarationLine = pos.line
                            func.declarationColumn = pos.column
                            
                            functions.add(func)
                        }
                    }
                }
                
                searchStart = nameEnd > searchStart ? nameEnd : searchStart + 1
            }
        }
        
        private void findLetArrowFunctions(String content, Map script, List<JavaScriptFunction> functions) {
            int searchStart = 0
            while (true) {
                int letPos = StringUtils.indexOf(content, "let ", searchStart)
                if (letPos == -1) break
                
                int nameStart = letPos + 4
                while (nameStart < content.length() && Character.isWhitespace(content.charAt(nameStart))) {
                    nameStart++
                }
                
                int nameEnd = nameStart
                while (nameEnd < content.length() && (Character.isLetterOrDigit(content.charAt(nameEnd)) || content.charAt(nameEnd) == '_' || content.charAt(nameEnd) == '$')) {
                    nameEnd++
                }
                
                if (nameEnd > nameStart) {
                    // Look for = () => pattern
                    int equalPos = StringUtils.indexOf(content, "=", nameEnd)
                    if (equalPos != -1 && equalPos - nameEnd < 10) {
                        int arrowStart = StringUtils.indexOf(content, "=>", equalPos)
                        if (arrowStart != -1 && arrowStart - equalPos < 20) {
                            String funcName = content.substring(nameStart, nameEnd)
                            
                            // Skip if already found
                            if (functions.any { it.name == funcName }) {
                                searchStart = nameEnd
                                continue
                            }
                            
                            JavaScriptFunction func = new JavaScriptFunction()
                            func.name = funcName
                            func.type = "arrow"
                            
                            // Check if async
                            String beforeFunc = content.substring(Math.max(0, letPos - 10), letPos)
                            func.isAsync = StringUtils.contains(beforeFunc, "async")
                            
                            // Check if exported
                            String beforeMatch = content.substring(Math.max(0, letPos - 20), letPos)
                            func.isExported = StringUtils.contains(beforeMatch, "export")
                            
                            // Get line position
                            def pos = tracker.getLineColumn(script.startOffset + letPos)
                            func.declarationLine = pos.line
                            func.declarationColumn = pos.column
                            
                            functions.add(func)
                        }
                    }
                }
                
                searchStart = nameEnd > searchStart ? nameEnd : searchStart + 1
            }
        }
        
        private void findObjectMethods(String content, Map script, List<JavaScriptFunction> functions) {
            // Find patterns like: methodName: () =>
            int searchStart = 0
            while (true) {
                int colonPos = StringUtils.indexOf(content, ":", searchStart)
                if (colonPos == -1) break
                
                // Look backwards for method name
                int nameStart = colonPos - 1
                while (nameStart >= 0 && Character.isWhitespace(content.charAt(nameStart))) {
                    nameStart--
                }
                
                int nameEnd = nameStart + 1
                while (nameStart >= 0 && (Character.isLetterOrDigit(content.charAt(nameStart)) || content.charAt(nameStart) == '_' || content.charAt(nameStart) == '$')) {
                    nameStart--
                }
                nameStart++
                
                if (nameEnd > nameStart) {
                    // Look for () => after colon
                    int arrowStart = StringUtils.indexOf(content, "=>", colonPos)
                    if (arrowStart != -1 && arrowStart - colonPos < 20) {
                        String funcName = content.substring(nameStart, nameEnd)
                        
                        // Filter out TypeScript type properties 
                        // Check if this is a type definition (e.g., x: number, y: string)
                        String afterColon = content.substring(colonPos + 1, Math.min(content.length(), colonPos + 15)).trim()
                        Set<String> typeKeywords = ["number", "string", "boolean", "object", "any", "void", "null", "undefined"] as Set
                        boolean isTypeDefinition = typeKeywords.any { keyword -> afterColon.startsWith(keyword) }
                        
                        if (isTypeDefinition) {
                            searchStart = colonPos + 1
                            continue
                        }
                        
                        // Skip if already found
                        if (functions.any { it.name == funcName }) {
                            searchStart = colonPos + 1
                            continue
                        }
                        
                        JavaScriptFunction func = new JavaScriptFunction()
                        func.name = funcName
                        func.type = "method"
                        
                        // Get line position
                        def pos = tracker.getLineColumn(script.startOffset + nameStart)
                        func.declarationLine = pos.line
                        func.declarationColumn = pos.column
                        
                        functions.add(func)
                    }
                }
                
                searchStart = colonPos + 1
            }
        }
        
        private void findSimpleMethods(String content, Map script, List<JavaScriptFunction> functions) {
            // Find patterns like: methodName() {
            int searchStart = 0
            while (true) {
                int bracePos = StringUtils.indexOf(content, ") {", searchStart)
                if (bracePos == -1) break
                
                // Look backwards for method name
                int parenPos = StringUtils.lastIndexOf(content, "(", bracePos)
                if (parenPos == -1) {
                    searchStart = bracePos + 1
                    continue
                }
                
                int nameEnd = parenPos
                while (nameEnd > 0 && Character.isWhitespace(content.charAt(nameEnd - 1))) {
                    nameEnd--
                }
                
                int nameStart = nameEnd - 1
                while (nameStart >= 0 && (Character.isLetterOrDigit(content.charAt(nameStart)) || content.charAt(nameStart) == '_' || content.charAt(nameStart) == '$')) {
                    nameStart--
                }
                nameStart++
                
                if (nameEnd > nameStart) {
                    String funcName = content.substring(nameStart, nameEnd)
                    
                    // Filter out JavaScript keywords and control structures
                    Set<String> jsKeywords = ["if", "while", "for", "switch", "catch", "try", "with", "do"] as Set
                    if (jsKeywords.contains(funcName)) {
                        searchStart = bracePos + 1
                        continue
                    }
                    
                    // Filter out TypeScript type properties (e.g., { x: number; y: number })
                    // Check if this is inside a type definition by looking backwards for ":"
                    int beforeName = nameStart - 1
                    while (beforeName >= 0 && Character.isWhitespace(content.charAt(beforeName))) {
                        beforeName--
                    }
                    if (beforeName >= 0 && content.charAt(beforeName) == ':') {
                        searchStart = bracePos + 1
                        continue
                    }
                    
                    // Also check if preceded by "{ " pattern (object type)
                    String beforeContext = content.substring(Math.max(0, nameStart - 10), nameStart)
                    if (beforeContext.contains("{") && beforeContext.contains(":")) {
                        searchStart = bracePos + 1
                        continue
                    }
                    
                    // Skip if already found
                    if (functions.any { it.name == funcName }) {
                        searchStart = bracePos + 1
                        continue
                    }
                    
                    JavaScriptFunction func = new JavaScriptFunction()
                    func.name = funcName
                    func.type = "method"
                    
                    // Get line position
                    def pos = tracker.getLineColumn(script.startOffset + nameStart)
                    func.declarationLine = pos.line
                    func.declarationColumn = pos.column
                    
                    functions.add(func)
                }
                
                searchStart = bracePos + 1
            }
        }
        
        private void findAsyncFunctions(String content, Map script, List<JavaScriptFunction> functions) {
            int searchStart = 0
            while (true) {
                int asyncPos = StringUtils.indexOf(content, "async ", searchStart)
                if (asyncPos == -1) break
                
                int afterAsync = asyncPos + 6
                while (afterAsync < content.length() && Character.isWhitespace(content.charAt(afterAsync))) {
                    afterAsync++
                }
                
                // Check if it's "async function"
                if (StringUtils.startsWith(content.substring(afterAsync), "function")) {
                    int nameStart = afterAsync + 8
                    while (nameStart < content.length() && Character.isWhitespace(content.charAt(nameStart))) {
                        nameStart++
                    }
                    
                    int nameEnd = nameStart
                    while (nameEnd < content.length() && (Character.isLetterOrDigit(content.charAt(nameEnd)) || content.charAt(nameEnd) == '_' || content.charAt(nameEnd) == '$')) {
                        nameEnd++
                    }
                    
                    if (nameEnd > nameStart) {
                        String funcName = content.substring(nameStart, nameEnd)
                        
                        // Skip if already found
                        if (functions.any { it.name == funcName }) {
                            searchStart = nameEnd
                            continue
                        }
                        
                        JavaScriptFunction func = new JavaScriptFunction()
                        func.name = funcName
                        func.type = "function"
                        func.isAsync = true
                        
                        // Check if exported
                        String beforeMatch = content.substring(Math.max(0, asyncPos - 20), asyncPos)
                        func.isExported = StringUtils.contains(beforeMatch, "export")
                        
                        // Get line position
                        def pos = tracker.getLineColumn(script.startOffset + asyncPos)
                        func.declarationLine = pos.line
                        func.declarationColumn = pos.column
                        
                        functions.add(func)
                    }
                } else {
                    // Check for async arrow function: async name(
                    int nameEnd = afterAsync
                    while (nameEnd < content.length() && (Character.isLetterOrDigit(content.charAt(nameEnd)) || content.charAt(nameEnd) == '_' || content.charAt(nameEnd) == '$')) {
                        nameEnd++
                    }
                    
                    if (nameEnd > afterAsync) {
                        int parenPos = StringUtils.indexOf(content, "(", nameEnd)
                        if (parenPos != -1 && parenPos - nameEnd < 5) {
                            String funcName = content.substring(afterAsync, nameEnd)
                            
                            // Skip if already found
                            if (functions.any { it.name == funcName }) {
                                searchStart = nameEnd
                                continue
                            }
                            
                            JavaScriptFunction func = new JavaScriptFunction()
                            func.name = funcName
                            func.type = "function"
                            func.isAsync = true
                            
                            // Check if exported
                            String beforeMatch = content.substring(Math.max(0, asyncPos - 20), asyncPos)
                            func.isExported = StringUtils.contains(beforeMatch, "export")
                            
                            // Get line position
                            def pos = tracker.getLineColumn(script.startOffset + asyncPos)
                            func.declarationLine = pos.line
                            func.declarationColumn = pos.column
                            
                            functions.add(func)
                        }
                    }
                }
                
                searchStart = asyncPos + 6
            }
        }
        
        private void findJavaScriptUsage(List<JavaScriptFunction> functions, String content) {
            functions.each { func ->
                // Enhanced usage detection using Commons Lang3 for better readability
                List<String> usagePatterns = [
                    "${func.name}(",                                  // Direct function calls
                    "{${func.name}}",                                 // Svelte bindings
                    "addEventListener(",                              // Event listeners (we'll check if function name follows)
                    "removeEventListener(",                           // Event listener removal
                    "on:${func.name}",                               // Svelte direct event handlers  
                    "\$: ${func.name}",                              // Reactive statements
                    "[${func.name}]",                                // Array literal references
                    "{${func.name}}",                                // Object literal references
                    ", ${func.name})",                               // Function parameters
                    "(${func.name},",                                // First parameter
                    " ${func.name} "                                 // General references with spaces
                ]
                
                usagePatterns.each { pattern ->
                    int searchStart = 0
                    while (true) {
                        int usagePos = StringUtils.indexOf(content, pattern, searchStart)
                        if (usagePos == -1) break
                        
                        // Skip the declaration itself
                        def pos = tracker.getLineColumn(usagePos)
                        if (pos.line == func.declarationLine) {
                            searchStart = usagePos + pattern.length()
                            continue
                        }
                        
                        // Special handling for addEventListener/removeEventListener
                        if (pattern.contains("addEventListener") || pattern.contains("removeEventListener")) {
                            // Check if our function name appears after the event type
                            String afterPattern = content.substring(usagePos + pattern.length())
                            if (StringUtils.contains(afterPattern, func.name)) {
                                String eventListenerSection = StringUtils.substring(afterPattern, 0, 100) // Look ahead 100 chars
                                if (StringUtils.contains(eventListenerSection, func.name)) {
                                    Usage usage = new Usage()
                                    usage.line = pos.line
                                    usage.column = pos.column
                                    usage.context = extractContext(content, usagePos)
                                    func.usages.add(usage)
                                }
                            }
                        } else {
                            // Regular usage
                            Usage usage = new Usage()
                            usage.line = pos.line
                            usage.column = pos.column
                            usage.context = extractContext(content, usagePos)
                            func.usages.add(usage)
                        }
                        
                        searchStart = usagePos + pattern.length()
                    }
                }
            }
        }
        
        private List<ComponentImport> parseComponentImports(List<ImportStatement> imports) {
            List<ComponentImport> componentImports = []
            
            imports.each { importStmt ->
                if (importStmt.module.endsWith('.svelte')) {
                    // For .svelte imports, the first symbol is usually the component name
                    if (importStmt.symbols && !importStmt.symbols.isEmpty()) {
                        ComponentImport compImport = new ComponentImport()
                        compImport.componentName = importStmt.symbols[0]
                        compImport.filePath = importStmt.module
                        compImport.line = importStmt.line
                        compImport.column = importStmt.column
                        componentImports.add(compImport)
                    }
                }
            }
            
            return componentImports
        }
        
        private List<ComponentUsage> parseComponentUsage(String content) {
            List<ComponentUsage> usages = []
            
            // Find component tags in markup (capitalized tags) using StringUtils instead of regex
            int searchStart = 0
            while (true) {
                int openBracket = StringUtils.indexOf(content, "<", searchStart)
                if (openBracket == -1) break
                
                // Check if next character is uppercase (component tag)
                int tagStart = openBracket + 1
                if (tagStart >= content.length()) break
                
                char firstChar = content.charAt(tagStart)
                if (!Character.isUpperCase(firstChar)) {
                    searchStart = openBracket + 1
                    continue
                }
                
                // Find the end of the tag name
                int tagEnd = tagStart
                while (tagEnd < content.length() && 
                       (Character.isLetterOrDigit(content.charAt(tagEnd)) || content.charAt(tagEnd) == '_')) {
                    tagEnd++
                }
                
                if (tagEnd > tagStart) {
                    // Find the closing > of the opening tag
                    int closingBracket = StringUtils.indexOf(content, ">", tagEnd)
                    if (closingBracket != -1) {
                        String componentName = content.substring(tagStart, tagEnd)
                        
                        ComponentUsage usage = new ComponentUsage()
                        usage.componentName = componentName
                        usage.tagName = componentName
                        
                        def pos = tracker.getLineColumn(openBracket)
                        usage.line = pos.line
                        usage.column = pos.column
                        usage.context = extractContext(content, openBracket)
                        
                        usages.add(usage)
                    }
                }
                
                searchStart = openBracket + 1
            }
            
            return usages
        }
        
        private void linkComponentUsage(List<ComponentImport> imports, List<ComponentUsage> usages) {
            imports.each { compImport ->
                compImport.usages = usages.findAll { usage -> 
                    usage.componentName == compImport.componentName 
                }
                compImport.isUsed = !compImport.usages.isEmpty()
            }
        }
        private List<CSSSelector> parseCSSSelectors(List<StyleBlock> styleBlocks) {
            List<CSSSelector> selectors = []
            
            styleBlocks.eachWithIndex { block, blockIndex ->
                String[] lines = block.content.split("\n")
                String currentSelector = ""
                int selectorStartLine = 0
                String accumulatedSelector = ""
                int keyframesBraceLevel = 0
                int mediaBraceLevel = 0
                
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim()
                    
                    // Track keyframes
                    if (line.contains("@keyframes")) {
                        keyframesBraceLevel = 1 // We expect the opening brace
                        continue
                    }
                    
                    // Track media queries
                    if (line.contains("@media")) {
                        mediaBraceLevel = 1 // We expect the opening brace
                        continue
                    }
                    
                    // Count braces to track nesting levels
                    if (keyframesBraceLevel > 0 || mediaBraceLevel > 0) {
                        int openBraces = StringUtils.countMatches(line, "{")
                        int closeBraces = StringUtils.countMatches(line, "}")
                        
                        if (keyframesBraceLevel > 0) {
                            keyframesBraceLevel += openBraces - closeBraces
                            if (keyframesBraceLevel <= 0) {
                                keyframesBraceLevel = 0
                            }
                        }
                        
                        if (mediaBraceLevel > 0) {
                            mediaBraceLevel += openBraces - closeBraces
                            if (mediaBraceLevel <= 0) {
                                mediaBraceLevel = 0
                            }
                        }
                        
                        // Skip processing selectors inside keyframes or media queries
                        if (keyframesBraceLevel > 0 || mediaBraceLevel > 0) {
                            continue
                        }
                    }
                    
                    if (line.contains("{")) {
                        // Selector declaration line
                        if (accumulatedSelector) {
                            // We have accumulated a multi-line selector
                            currentSelector = (accumulatedSelector + " " + StringUtils.substringBefore(line, "{")).trim()
                        } else {
                            currentSelector = StringUtils.substringBefore(line, "{").trim()
                            selectorStartLine = block.startLine + i
                        }
                        
                        // Parse individual selectors (comma-separated)
                        String[] individualSelectors = currentSelector.split(",")
                        individualSelectors.each { sel ->
                            sel = sel.trim()
                            if (sel) {
                                // Check if this is a descendant selector (contains spaces)
                                if (sel.contains(" ") && !sel.contains(":") && !sel.contains(">") && !sel.contains("+") && !sel.contains("~")) {
                                    // This is a descendant selector like ".parent .child"
                                    CSSSelector cssSelector = new CSSSelector()
                                    cssSelector.selector = sel
                                    cssSelector.declarationLine = selectorStartLine
                                    cssSelector.declarationColumn = line.indexOf(sel) + 1
                                    cssSelector.sourceBlock = block.isScoped ? "Scoped Block ${blockIndex + 1}" : "Global Block ${blockIndex + 1}"
                                    cssSelector.type = "descendant"
                                    cssSelector.name = sel // Keep the full selector for descendant type
                                    
                                    // Mark descendant selectors as used by default (to avoid false positives)
                                    CSSUsage usage = new CSSUsage()
                                    usage.line = selectorStartLine
                                    usage.column = 1
                                    usage.context = "descendant selector auto-detected as used"
                                    usage.element = "descendant"
                                    usage.attribute = "descendant"
                                    cssSelector.htmlUsages.add(usage)
                                    
                                    selectors.add(cssSelector)
                                } else {
                                    // Regular selector
                                    CSSSelector cssSelector = new CSSSelector()
                                    cssSelector.selector = sel
                                    cssSelector.declarationLine = selectorStartLine
                                    cssSelector.declarationColumn = line.indexOf(sel) + 1
                                    cssSelector.sourceBlock = block.isScoped ? "Scoped Block ${blockIndex + 1}" : "Global Block ${blockIndex + 1}"
                                    
                                    // Determine selector type and extract name
                                    if (sel.contains("[") && !sel.startsWith("#") && !sel.startsWith(".")) {
                                        // Element with attribute selector like "select[disabled]"
                                        cssSelector.type = "element"
                                        cssSelector.name = StringUtils.substringBefore(sel, "[")
                                        
                                        // Mark attribute selectors as used (they're typically dynamic)
                                        CSSUsage usage = new CSSUsage()
                                        usage.line = selectorStartLine
                                        usage.column = 1
                                        usage.context = "attribute selector auto-detected as used"
                                        usage.element = cssSelector.name
                                        usage.attribute = "attribute"
                                        cssSelector.htmlUsages.add(usage)
                                        
                                    } else if (sel.startsWith(".")) {
                                        cssSelector.type = "class"
                                        cssSelector.name = extractSelectorName(sel.substring(1))
                                    } else if (sel.startsWith("#")) {
                                        cssSelector.type = "id"  
                                        cssSelector.name = extractSelectorName(sel.substring(1))
                                    } else if (sel.contains("[")) {
                                        cssSelector.type = "attribute"
                                        cssSelector.name = StringUtils.substringBetween(sel, "[", "]")
                                    } else {
                                        cssSelector.type = "element"
                                        cssSelector.name = extractSelectorName(sel)
                                    }
                                    
                                    selectors.add(cssSelector)
                                }
                            }
                        }
                        
                        // Reset accumulated selector
                        accumulatedSelector = ""
                        
                    } else if (line.endsWith(",")) {
                        // Multi-line selector - accumulate
                        if (!accumulatedSelector) {
                            selectorStartLine = block.startLine + i
                        }
                        accumulatedSelector += (accumulatedSelector ? " " : "") + line.substring(0, line.length() - 1).trim()
                        
                    } else if (accumulatedSelector && !line.contains(":")) {
                        // Continue multi-line selector without comma
                        accumulatedSelector += " " + line
                        
                    } else if (line.contains(":") && !line.startsWith("//") && !line.startsWith("/*") && currentSelector) {
                        // Property line - add to current selector's properties
                        String property = StringUtils.substringBefore(line, ":").trim()
                        selectors.findAll { it.selector == currentSelector }.each { 
                            it.properties.add(property)
                        }
                    }
                }
            }
            
            return selectors
        }

        private void findCSSUsageInHTML(List<CSSSelector> selectors, String content) {
            // Extract markup section (everything outside script and style tags)
            String markup = extractMarkupSection(content)
            int markupStartOffset = findMarkupStartOffset(content)
            
            selectors.each { selector ->
                switch (selector.type) {
                    case "class":
                        findClassUsage(selector, markup, markupStartOffset)
                        break
                    case "id":
                        findIdUsage(selector, markup, markupStartOffset)
                        break
                    case "element":
                        findElementUsage(selector, markup, markupStartOffset)
                        break
                    case "descendant":
                        findDescendantUsage(selector, markup, markupStartOffset)
                        break
                }
            }
        }

        private String extractMarkupSection(String content) {
            String markup = content
            
            // Remove script sections
            markup = removeTagContent(markup, "<script", "</script>")
            // Remove style sections  
            markup = removeTagContent(markup, "<style", "</style>")
            
            return markup
        }

        private String removeTagContent(String content, String startTag, String endTag) {
            String result = content
            int searchStart = 0
            
            while (true) {
                int tagStart = StringUtils.indexOf(result, startTag, searchStart)
                if (tagStart == -1) break
                
                int tagEnd = StringUtils.indexOf(result, endTag, tagStart)
                if (tagEnd == -1) break
                
                tagEnd += endTag.length()
                result = result.substring(0, tagStart) + result.substring(tagEnd)
                searchStart = tagStart
            }
            
            return result
        }

        private int findMarkupStartOffset(String content) {
            // Find the end of the last script or style tag
            int lastScriptEnd = content.lastIndexOf("</script>")
            int lastStyleEnd = content.lastIndexOf("</style>")
            return Math.max(lastScriptEnd, lastStyleEnd) + 9 // +9 for "</script>" length
        }

        private void findClassUsage(CSSSelector selector, String markup, int baseOffset) {
            String className = selector.name
            int searchStart = 0
            
            // First, check for standard class usage
            while (true) {
                int classPos = StringUtils.indexOf(markup, "class=", searchStart)
                if (classPos == -1) break
                
                // Find the quote start
                int quoteStart = classPos + 6
                while (quoteStart < markup.length() && markup.charAt(quoteStart) != '"' && markup.charAt(quoteStart) != "'") {
                    quoteStart++
                }
                if (quoteStart >= markup.length()) {
                    searchStart = classPos + 6
                    continue
                }
                
                char quote = markup.charAt(quoteStart)
                int quoteEnd = StringUtils.indexOf(markup, quote as String, quoteStart + 1)
                if (quoteEnd == -1) {
                    searchStart = quoteStart + 1
                    continue
                }
                
                String classValue = markup.substring(quoteStart + 1, quoteEnd)
                
                // Check for direct usage in class value
                boolean foundUsage = false
                
                // Split classes by whitespace using StringUtils
                String[] classes = StringUtils.split(classValue)
                if (classes.contains(className)) {
                    foundUsage = true
                }
                
                // 2. Svelte conditional class patterns - ENHANCED DETECTION
                if (!foundUsage && classValue.contains("{")) {
                    
                    // Pattern A: {condition ? 'class-name' : 'other-class'}
                    if (classValue.contains("? '${className}'") || 
                        classValue.contains("? \"${className}\"") ||
                        classValue.contains(": '${className}'") || 
                        classValue.contains(": \"${className}\"")) {
                        foundUsage = true
                    }
                    
                    // Pattern B: Dynamic patterns like toast-{variable} matching .toast-success
                    if (className.contains("-")) {
                        String[] parts = className.split("-", 2)
                        String prefix = parts[0]
                        if (classValue.contains(prefix + "-{") || 
                            markup.contains("'" + prefix + "-' + ") ||
                            markup.contains("\"" + prefix + "-\" + ")) {
                            foundUsage = true
                        }
                    }
                    
                    // Pattern C: Base class with dynamic modifier like base.{variable}
                    if (className.contains(".")) {
                        String[] parts = StringUtils.split(className, ".")
                        String baseClass = parts[0]
                        if (classValue.contains(baseClass + ".{")) {
                            foundUsage = true
                        }
                    }
                    
                    // Pattern D: Compound class pattern like "base-class {variable}" creating .base-class.modifier
                    if (className.contains(".") && classValue.trim().contains(" {")) {
                        // Extract compound class parts: .strength-fill.weak -> ["strength-fill", "weak"]
                        String[] compoundParts = StringUtils.split(className, ".")
                        if (compoundParts.length == 2) {
                            String baseClass = compoundParts[0]
                            String modifierClass = compoundParts[1]
                            
                            // Check if classValue has pattern like "strength-fill {variable}"
                            // where {variable} could evaluate to the modifier class
                            if (classValue.contains(baseClass) && classValue.contains("{")) {
                                foundUsage = true
                            }
                        }
                    }
                    
                    // Pattern E: Template literals with ${variable}
                    if (classValue.contains("\${")) {
                        if (className.contains("-")) {
                            String[] parts = className.split("-", 2)
                            String prefix = parts[0]
                            if (classValue.contains(prefix + "-\${")) {
                                foundUsage = true
                            }
                        }
                    }
                    
                    // Pattern F: Template literals outside class attributes (for dynamic assignment)
                    // Example: positionClass = `chat-position-${chatPosition}` creating .chat-position-bottom-left
                    if (className.contains("-")) {
                        String[] parts = className.split("-", 2)
                        String prefix = parts[0]
                        // Look for template literals in the broader markup
                        if (markup.contains("`${prefix}-\${") || markup.contains("\"${prefix}-\${") || markup.contains("'${prefix}-\${")) {
                            foundUsage = true
                        }
                    }
                    
                    // Pattern G: Variables containing class names used in class attributes
                    // Example: class="base-class {variableName}" where variableName contains our className
                    if (className.contains("-")) {
                        String[] parts = className.split("-", 2)
                        String prefix = parts[0]
                        String suffix = parts[1]
                        
                        // Look for template literals that create our class name
                        if (markup.contains("`${prefix}-\${")) {
                            foundUsage = true
                        }
                    }
                }
                
                if (foundUsage) {
                    CSSUsage usage = new CSSUsage()
                    def pos = tracker.getLineColumn(baseOffset + classPos)
                    usage.line = pos.line
                    usage.column = pos.column
                    usage.context = extractContext(markup, classPos)
                    usage.element = extractElementName(markup, classPos)
                    usage.attribute = "class"
                    
                    selector.htmlUsages.add(usage)
                }
                
                searchStart = quoteEnd + 1
            }
            
            // Pattern E: Svelte class directive usage class:name={condition}
            searchStart = 0
            while (true) {
                String directiveSearchTerm = "class:${className}="
                
                // For compound classes like .requirement.met, also search for the modifier part
                if (className.contains(".")) {
                    String[] parts = StringUtils.split(className, ".")
                    String modifierClass = parts[1]
                    directiveSearchTerm = "class:${modifierClass}="
                }
                
                int directivePos = StringUtils.indexOf(markup, directiveSearchTerm, searchStart)
                if (directivePos == -1) break
                
                CSSUsage usage = new CSSUsage()
                def pos = tracker.getLineColumn(baseOffset + directivePos)
                usage.line = pos.line
                usage.column = pos.column
                usage.context = extractContext(markup, directivePos)
                usage.element = extractElementName(markup, directivePos)
                usage.attribute = "class:directive"
                
                selector.htmlUsages.add(usage)
                searchStart = directivePos + directiveSearchTerm.length()
            }
            
            // Also search for class usage in the entire markup (for dynamic patterns outside class attributes)
            searchStart = 0
            while (true) {
                // Look for quoted class names in JavaScript expressions
                String quotedClassName = "'${className}'"
                int usagePos = StringUtils.indexOf(markup, quotedClassName, searchStart)
                if (usagePos == -1) {
                    quotedClassName = "\"${className}\""
                    usagePos = StringUtils.indexOf(markup, quotedClassName, searchStart)
                }
                if (usagePos == -1) break
                
                // Make sure it's not already counted in a class attribute
                boolean inClassAttribute = false
                int checkPos = usagePos
                while (checkPos > 0 && markup.charAt(checkPos) != '<') {
                    if (markup.substring(Math.max(0, checkPos - 6), checkPos).contains("class=")) {
                        inClassAttribute = true
                        break
                    }
                    checkPos--
                }
                
                if (!inClassAttribute) {
                    CSSUsage usage = new CSSUsage()
                    def pos = tracker.getLineColumn(baseOffset + usagePos)
                    usage.line = pos.line
                    usage.column = pos.column
                    usage.context = extractContext(markup, usagePos)
                    usage.element = extractElementName(markup, usagePos)
                    usage.attribute = "dynamic"
                    
                    selector.htmlUsages.add(usage)
                }
                
                searchStart = usagePos + quotedClassName.length()
            }
        }

        private void findIdUsage(CSSSelector selector, String markup, int baseOffset) {
            String idName = selector.name
            
            // Skip common global IDs that might be used outside this component
            if (idName in ["body", "html", "root", "app", "main"] && selector.sourceBlock.contains("Global")) {
                // Mark as used to avoid false positives
                CSSUsage usage = new CSSUsage()
                usage.line = 1
                usage.column = 1
                usage.context = "global ID auto-detected as used"
                usage.element = "global"
                usage.attribute = "id"
                selector.htmlUsages.add(usage)
                return
            }
            
            int searchStart = 0
            
            while (true) {
                int idPos = StringUtils.indexOf(markup, "id=", searchStart)
                if (idPos == -1) break
                
                // Find the quote start
                int quoteStart = idPos + 3
                while (quoteStart < markup.length() && markup.charAt(quoteStart) != '"' && markup.charAt(quoteStart) != "'") {
                    quoteStart++
                }
                if (quoteStart >= markup.length()) {
                    searchStart = idPos + 3
                    continue
                }
                
                char quote = markup.charAt(quoteStart)
                int quoteEnd = StringUtils.indexOf(markup, quote as String, quoteStart + 1)
                if (quoteEnd == -1) {
                    searchStart = quoteStart + 1
                    continue
                }
                
                String idValue = markup.substring(quoteStart + 1, quoteEnd)
                
                if (idValue == idName) {
                    CSSUsage usage = new CSSUsage()
                    def pos = tracker.getLineColumn(baseOffset + idPos)
                    usage.line = pos.line
                    usage.column = pos.column
                    usage.context = extractContext(markup, idPos)
                    usage.element = extractElementName(markup, idPos)
                    usage.attribute = "id"
                    
                    selector.htmlUsages.add(usage)
                }
                
                searchStart = quoteEnd + 1
            }
        }

        private void findElementUsage(CSSSelector selector, String markup, int baseOffset) {
            String elementName = selector.name
            if (elementName in ["html", "body", "*"]) return // Skip global elements
            
            int searchStart = 0
            
            while (true) {
                int elementPos = StringUtils.indexOf(markup, "<${elementName}", searchStart)
                if (elementPos == -1) {
                    elementPos = StringUtils.indexOf(markup, "<${elementName} ", searchStart)
                    if (elementPos == -1) break
                }
                
                CSSUsage usage = new CSSUsage()
                def pos = tracker.getLineColumn(baseOffset + elementPos)
                usage.line = pos.line
                usage.column = pos.column
                usage.context = extractContext(markup, elementPos)
                usage.element = elementName
                usage.attribute = "element"
                
                selector.htmlUsages.add(usage)
                searchStart = elementPos + elementName.length() + 1
            }
        }

        private void findDescendantUsage(CSSSelector selector, String markup, int baseOffset) {
            // Parse descendant selector like ".chat-position-bottom-left .chat-header"
            String selectorText = selector.name.trim()
            String[] parts = StringUtils.split(selectorText)
            
            if (parts.length < 2) return // Not a valid descendant selector
            
            // For now, handle simple case of ".parent .child"
            String parentSelector = parts[0].trim()
            String childSelector = parts[1].trim()
            
            // Only handle class-based descendant selectors for now
            if (!parentSelector.startsWith(".") || !childSelector.startsWith(".")) return
            
            String parentClass = parentSelector.substring(1)
            String childClass = childSelector.substring(1)
            
            // Find parent elements with parentClass
            int searchStart = 0
            while (true) {
                int parentPos = findClassInMarkup(markup, parentClass, searchStart)
                if (parentPos == -1) break
                
                // Find the parent element's closing tag
                int parentTagStart = parentPos
                while (parentTagStart > 0 && markup.charAt(parentTagStart) != '<') {
                    parentTagStart--
                }
                
                if (parentTagStart == 0) {
                    searchStart = parentPos + parentClass.length()
                    continue
                }
                
                // Get the parent element name
                String parentElement = extractElementName(markup, parentPos)
                if (parentElement == "unknown") {
                    searchStart = parentPos + parentClass.length()
                    continue
                }
                
                // Find the closing tag for this parent element
                int parentEndPos = findClosingTag(markup, parentElement, parentTagStart)
                if (parentEndPos == -1) {
                    searchStart = parentPos + parentClass.length()
                    continue
                }
                
                // Look for child class within the parent element's content
                String parentContent = markup.substring(parentTagStart, parentEndPos)
                if (parentContent.contains("class=\"") && parentContent.contains(childClass)) {
                    // Check if the child class is actually used in a class attribute
                    if (hasClassInContent(parentContent, childClass)) {
                        CSSUsage usage = new CSSUsage()
                        def pos = tracker.getLineColumn(baseOffset + parentPos)
                        usage.line = pos.line
                        usage.column = pos.column
                        usage.context = extractContext(markup, parentPos)
                        usage.element = parentElement
                        usage.attribute = "descendant"
                        
                        selector.htmlUsages.add(usage)
                        break // Found usage, no need to continue searching
                    }
                }
                
                searchStart = parentPos + parentClass.length()
            }
        }
        
        private int findClassInMarkup(String markup, String className, int startPos) {
            String pattern = "class=\""
            int searchStart = startPos
            
            while (true) {
                int classPos = StringUtils.indexOf(markup, pattern, searchStart)
                if (classPos == -1) return -1
                
                int quoteEnd = StringUtils.indexOf(markup, "\"", classPos + pattern.length())
                if (quoteEnd == -1) {
                    searchStart = classPos + pattern.length()
                    continue
                }
                
                String classValue = markup.substring(classPos + pattern.length(), quoteEnd)
                String[] classes = StringUtils.split(classValue)
                
                for (String cls : classes) {
                    if (cls.equals(className) || 
                        (cls.contains("{") && cls.contains(className))) { // Handle dynamic classes
                        return classPos
                    }
                }
                
                searchStart = quoteEnd + 1
            }
        }
        
        private boolean hasClassInContent(String content, String className) {
            return content.contains("class=\"") && content.contains(className)
        }
        
        private int findClosingTag(String markup, String elementName, int startPos) {
            String openingTag = "<${elementName}"
            String closingTag = "</${elementName}>"
            
            int tagCount = 1
            int searchPos = startPos + openingTag.length()
            
            while (searchPos < markup.length() && tagCount > 0) {
                int nextOpening = StringUtils.indexOf(markup, openingTag, searchPos)
                int nextClosing = StringUtils.indexOf(markup, closingTag, searchPos)
                
                if (nextClosing == -1) return -1 // No closing tag found
                
                if (nextOpening != -1 && nextOpening < nextClosing) {
                    tagCount++
                    searchPos = nextOpening + openingTag.length()
                } else {
                    tagCount--
                    if (tagCount == 0) {
                        return nextClosing + closingTag.length()
                    }
                    searchPos = nextClosing + closingTag.length()
                }
            }
            
            return -1 // No matching closing tag found
        }

        private String extractElementName(String markup, int position) {
            // Find the opening < before this position
            int tagStart = position
            while (tagStart > 0 && markup.charAt(tagStart) != '<') {
                tagStart--
            }
            
            if (tagStart == 0) return "unknown"
            
            // Extract element name
            int nameEnd = tagStart + 1
            while (nameEnd < markup.length() && 
                   Character.isLetterOrDigit(markup.charAt(nameEnd)) || 
                   markup.charAt(nameEnd) == '-') {
                nameEnd++
            }
            
            return markup.substring(tagStart + 1, nameEnd)
        }
    }

    // Output formatter
    class OutputFormatter {
        
        String formatCssIssuesOnly(AnalysisResult result, String format) {
            switch (format.toLowerCase()) {
                case "json":
                    return formatCssIssuesAsJSON(result)
                case "csv":
                    return formatCssIssuesAsCSV(result)
                default:
                    return formatCssIssuesAsText(result)
            }
        }
        
        String formatAnalysisResult(AnalysisResult result, String format) {
            switch (format.toLowerCase()) {
                case "json":
                    return formatAsJSON(result)
                case "csv":
                    return formatAsCSV(result)
                default:
                    return formatAsText(result)
            }
        }

        private String formatAsText(AnalysisResult result) {
            StringBuilder output = new StringBuilder()
            
            output.append("Title: ${result.filePath}\n\n")
            
            // CSS Analysis
            output.append("CSS Analysis:\n")
            output.append("- <style> declaration count: ${result.styleBlocks.size()}\n")
            
            if (result.styleBlocks) {
                output.append("- Style blocks:\n")
                result.styleBlocks.eachWithIndex { block, index ->
                    String scopeType = block.isScoped ? "Scoped" : "Global"
                    output.append("  ${index + 1}. [Range ${block.startLine}:${block.startCol} - ${block.endLine}:${block.endCol}] - ${scopeType} styles")
                    if (block.language != "css") {
                        output.append(" (lang: ${block.language})")
                    }
                    output.append("\n")
                }
            }
            
            output.append("- Count of imported CSS: ${result.cssImports.size()}\n")
            
            if (result.cssImports) {
                output.append("- List of imported CSS:\n")
                result.cssImports.eachWithIndex { cssImport, index ->
                    output.append("  ${index + 1}. ${cssImport.path} [imported at ${cssImport.line}:${cssImport.column}]\n")
                }
            }

            // CSS Override Analysis
            if (result.cssOverrides) {
                output.append("\nCSS Override Analysis:\n")
                output.append("- Property conflicts detected: ${result.cssOverrides.size()}\n")
                result.cssOverrides.eachWithIndex { override, index ->
                    output.append("  ${index + 1}. '${override.property}' property overridden at [${override.line}:${override.column}] (specificity: ${override.specificity})\n")
                    output.append("     ${override.conflictsWith}\n")
                }
            }
            // CSS Selector Usage Analysis
            if (result.cssSelectors) {
                output.append("\nCSS Selector Usage Analysis:\n")
                
                def selectorsByType = result.cssSelectors.groupBy { it.type }
                
                if (selectorsByType.containsKey("class")) {
                    output.append("\nClasses:\n")
                    selectorsByType.class.each { selector ->
                        output.append("- '.${selector.name}' declared at [${selector.declarationLine}:${selector.declarationColumn}] in ${selector.sourceBlock}\n")
                        if (selector.properties) {
                            output.append("  Properties: ${selector.properties.join(", ")}\n")
                        }
                        if (selector.htmlUsages) {
                            output.append("  Used in HTML at: ${selector.htmlUsages.collect { "${it.element} [${it.line}:${it.column}]" }.join(", ")}\n")
                        } else {
                            output.append("  ⚠️ Unused CSS class\n")
                        }
                    }
                }
                
                if (selectorsByType.containsKey("id")) {
                    output.append("\nIDs:\n")
                    selectorsByType.id.each { selector ->
                        output.append("- '#${selector.name}' declared at [${selector.declarationLine}:${selector.declarationColumn}] in ${selector.sourceBlock}\n")
                        if (selector.properties) {
                            output.append("  Properties: ${selector.properties.join(", ")}\n")
                        }
                        if (selector.htmlUsages) {
                            output.append("  Used in HTML at: ${selector.htmlUsages.collect { "${it.element} [${it.line}:${it.column}]" }.join(", ")}\n")
                        } else {
                            output.append("  ⚠️ Unused CSS ID\n")
                        }
                    }
                }
                
                if (selectorsByType.containsKey("element")) {
                    output.append("\nElement Selectors:\n")
                    selectorsByType.element.each { selector ->
                        output.append("- '${selector.name}' declared at [${selector.declarationLine}:${selector.declarationColumn}] in ${selector.sourceBlock}\n")
                        if (selector.properties) {
                            output.append("  Properties: ${selector.properties.join(", ")}\n")
                        }
                        if (selector.htmlUsages) {
                            output.append("  Used in HTML at: ${selector.htmlUsages.collect { "[${it.line}:${it.column}]" }.join(", ")}\n")
                        }
                    }
                }
            }

            // Reference Analysis
            output.append("\nReference Analysis:\n\n")
            
            def variablesByType = result.variables.groupBy { it.type }
            
            if (variablesByType.containsKey("let") || variablesByType.containsKey("const") || variablesByType.containsKey("var")) {
                output.append("Variables:\n")
                ["let", "const", "var"].each { type ->
                    variablesByType[type]?.each { variable ->
                        output.append("- '${variable.name}' (${variable.type}) declared at [${variable.declarationLine}:${variable.declarationCol}]\n")
                        if (variable.usages) {
                            output.append("  Used at: ${variable.usages.collect { "[${it.line}:${it.column}]" }.join(", ")}\n")
                        }
                    }
                }
            }

            if (variablesByType.containsKey("reactive")) {
                output.append("\nReactive Variables:\n")
                variablesByType.reactive.each { variable ->
                    output.append("- '${variable.name}' (reactive) declared at [${variable.declarationLine}:${variable.declarationCol}]\n")
                    if (variable.dependencies) {
                        output.append("  Dependencies: ${variable.dependencies.join(", ")}\n")
                    }
                    if (variable.usages) {
                        output.append("  Used at: ${variable.usages.collect { "[${it.line}:${it.column}]" }.join(", ")}\n")
                    }
                }
            }

            if (variablesByType.containsKey("prop")) {
                output.append("\nComponent Props:\n")
                variablesByType.prop.each { variable ->
                    output.append("- '${variable.name}' (export let) declared at [${variable.declarationLine}:${variable.declarationCol}]\n")
                    if (variable.usages) {
                        output.append("  Used at: ${variable.usages.collect { "[${it.line}:${it.column}]" }.join(", ")}\n")
                    }
                }
            }

            // Import Analysis
            output.append("\nImport Analysis:\n\n")
            
            def importsByType = result.imports.groupBy { it.type }
            
            if (importsByType.containsKey("es6")) {
                output.append("ES6 Imports:\n")
                importsByType.es6.each { importStmt ->
                    output.append("- '${importStmt.symbols.join(", ")}' from '${importStmt.module}' [imported at ${importStmt.line}:${importStmt.column}]\n")
                    if (importStmt.usages) {
                        output.append("  Used at: ${importStmt.usages.collect { "[${it.line}:${it.column}]" }.join(", ")}\n")
                    }
                }
            }

            if (importsByType.containsKey("dynamic")) {
                output.append("\nDynamic Imports:\n")
                importsByType.dynamic.each { importStmt ->
                    output.append("- '${importStmt.module}' [imported at ${importStmt.line}:${importStmt.column}]\n")
                    output.append("  Conditional import in async function\n")
                }
            }

            // Statistics
            output.append("\nStatistics:\n")
            result.statistics.each { key, value ->
                output.append("- ${key}: ${value}\n")
            }

            return output.toString()
        }

        private String formatAsJSON(AnalysisResult result) {
            // Simple JSON formatting using JsonBuilder
            return new JsonBuilder(result).toPrettyString()
        }

        private String formatAsCSV(AnalysisResult result) {
            StringBuilder csv = new StringBuilder()
            csv.append("Type,Name,Line,Column,Details\n")
            
            result.variables.each { variable ->
                csv.append("Variable,${variable.name},${variable.declarationLine},${variable.declarationCol},${variable.type}\n")
            }
            
            result.imports.each { importStmt ->
                csv.append("Import,${importStmt.module},${importStmt.line},${importStmt.column},${importStmt.type}\n")
            }
            
            return csv.toString()
        }
        
        private String formatCssIssuesAsText(AnalysisResult result) {
            StringBuilder output = new StringBuilder()
            
            // Only show files with CSS issues
            boolean hasIssues = false
            
            // Check for unused CSS selectors (skip global styles)
            def unusedSelectors = result.cssSelectors.findAll { selector ->
                (!selector.htmlUsages || selector.htmlUsages.isEmpty()) && 
                !selector.sourceBlock.contains("Global")
            }
            
            if (unusedSelectors) {
                hasIssues = true
                output.append("🔍 CSS Issues in: ${result.filePath}\n\n")
                
                output.append("⚠️ Unused CSS Classes/IDs:\n")
                unusedSelectors.each { selector ->
                    String prefix = selector.type == "class" ? "." : "#"
                    output.append("- '${prefix}${selector.name}' declared at [${selector.declarationLine}:${selector.declarationColumn}]\n")
                    output.append("  ❌ Never used in HTML\n")
                }
                output.append("\n")
            }
            
            // Check for CSS overrides/conflicts
            if (result.cssOverrides) {
                if (!hasIssues) {
                    output.append("🔍 CSS Issues in: ${result.filePath}\n\n")
                    hasIssues = true
                }
                
                output.append("⚠️ CSS Property Conflicts:\n")
                result.cssOverrides.each { override ->
                    output.append("- '${override.property}' overridden at [${override.line}:${override.column}]\n")
                    output.append("  ${override.conflictsWith}\n")
                }
                output.append("\n")
            }
            
            // Check for missing CSS imports
            if (result.styleBlocks.isEmpty() && result.cssImports.isEmpty()) {
                if (!hasIssues) {
                    output.append("🔍 CSS Issues in: ${result.filePath}\n\n")
                    hasIssues = true
                }
                
                output.append("⚠️ No CSS Found:\n")
                output.append("- No <style> blocks or CSS imports detected\n")
                output.append("  Consider adding styles or importing CSS\n\n")
            }
            
            return hasIssues ? output.toString() : ""
        }
        
        private String formatCssIssuesAsJSON(AnalysisResult result) {
            def issues = [:]
            issues.filePath = result.filePath
            issues.unusedSelectors = []
            issues.cssOverrides = []
            issues.noCSS = false
            
            // Unused selectors (skip global styles as they might be used elsewhere)
            result.cssSelectors.findAll { 
                (!it.htmlUsages || it.htmlUsages.isEmpty()) && 
                !it.sourceBlock.contains("Global")
            }.each { selector ->
                issues.unusedSelectors.add([
                    type: selector.type,
                    name: selector.name,
                    line: selector.declarationLine,
                    column: selector.declarationColumn
                ])
            }
            
            // CSS overrides
            if (result.cssOverrides) {
                issues.cssOverrides = result.cssOverrides
            }
            
            // No CSS
            if (result.styleBlocks.isEmpty() && result.cssImports.isEmpty()) {
                issues.noCSS = true
            }
            
            return issues.unusedSelectors || issues.cssOverrides || issues.noCSS ? 
                new JsonBuilder(issues).toPrettyString() : ""
        }
        
        private String formatCssIssuesAsCSV(AnalysisResult result) {
            StringBuilder csv = new StringBuilder()
            boolean hasHeader = false
            
            // Unused selectors (skip global styles as they might be used elsewhere)
            result.cssSelectors.findAll { 
                (!it.htmlUsages || it.htmlUsages.isEmpty()) && 
                !it.sourceBlock.contains("Global")
            }.each { selector ->
                if (!hasHeader) {
                    csv.append("File,IssueType,SelectorType,Name,Line,Column,Details\n")
                    hasHeader = true
                }
                csv.append("${result.filePath},UnusedSelector,${selector.type},${selector.name},${selector.declarationLine},${selector.declarationColumn},Never used in HTML\n")
            }
            
            // CSS overrides
            result.cssOverrides?.each { override ->
                if (!hasHeader) {
                    csv.append("File,IssueType,SelectorType,Name,Line,Column,Details\n")
                    hasHeader = true
                }
                csv.append("${result.filePath},PropertyConflict,property,${override.property},${override.line},${override.column},${override.conflictsWith}\n")
            }
            
            return csv.toString()
        }
        
        private String formatDeadCodeOnly(AnalysisResult result, String format) {
            switch (format.toLowerCase()) {
                case "json": return formatDeadCodeAsJSON(result)
                case "csv": return formatDeadCodeAsCSV(result)
                default: return formatDeadCodeAsText(result)
            }
        }
        
        private String formatUnusedComponentsOnly(AnalysisResult result, String format) {
            switch (format.toLowerCase()) {
                case "json": return formatUnusedComponentsAsJSON(result)
                case "csv": return formatUnusedComponentsAsCSV(result)
                default: return formatUnusedComponentsAsText(result)
            }
        }
        
        private String formatAllIssues(AnalysisResult result, String format) {
            switch (format.toLowerCase()) {
                case "json": return formatAllIssuesAsJSON(result)
                case "csv": return formatAllIssuesAsCSV(result)
                default: return formatAllIssuesAsText(result)
            }
        }
        
        private String formatDeadCodeAsText(AnalysisResult result) {
            StringBuilder output = new StringBuilder()
            boolean hasIssues = false
            
            // DOM and built-in method names that should not be flagged as unused
            Set<String> domAndBuiltinMethods = [
                // DOM methods
                'contains', 'closest', 'querySelector', 'querySelectorAll', 'getElementById',
                'getElementsByClassName', 'getElementsByTagName', 'addEventListener', 
                'removeEventListener', 'appendChild', 'removeChild', 'insertBefore',
                'cloneNode', 'setAttribute', 'getAttribute', 'removeAttribute',
                'focus', 'blur', 'click', 'submit', 'reset',
                
                // String methods
                'trim', 'startsWith', 'endsWith', 'includes', 'indexOf', 'lastIndexOf',
                'substring', 'substr', 'slice', 'split', 'replace', 'replaceAll',
                'toLowerCase', 'toUpperCase', 'charAt', 'charCodeAt',
                
                // Array methods
                'push', 'pop', 'shift', 'unshift', 'splice', 'slice', 'concat',
                'join', 'reverse', 'sort', 'filter', 'map', 'reduce', 'forEach',
                'find', 'findIndex', 'some', 'every', 'includes',
                
                // Object methods
                'keys', 'values', 'entries', 'hasOwnProperty', 'toString', 'valueOf',
                
                // Global functions
                'isArray', 'parseInt', 'parseFloat', 'isNaN', 'isFinite',
                'encodeURIComponent', 'decodeURIComponent', 'setTimeout', 'clearTimeout',
                'setInterval', 'clearInterval'
            ].toSet()
            
            def unusedFunctions = result.jsFunctions.findAll { func -> 
                func.usages.isEmpty() && !func.isExported && !domAndBuiltinMethods.contains(func.name)
            }
            if (unusedFunctions) {
                hasIssues = true
                output.append("💀 Dead Code in: ${result.filePath}\n\n⚠️ Unused Functions:\n")
                unusedFunctions.each { func ->
                    output.append("- '${func.name}()' declared at [${func.declarationLine}:${func.declarationColumn}]\n  ❌ Never called\n")
                }
            }
            
            def unusedVars = result.variables.findAll { variable -> 
                variable.usages.isEmpty() && variable.type != 'reactive' && !variable.name.startsWith('$')
            }
            if (unusedVars) {
                if (!hasIssues) { output.append("💀 Dead Code in: ${result.filePath}\n\n"); hasIssues = true }
                output.append("⚠️ Unused Variables:\n")
                unusedVars.each { variable ->
                    output.append("- '${variable.name}' declared at [${variable.declarationLine}:${variable.declarationCol}]\n  ❌ Never used\n")
                }
            }
            
            return hasIssues ? output.toString() : ""
        }
        
        private String formatUnusedComponentsAsText(AnalysisResult result) {
            def unusedComponents = result.componentImports.findAll { !it.isUsed }
            if (!unusedComponents) return ""
            
            StringBuilder output = new StringBuilder()
            output.append("🧩 Unused Components in: ${result.filePath}\n\n⚠️ Imported but Never Used:\n")
            unusedComponents.each { compImport ->
                output.append("- '${compImport.componentName}' from '${compImport.filePath}'\n  ❌ Remove import\n")
            }
            return output.toString()
        }
        
        private String formatAllIssuesAsText(AnalysisResult result) {
            String css = formatCssIssuesAsText(result)
            String dead = formatDeadCodeAsText(result)
            String comp = formatUnusedComponentsAsText(result)
            
            if (!css && !dead && !comp) return ""
            
            StringBuilder output = new StringBuilder()
            output.append("🔧 REFACTORING REPORT: ${result.filePath}\n${'=' * 80}\n\n")
            if (css) output.append(css).append("\n")
            if (dead) output.append(dead).append("\n")
            if (comp) output.append(comp).append("\n")
            
            int totalIssues = (result.cssSelectors?.count { it.htmlUsages.isEmpty() && !it.sourceBlock?.contains("Global") } ?: 0) +
                             (result.cssOverrides?.size() ?: 0) +
                             (result.jsFunctions?.count { it.usages.isEmpty() && !it.isExported } ?: 0) +
                             (result.variables?.count { it.usages.isEmpty() && it.type != 'reactive' && !it.name.startsWith('$') } ?: 0) +
                             (result.componentImports?.count { !it.isUsed } ?: 0) +
                             (result.imports?.count { it.usages.isEmpty() } ?: 0)
            output.append("📊 SUMMARY: ${totalIssues} refactoring opportunities found\n")
            
            return output.toString()
        }
        
        private String formatDeadCodeAsJSON(AnalysisResult result) { return "{}" }
        private String formatUnusedComponentsAsJSON(AnalysisResult result) { return "{}" }
        private String formatAllIssuesAsJSON(AnalysisResult result) { return "{}" }
        private String formatDeadCodeAsCSV(AnalysisResult result) { return "" }
        private String formatUnusedComponentsAsCSV(AnalysisResult result) { return "" }
        private String formatAllIssuesAsCSV(AnalysisResult result) { return "" }
        
        String formatGitCommitAnalysis(GitCommitAnalysis commitAnalysis, String format) {
            switch (format.toLowerCase()) {
                case "json":
                    return new JsonBuilder(commitAnalysis).toPrettyString()
                case "csv":
                    return formatGitCommitAsCSV(commitAnalysis)
                default:
                    return formatGitCommitAsText(commitAnalysis)
            }
        }

        String formatCommitComparison(ComparisonResult comparison, String format) {
            switch (format.toLowerCase()) {
                case "json":
                    return new JsonBuilder(comparison).toPrettyString()
                case "csv":
                    return formatComparisonAsCSV(comparison)
                default:
                    return formatComparisonAsText(comparison)
            }
        }

        private String formatGitCommitAsText(GitCommitAnalysis commitAnalysis) {
            StringBuilder output = new StringBuilder()
            
            output.append("Git Commit Analysis\n")
            output.append("=".repeat(50) + "\n\n")
            
            output.append("Commit Information:\n")
            output.append("- Hash: ${commitAnalysis.commitHash}\n")
            output.append("- Message: ${commitAnalysis.commitMessage}\n")
            output.append("- Date: ${commitAnalysis.commitDate}\n")
            output.append("- Author: ${commitAnalysis.author}\n")
            output.append("- Modified Files: ${commitAnalysis.modifiedFiles.size()}\n")
            
            if (commitAnalysis.modifiedFiles) {
                output.append("\nModified Svelte Files:\n")
                commitAnalysis.modifiedFiles.eachWithIndex { file, index ->
                    output.append("  ${index + 1}. ${file}\n")
                }
            }
            
            output.append("\n")
            output.append("Project Analysis at this Commit:\n")
            output.append("-".repeat(40) + "\n")
            
            // Use existing formatAnalysisResult but modify the title
            String analysisText = formatAnalysisResult(commitAnalysis.analysisResult, "text")
            analysisText = analysisText.replaceFirst("Title: .*?\n", "Project Path: ${commitAnalysis.analysisResult.filePath}\n")
            output.append(analysisText)
            
            return output.toString()
        }

        private String formatComparisonAsText(ComparisonResult comparison) {
            StringBuilder output = new StringBuilder()
            
            output.append("Git Commit Comparison Analysis\n")
            output.append("=".repeat(50) + "\n\n")
            
            // Commit 1 info
            output.append("Commit 1 (Before):\n")
            output.append("- Hash: ${comparison.commit1.commitHash}\n")
            output.append("- Message: ${comparison.commit1.commitMessage}\n")
            output.append("- Date: ${comparison.commit1.commitDate}\n")
            output.append("- Author: ${comparison.commit1.author}\n\n")
            
            // Commit 2 info
            output.append("Commit 2 (After):\n")
            output.append("- Hash: ${comparison.commit2.commitHash}\n")
            output.append("- Message: ${comparison.commit2.commitMessage}\n")
            output.append("- Date: ${comparison.commit2.commitDate}\n")
            output.append("- Author: ${comparison.commit2.author}\n\n")
            
            // Changes summary
            output.append("Changes Summary:\n")
            output.append("-".repeat(30) + "\n")
            comparison.differences.each { key, value ->
                String sign = value > 0 ? "+" : ""
                String label = StringUtils.replace(key, "Change", "")
                // Convert camelCase to space-separated words using StringUtils 
                StringBuilder result = new StringBuilder()
                for (int i = 0; i < label.length(); i++) {
                    char c = label.charAt(i)
                    if (Character.isUpperCase(c) && i > 0) {
                        result.append(" ")
                    }
                    result.append(Character.toLowerCase(c))
                }
                label = result.toString().trim()
                label = label.substring(0, 1).toUpperCase() + label.substring(1)
                output.append("- ${label}: ${sign}${value}\n")
            }
            
            if (comparison.modifiedFiles) {
                output.append("\nModified Files:\n")
                comparison.modifiedFiles.eachWithIndex { file, index ->
                    output.append("  ${index + 1}. ${file}\n")
                }
            }
            
            // Detailed analysis for both commits
            output.append("\n")
            output.append("=".repeat(50) + "\n")
            output.append("BEFORE (${comparison.commit1.commitHash}):\n")
            output.append("=".repeat(50) + "\n")
            String analysis1 = formatAnalysisResult(comparison.commit1.analysisResult, "text")
            analysis1 = analysis1.replaceFirst("Title: .*?\n", "")
            output.append(analysis1)
            
            output.append("\n")
            output.append("=".repeat(50) + "\n")
            output.append("AFTER (${comparison.commit2.commitHash}):\n")
            output.append("=".repeat(50) + "\n")
            String analysis2 = formatAnalysisResult(comparison.commit2.analysisResult, "text")
            analysis2 = analysis2.replaceFirst("Title: .*?\n", "")
            output.append(analysis2)
            
            return output.toString()
        }

        private String formatGitCommitAsCSV(GitCommitAnalysis commitAnalysis) {
            StringBuilder csv = new StringBuilder()
            csv.append("Type,Name,Line,Column,Details,Commit\n")
            
            String commitHash = commitAnalysis.commitHash
            AnalysisResult result = commitAnalysis.analysisResult
            
            result.variables.each { variable ->
                csv.append("Variable,${variable.name},${variable.declarationLine},${variable.declarationCol},${variable.type},${commitHash}\n")
            }
            
            result.imports.each { importStmt ->
                csv.append("Import,${importStmt.module},${importStmt.line},${importStmt.column},${importStmt.type},${commitHash}\n")
            }
            
            result.cssSelectors.each { selector ->
                csv.append("CSSSelector,${selector.name},${selector.declarationLine},${selector.declarationColumn},${selector.type},${commitHash}\n")
            }
            
            return csv.toString()
        }

        private String formatComparisonAsCSV(ComparisonResult comparison) {
            StringBuilder csv = new StringBuilder()
            csv.append("Metric,Commit1_Value,Commit2_Value,Change,Commit1_Hash,Commit2_Hash\n")
            
            def stats1 = comparison.commit1.analysisResult.statistics
            def stats2 = comparison.commit2.analysisResult.statistics
            
            [
                "totalFiles", "styleBlockCount", "cssImportCount", "variableCount", 
                "importCount", "cssOverrideCount", "cssSelectorCount", 
                "usedCSSSelectors", "unusedCSSSelectors"
            ].each { metric ->
                def value1 = stats1[metric] ?: 0
                def value2 = stats2[metric] ?: 0
                def change = value2 - value1
                csv.append("${metric},${value1},${value2},${change},${comparison.commit1.commitHash},${comparison.commit2.commitHash}\n")
            }
            
            return csv.toString()
        }
    }

    private int countRefactoringOpportunities(AnalysisResult result) {
        int opportunities = 0
        
        // DOM and built-in method names that should not be flagged as unused
        Set<String> domAndBuiltinMethods = [
            // DOM methods
            'contains', 'closest', 'querySelector', 'querySelectorAll', 'getElementById',
            'getElementsByClassName', 'getElementsByTagName', 'addEventListener', 
            'removeEventListener', 'appendChild', 'removeChild', 'insertBefore',
            'cloneNode', 'setAttribute', 'getAttribute', 'removeAttribute',
            'focus', 'blur', 'click', 'submit', 'reset',
            
            // String methods
            'trim', 'startsWith', 'endsWith', 'includes', 'indexOf', 'lastIndexOf',
            'substring', 'substr', 'slice', 'split', 'replace', 'replaceAll',
            'toLowerCase', 'toUpperCase', 'charAt', 'charCodeAt',
            
            // Array methods
            'push', 'pop', 'shift', 'unshift', 'splice', 'slice', 'concat',
            'join', 'reverse', 'sort', 'filter', 'map', 'reduce', 'forEach',
            'find', 'findIndex', 'some', 'every', 'includes',
            
            // Object methods
            'keys', 'values', 'entries', 'hasOwnProperty', 'toString', 'valueOf',
            
            // Global functions
            'isArray', 'parseInt', 'parseFloat', 'isNaN', 'isFinite',
            'encodeURIComponent', 'decodeURIComponent', 'setTimeout', 'clearTimeout',
            'setInterval', 'clearInterval'
        ].toSet()
        
        // Count unused CSS selectors (excluding global styles)
        opportunities += result.cssSelectors?.count { selector ->
            (!selector.htmlUsages || selector.htmlUsages.isEmpty()) && 
            !selector.sourceBlock?.contains("Global")
        } ?: 0
        
        // Count CSS property conflicts
        opportunities += result.cssOverrides?.size() ?: 0
        
        // Count unused JavaScript functions (excluding exported ones and DOM/built-in methods)
        opportunities += result.jsFunctions?.count { func -> 
            func.usages.isEmpty() && !func.isExported && !domAndBuiltinMethods.contains(func.name)
        } ?: 0
        
        // Count unused variables (excluding reactive and store variables)
        opportunities += result.variables?.count { variable -> 
            variable.usages.isEmpty() && variable.type != 'reactive' && !variable.name.startsWith('$')
        } ?: 0
        
        // Count unused component imports
        opportunities += result.componentImports?.count { !it.isUsed } ?: 0
        
        // Count unused regular imports
        opportunities += result.imports?.count { importStmt ->
            importStmt.usages.isEmpty()
        } ?: 0
        
        return opportunities
    }

    @Override
    Integer call() throws Exception {
        try {
            if (verbose) {
                println "Analyzing: $inputPath"
                println "Format: $format"
                println "Extensions: $extensions"
                if (gitCommit) println "Git commit: $gitCommit"
                if (compareCommits) println "Comparing commits: $compareCommits"
            }

            // Handle git operations
            if (gitCommit || compareCommits) {
                return handleGitAnalysis()
            }

            // Regular file/directory analysis
            File input = new File(inputPath)
            if (!input.exists()) {
                System.err.println "Error: Input path does not exist: $inputPath"
                return 1
            }

            List<File> filesToAnalyze = []
            if (verbose) {
                println "Input file exists: ${input.exists()}"
                println "Input is file: ${input.isFile()}"
                println "Input is directory: ${input.isDirectory()}"
                println "Input absolute path: ${input.absolutePath}"
            }
            if (input.isFile()) {
                filesToAnalyze.add(input)
            } else if (input.isDirectory()) {
                filesToAnalyze = findFilesToAnalyze(input)
            }

            if (filesToAnalyze.isEmpty()) {
                System.err.println "No files found to analyze"
                return 1
            }

            OutputFormatter formatter = new OutputFormatter()
            List<String> allResults = []

            filesToAnalyze.each { file ->
                if (verbose) {
                    println "Processing: ${file.absolutePath}"
                }

                try {
                    String content = FileUtils.readFileToString(file, "UTF-8")
                    SvelteFileParser parser = new SvelteFileParser(content)
                    AnalysisResult result = parser.parseFile(file.absolutePath)
                    
                    String formattedResult
                    if (filterCssIssues) {
                        formattedResult = formatter.formatCssIssuesOnly(result, format)
                    } else if (filterDeadCode) {
                        formattedResult = formatter.formatDeadCodeOnly(result, format)
                    } else if (filterUnusedComponents) {
                        formattedResult = formatter.formatUnusedComponentsOnly(result, format)
                    } else if (filterAllIssues) {
                        formattedResult = formatter.formatAllIssues(result, format)
                    } else {
                        // Check if there are any refactoring opportunities before showing full analysis
                        int refactoringOpportunities = countRefactoringOpportunities(result)
                        if (refactoringOpportunities > 0) {
                            formattedResult = formatter.formatAnalysisResult(result, format)
                        } else {
                            formattedResult = "" // No output if no refactoring opportunities
                        }
                    }
                    
                    if (formattedResult && !formattedResult.trim().isEmpty()) {
                        allResults.add(formattedResult)
                    }
                    
                } catch (Exception e) {
                    System.err.println "Error processing ${file.absolutePath}: ${e.message}"
                    if (verbose) {
                        e.printStackTrace()
                    }
                }
            }

            String finalOutput = allResults.join("\n" + "="*80 + "\n")

            if (outputFile) {
                FileUtils.writeStringToFile(new File(outputFile), finalOutput, "UTF-8")
                println "Analysis results written to: $outputFile"
            } else {
                println finalOutput
            }

            return 0

        } catch (Exception e) {
            System.err.println "Error: ${e.message}"
            if (verbose) {
                e.printStackTrace()
            }
            return 1
        }
    }

    private Integer handleGitAnalysis() {
        GitHelper gitHelper = new GitHelper(gitWorkingDir)
        
        if (!gitHelper.isGitRepository()) {
            System.err.println "Error: Not a git repository: $gitWorkingDir"
            return 1
        }

        try {
            if (compareCommits) {
                return handleCommitComparison(gitHelper)
            } else if (gitCommit) {
                return handleSingleCommitAnalysis(gitHelper)
            }
        } catch (Exception e) {
            System.err.println "Git analysis error: ${e.message}"
            if (verbose) {
                e.printStackTrace()
            }
            return 1
        }
        
        return 0
    }

    private Integer handleSingleCommitAnalysis(GitHelper gitHelper) {
        String originalBranch = gitHelper.getCurrentBranch()
        String originalCommit = gitHelper.getCurrentCommitHash()
        
        try {
            GitCommitAnalysis commitAnalysis = gitHelper.getCommitInfo(gitCommit)
            
            if (verbose) {
                println "Checking out commit: $gitCommit"
            }
            
            if (!gitHelper.checkoutCommit(gitCommit)) {
                System.err.println "Failed to checkout commit: $gitCommit"
                return 1
            }
            
            // Analyze files at this commit
            commitAnalysis.analysisResult = analyzeCurrentState()
            
            // Format and output results
            OutputFormatter formatter = new OutputFormatter()
            String result = formatter.formatGitCommitAnalysis(commitAnalysis, format)
            
            if (outputFile) {
                FileUtils.writeStringToFile(new File(outputFile), result, "UTF-8")
                println "Git commit analysis written to: $outputFile"
            } else {
                println result
            }
            
        } finally {
            // Restore original state
            if (verbose) {
                println "Restoring original state..."
            }
            gitHelper.checkoutBranch(originalBranch)
        }
        
        return 0
    }

    private Integer handleCommitComparison(GitHelper gitHelper) {
        String[] commits = compareCommits.split(",")
        if (commits.length != 2) {
            System.err.println "Error: Compare commits format should be: hash1,hash2"
            return 1
        }
        
        String commit1 = commits[0].trim()
        String commit2 = commits[1].trim()
        String originalBranch = gitHelper.getCurrentBranch()
        
        try {
            ComparisonResult comparison = new ComparisonResult()
            
            // Analyze first commit
            if (verbose) println "Analyzing commit 1: $commit1"
            comparison.commit1 = gitHelper.getCommitInfo(commit1)
            gitHelper.checkoutCommit(commit1)
            comparison.commit1.analysisResult = analyzeCurrentState()
            
            // Analyze second commit
            if (verbose) println "Analyzing commit 2: $commit2"
            comparison.commit2 = gitHelper.getCommitInfo(commit2)
            gitHelper.checkoutCommit(commit2)
            comparison.commit2.analysisResult = analyzeCurrentState()
            
            // Calculate differences
            comparison.differences = calculateDifferences(comparison.commit1.analysisResult, comparison.commit2.analysisResult)
            comparison.modifiedFiles = gitHelper.getCommitDiff(commit1, commit2)
            
            // Format and output results
            OutputFormatter formatter = new OutputFormatter()
            String result = formatter.formatCommitComparison(comparison, format)
            
            if (outputFile) {
                FileUtils.writeStringToFile(new File(outputFile), result, "UTF-8")
                println "Commit comparison written to: $outputFile"
            } else {
                println result
            }
            
        } finally {
            // Restore original state
            if (verbose) {
                println "Restoring original state..."
            }
            gitHelper.checkoutBranch(originalBranch)
        }
        
        return 0
    }

    private AnalysisResult analyzeCurrentState() {
        File input = new File(inputPath)
        if (!input.exists()) {
            throw new IllegalArgumentException("Input path does not exist: $inputPath")
        }

        List<File> filesToAnalyze = []
        if (input.isFile()) {
            filesToAnalyze.add(input)
        } else if (input.isDirectory()) {
            filesToAnalyze = findFilesToAnalyze(input)
        }

        // Combine all results into a single analysis
        AnalysisResult combinedResult = new AnalysisResult()
        combinedResult.filePath = input.absolutePath
        
        filesToAnalyze.each { file ->
            try {
                String content = FileUtils.readFileToString(file, "UTF-8")
                SvelteFileParser parser = new SvelteFileParser(content)
                AnalysisResult result = parser.parseFile(file.absolutePath)
                
                // Merge results
                combinedResult.styleBlocks.addAll(result.styleBlocks)
                combinedResult.cssImports.addAll(result.cssImports)
                combinedResult.variables.addAll(result.variables)
                combinedResult.imports.addAll(result.imports)
                combinedResult.cssOverrides.addAll(result.cssOverrides)
                combinedResult.cssSelectors.addAll(result.cssSelectors)
                
            } catch (Exception e) {
                if (verbose) {
                    System.err.println "Warning: Could not analyze ${file.absolutePath}: ${e.message}"
                }
            }
        }
        
        // Calculate combined statistics
        combinedResult.statistics = [
            totalFiles: filesToAnalyze.size(),
            styleBlockCount: combinedResult.styleBlocks.size(),
            cssImportCount: combinedResult.cssImports.size(),
            variableCount: combinedResult.variables.size(),
            importCount: combinedResult.imports.size(),
            cssOverrideCount: combinedResult.cssOverrides.size(),
            cssSelectorCount: combinedResult.cssSelectors.size(),
            usedCSSSelectors: combinedResult.cssSelectors.count { !it.htmlUsages.isEmpty() },
            unusedCSSSelectors: combinedResult.cssSelectors.count { it.htmlUsages.isEmpty() }
        ]
        
        return combinedResult
    }

    private Map<String, Object> calculateDifferences(AnalysisResult result1, AnalysisResult result2) {
        return [
            filesChange: (result2.statistics.totalFiles ?: 0) - (result1.statistics.totalFiles ?: 0),
            styleBlocksChange: (result2.statistics.styleBlockCount ?: 0) - (result1.statistics.styleBlockCount ?: 0),
            cssImportsChange: (result2.statistics.cssImportCount ?: 0) - (result1.statistics.cssImportCount ?: 0),
            variablesChange: (result2.statistics.variableCount ?: 0) - (result1.statistics.variableCount ?: 0),
            importsChange: (result2.statistics.importCount ?: 0) - (result1.statistics.importCount ?: 0),
            cssOverridesChange: (result2.statistics.cssOverrideCount ?: 0) - (result1.statistics.cssOverrideCount ?: 0),
            cssSelectorsChange: (result2.statistics.cssSelectorCount ?: 0) - (result1.statistics.cssSelectorCount ?: 0),
            usedSelectorsChange: (result2.statistics.usedCSSSelectors ?: 0) - (result1.statistics.usedCSSSelectors ?: 0),
            unusedSelectorsChange: (result2.statistics.unusedCSSSelectors ?: 0) - (result1.statistics.unusedCSSSelectors ?: 0)
        ]
    }

    private List<File> findFilesToAnalyze(File directory) {
        List<File> files = []
        String[] extensionArray = extensions.split(",").collect { it.trim() }

        // Directories to exclude from analysis
        Set<String> excludedDirs = ["node_modules", ".svelte-kit", ".git", "dist", "build", ".next", ".nuxt"].toSet()

            if (verbose) {
                println "Looking for files with extensions: ${extensionArray.join(', ')}"
                println "Excluding directories: ${excludedDirs.join(', ')}"
                println "Walking directory: ${directory.absolutePath}"
            }

        try {
            Files.walk(directory.toPath())
                .forEach { path ->
                    if (Files.isRegularFile(path)) {
                        // Exclude files in excluded directories
                        String pathStr = path.toString()
                        boolean excluded = excludedDirs.any { excludedDir -> 
                            pathStr.contains("/${excludedDir}/") || pathStr.contains("\\${excludedDir}\\") 
                        }
                        
                        if (!excluded) {
                            String fileName = path.fileName.toString()
                            boolean matches = extensionArray.any { ext -> 
                                fileName.endsWith(ext.startsWith(".") ? ext : "." + ext)
                            }
                            
                            if (matches) {
                                files.add(path.toFile())
                            }
                        }
                    }
                }
        } catch (Exception e) {
            if (verbose) {
                println "Error during file walk: ${e.message}"
                e.printStackTrace()
            }
        }

        if (verbose) {
            println "Found ${files.size()} files to analyze"
        }

        return files
    }

    static void main(String[] args) {
        int exitCode = new CommandLine(new SvelteAnalyzer()).execute(args)
        System.exit(exitCode)
    }
}