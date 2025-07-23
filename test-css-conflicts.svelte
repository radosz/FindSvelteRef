<script>
  let message = "test";
</script>

<div class="container">
  <p>{message}</p>
</div>

<style>
  .container {
    padding: 1rem;
    padding: 2rem; /* CSS conflict */
  }
</style>