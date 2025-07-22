#!/usr/bin/env groovy

@Grab('org.apache.commons:commons-lang3:3.12.0')
@Grab('commons-io:commons-io:2.11.0')
@Grab('info.picocli:picocli:4.7.0')
@Grab('com.fasterxml.jackson.core:jackson-core:2.15.2')
@Grab('org.apache.commons:commons-csv:1.9.0')
@Grab('org.slf4j:slf4j-simple:2.0.7')

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
import java.util.regex.Pattern
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

    @Option(names = ["-r", "--recursive"], description = "Recursive directory analysis (default: true)")
    boolean recursive = true

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

    // Data structures for analysis results
    class AnalysisResult {
        String filePath
        List<StyleBlock> styleBlocks = []
        List<CSSImport> cssImports = []
        List<Variable> variables = []
        List<ImportStatement> imports = []
        List<CSSOverride> cssOverrides = []
        List<CSSSelector> cssSelectors = []
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
                    String[] parts = lines[0].split("\\|")
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

            // Identify overrides
            propertyDeclarations.each { property, declarations ->
                if (declarations.size() > 1) {
                    // Sort by line number to find override order
                    declarations.sort { it.line }
                    
                    for (int i = 1; i < declarations.size(); i++) {
                        def current = declarations[i]
                        def previous = declarations[i-1]
                        
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
            
            // Count elements (weight: 1)
            String[] parts = selector.split("[\\s>+~]")
            for (String part : parts) {
                if (part.trim() && !part.contains(".") && !part.contains("#") && !part.contains("[")) {
                    specificity += 1
                }
            }
            
            return specificity
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
                totalLines: StringUtils.countMatches(content, "\n") + 1
            ]
        }
        private List<CSSSelector> parseCSSSelectors(List<StyleBlock> styleBlocks) {
            List<CSSSelector> selectors = []
            
            styleBlocks.eachWithIndex { block, blockIndex ->
                String[] lines = block.content.split("\n")
                String currentSelector = ""
                int selectorStartLine = 0
                
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim()
                    
                    if (line.contains("{")) {
                        // Selector declaration line
                        currentSelector = StringUtils.substringBefore(line, "{").trim()
                        selectorStartLine = block.startLine + i
                        
                        // Parse individual selectors (comma-separated)
                        String[] individualSelectors = currentSelector.split(",")
                        individualSelectors.each { sel ->
                            sel = sel.trim()
                            if (sel) {
                                CSSSelector cssSelector = new CSSSelector()
                                cssSelector.selector = sel
                                cssSelector.declarationLine = selectorStartLine
                                cssSelector.declarationColumn = line.indexOf(sel) + 1
                                cssSelector.sourceBlock = "Block ${blockIndex + 1}"
                                
                                // Determine selector type and extract name
                                if (sel.startsWith(".")) {
                                    cssSelector.type = "class"
                                    cssSelector.name = sel.substring(1).split("[\\s:>+~\\[]")[0]
                                } else if (sel.startsWith("#")) {
                                    cssSelector.type = "id"  
                                    cssSelector.name = sel.substring(1).split("[\\s:>+~\\[]")[0]
                                } else if (sel.contains("[")) {
                                    cssSelector.type = "attribute"
                                    cssSelector.name = StringUtils.substringBetween(sel, "[", "]")
                                } else {
                                    cssSelector.type = "element"
                                    cssSelector.name = sel.split("[\\s:>+~\\.]")[0]
                                }
                                
                                selectors.add(cssSelector)
                            }
                        }
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
                String[] classes = classValue.split("\\s+")
                
                if (classes.contains(className)) {
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
        }

        private void findIdUsage(CSSSelector selector, String markup, int baseOffset) {
            String idName = selector.name
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
                String label = key.replace("Change", "").replaceAll(/([A-Z])/, / $1/).toLowerCase().trim()
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
                    
                    String formattedResult = formatter.formatAnalysisResult(result, format)
                    allResults.add(formattedResult)
                    
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

        if (recursive) {
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
        } else {
            directory.listFiles().each { file ->
                if (file.isFile()) {
                    String fileName = file.name
                    if (extensionArray.any { ext -> 
                        fileName.endsWith(ext.startsWith(".") ? ext : "." + ext)
                    }) {
                        files.add(file)
                    }
                }
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