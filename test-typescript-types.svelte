<script lang="ts">
  // TypeScript type definitions should not be detected as functions
  export let onMove: (position: { x: number; y: number }) => void;
  export let onTest: (data: { width: number; height: number }) => void;
  
  // Real function that should be detected if unused
  function actualUnusedFunction() {
    console.log('This is really unused');
  }
  
  // Used function
  function usedFunction() {
    console.log('This is used');
  }
  
  usedFunction();
</script>

<div>TypeScript type test</div>