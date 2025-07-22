<script>
  export let disabled = false;
  let clicked = false;
</script>

<style>
  button {
    background: #007acc;
    color: white;
    border: none;
    padding: 0.5rem 1rem;
  }
</style>

<button {disabled} on:click={() => clicked = true}>
  <slot>Click me</slot>
</button>