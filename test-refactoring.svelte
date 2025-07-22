<script>
  // Used function
  function handleClick() {
    console.log('clicked');
  }
  
  // Unused function - dead code
  function unusedFunction() {
    return 'never called';
  }
  
  // Used variable
  let count = 0;
  
  // Unused variable - dead code
  let unusedVar = 'never used';
  
  // Component import
  import Button from './Button.svelte';
  import UnusedComponent from './Unused.svelte'; // Never used
</script>

<div>
  <Button on:click={handleClick} />
  <p>Count: {count}</p>
</div>

<style>
  .used { color: blue; }
  .unused { color: red; } /* Never used */
</style>