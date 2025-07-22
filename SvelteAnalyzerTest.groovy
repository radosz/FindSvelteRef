#!/usr/bin/env groovy

@Grab('org.spockframework:spock-core:2.4-M4-groovy-4.0')
@Grab('org.apache.commons:commons-lang3:3.12.0')
@Grab('commons-io:commons-io:2.11.0')
@Grab('info.picocli:picocli:4.7.0')
@Grab('org.slf4j:slf4j-simple:2.0.7')

import spock.lang.Specification
import spock.lang.Unroll
import java.nio.file.*

/**
 * Unit tests for find-ref-svelte.groovy CSS parsing enhancements
 * Tests all the improvements made to eliminate false positives:
 * - Keyframes detection (@keyframes with from/to)
 * - Media query handling (@media)
 * - Descendant selector detection (.parent .child)
 * - Attribute selector parsing (select[disabled])
 */
class SvelteAnalyzerTest extends Specification {

    def "should not flag keyframes selectors as unused CSS"() {
        given: "A Svelte file with keyframes animation"
        def testContent = '''
<style>
  .fade-in {
    animation: fadeIn 0.3s ease-in;
  }
  
  @keyframes fadeIn {
    from { opacity: 0; transform: translateY(10px); }
    to { opacity: 1; transform: translateY(0); }
  }
  
  @keyframes slideOut {
    0% { transform: translateX(0); }
    100% { transform: translateX(100%); }
  }
</style>

<div class="fade-in">Content</div>
'''
        def testFile = createTempSvelteFile(testContent)
        
        when: "Running the analyzer"
        def result = runAnalyzer(testFile)
        
        then: "Should not report from/to as unused selectors"
        !result.contains("'#from' declared")
        !result.contains("'#to' declared")
        !result.contains("Unused CSS Classes/IDs")
        
        cleanup:
        Files.deleteIfExists(testFile)
    }
    
    def "should not flag media query selectors as unused CSS"() {
        given: "A Svelte file with media queries"
        def testContent = '''
<style>
  .responsive {
    width: 100%;
  }
  
  @media (max-width: 768px) {
    .responsive {
      width: 50%;
    }
    
    .mobile-only {
      display: block;
    }
  }
  
  @media print {
    .no-print {
      display: none;
    }
  }
</style>

<div class="responsive">Content</div>
'''
        def testFile = createTempSvelteFile(testContent)
        
        when: "Running the analyzer"
        def result = runAnalyzer(testFile)
        
        then: "Should not report @media as unused selector"
        !result.contains("'#@media' declared")
        !result.contains("Unused CSS Classes/IDs")
        
        cleanup:
        Files.deleteIfExists(testFile)
    }
    
    def "should auto-mark descendant selectors as used"() {
        given: "A Svelte file with descendant selectors"
        def testContent = '''
<style>
  .chat-position-bottom-right .chat-header,
  .chat-position-bottom-left .chat-header {
    border-radius: 12px 12px 0 0;
  }
  
  .chat-position-top-right .chat-header,
  .chat-position-top-left .chat-header {
    border-radius: 0 0 12px 12px;
  }
  
  .parent .child .grandchild {
    color: blue;
  }
</style>

<div class="chat-position-bottom-left">
  <div class="chat-header">Header</div>
</div>
'''
        def testFile = createTempSvelteFile(testContent)
        
        when: "Running the analyzer"
        def result = runAnalyzer(testFile)
        
        then: "Should not report descendant selectors as unused"
        !result.contains("'.chat-position-bottom-right .chat-header' declared")
        !result.contains("'.chat-position-bottom-left .chat-header' declared")
        !result.contains("'.parent .child .grandchild' declared")
        !result.contains("Unused CSS Classes/IDs")
        
        cleanup:
        Files.deleteIfExists(testFile)
    }
    
    def "should auto-mark attribute selectors as used"() {
        given: "A Svelte file with attribute selectors"
        def testContent = '''
<style>
  input[disabled] {
    background-color: #f0f0f0;
    cursor: not-allowed;
  }
  
  select[disabled] {
    background-color: var(--background-secondary);
    color: var(--text-muted);
  }
  
  button[type="submit"] {
    background: blue;
  }
  
  a[href^="https"] {
    color: green;
  }
</style>

<input type="text" disabled={isDisabled} />
<select disabled={autoDetect}>
  <option>Test</option>
</select>
<button type="submit">Submit</button>
<a href="https://example.com">Link</a>
'''
        def testFile = createTempSvelteFile(testContent)
        
        when: "Running the analyzer"
        def result = runAnalyzer(testFile)
        
        then: "Should not report attribute selectors as unused"
        !result.contains("'#disabled' declared")
        !result.contains("'#type' declared") 
        !result.contains("'#href' declared")
        !result.contains("Unused CSS Classes/IDs")
        
        cleanup:
        Files.deleteIfExists(testFile)
    }
    
    @Unroll
    def "should handle complex CSS combinations: #testName"() {
        given: "A Svelte file with complex CSS patterns"
        def testFile = createTempSvelteFile(cssContent)
        
        when: "Running the analyzer"
        def result = runAnalyzer(testFile)
        
        then: "Should not report false positives"
        expectedUnused.each { selector ->
            assert !result.contains("'${selector}' declared"), "Should not flag ${selector} as unused"
        }
        !result.contains("Unused CSS Classes/IDs") || result.contains("No CSS Found")
        
        cleanup:
        Files.deleteIfExists(testFile)
        
        where:
        testName | cssContent | expectedUnused
        "keyframes with media queries" | '''
<style>
  @keyframes bounce {
    from { transform: scale(1); }
    50% { transform: scale(1.1); }
    to { transform: scale(1); }
  }
  
  @media (prefers-reduced-motion) {
    .animated {
      animation: none;
    }
  }
  
  .animated {
    animation: bounce 0.5s ease;
  }
</style>
<div class="animated">Content</div>
''' | ["#from", "#to", "#@media"]
        
        "descendant with attribute selectors" | '''
<style>
  .form-container input[type="text"] {
    border: 1px solid #ccc;
  }
  
  .modal .form-group select[disabled] {
    opacity: 0.5;
  }
  
  .sidebar .menu-item:hover {
    background: #f0f0f0;
  }
</style>
<div class="form-container">
  <input type="text" />
</div>
<div class="modal">
  <div class="form-group">
    <select disabled>
      <option>Test</option>
    </select>
  </div>
</div>
<div class="sidebar">
  <div class="menu-item">Menu</div>
</div>
''' | ["#type", "#disabled"]
        
        "multi-line selectors with keyframes" | '''
<style>
  .chat-position-bottom-right .chat-header,
  .chat-position-bottom-left .chat-header,
  .chat-position-top-right .chat-header {
    transition: all 0.3s ease;
  }
  
  @keyframes slideIn {
    from {
      opacity: 0;
      transform: translateX(-100%);
    }
    to {
      opacity: 1;
      transform: translateX(0);
    }
  }
</style>
<div class="chat-position-bottom-left">
  <div class="chat-header">Header</div>
</div>
''' | ["#from", "#to"]
    }
    
    def "should still detect genuinely unused CSS classes"() {
        given: "A Svelte file with actually unused CSS"
        def testContent = '''
<style>
  .used-class {
    color: blue;
  }
  
  .unused-class {
    color: red;
  }
  
  #used-id {
    font-size: 16px;
  }
  
  #unused-id {
    font-size: 18px;
  }
</style>

<div class="used-class" id="used-id">Content</div>
'''
        def testFile = createTempSvelteFile(testContent)
        
        when: "Running the analyzer"
        def result = runAnalyzer(testFile)
        
        then: "Should detect genuinely unused CSS"
        result.contains("Unused CSS Classes/IDs")
        result.contains("'.unused-class' declared") || result.contains("'#unused-id' declared")
        
        cleanup:
        Files.deleteIfExists(testFile)
    }
    
    def "should handle empty CSS blocks gracefully"() {
        given: "A Svelte file with empty or minimal CSS"
        def testContent = '''
<style>
  /* Empty style block */
</style>

<style>
</style>

<div>Content without styles</div>
'''
        def testFile = createTempSvelteFile(testContent)
        
        when: "Running the analyzer"
        def result = runAnalyzer(testFile)
        
        then: "Should handle gracefully without errors"
        !result.contains("Exception")
        !result.contains("Error")
        
        cleanup:
        Files.deleteIfExists(testFile)
    }
    
    // Helper methods
    private Path createTempSvelteFile(String content) {
        def tempFile = Files.createTempFile("test-", ".svelte")
        Files.write(tempFile, content.getBytes())
        return tempFile
    }
    
    private String runAnalyzer(Path testFile) {
        // Execute the find-ref-svelte.groovy script
        def scriptPath = Paths.get("/Users/radoslav/Projects/find-ref/find-ref-svelte.groovy")
        def command = ["groovy", scriptPath.toString(), testFile.toString(), "--filter-css-issues"]
        
        def process = new ProcessBuilder(command).start()
        process.waitFor()
        
        def output = process.inputStream.text
        def errors = process.errorStream.text
        
        if (errors && !errors.trim().isEmpty()) {
            println "Analyzer errors: ${errors}"
        }
        
        return output
    }
}