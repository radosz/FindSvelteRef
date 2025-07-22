<script>
  import Button from '../components/Button.svelte';
  export let title = 'Home Page';
  let count = 0;
</script>

<style>
  .page {
    padding: 2rem;
    color: blue;
  }
</style>

<main class="page">
  <h1>{title}</h1>
  <p>Count: {count}</p>
  <Button on:click={() => count++} />
</main>