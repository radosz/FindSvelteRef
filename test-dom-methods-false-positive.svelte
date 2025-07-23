<script>
  // These DOM method names should NOT be flagged as unused functions
  function contains(selector) {
    return document.querySelector(selector) !== null;
  }
  
  function trim(str) {
    return str.trim();
  }
  
  function startsWith(str, prefix) {
    return str.startsWith(prefix);
  }
  
  function handleClick() {
    console.log('Button clicked');
  }
</script>

<div>
  <button class="button" on:click={handleClick}>Click me</button>
</div>