<!-- Test file for dynamic class detection -->
<script>
  let isActive = true;
  let status = 'success';
  let theme = 'dark';
</script>

<!-- Pattern 1: Conditional classes -->
<div class="container {isActive ? 'active' : 'inactive'}">Content</div>

<!-- Pattern 2: Template literals -->
<div class="base-class {`theme-${theme}`}">Themed content</div>

<!-- Pattern 3: Multiple conditions -->
<button class="btn {status === 'success' ? 'btn-success' : status === 'error' ? 'btn-error' : 'btn-default'}">
  Button
</button>

<!-- Pattern 4: String concatenation -->
<div class="card {'card-' + status}">Card content</div>

<!-- Pattern 5: Mixed static and dynamic -->
<div class="header navigation {isActive ? 'nav-open' : 'nav-closed'} sidebar">Menu</div>

<style>
  .container { display: block; }
  .active { background: green; }
  .inactive { background: gray; }
  .base-class { padding: 1rem; }
  .theme-dark { background: #333; }
  .btn { padding: 0.5rem; }
  .btn-success { background: green; }
  .btn-error { background: red; }
  .btn-default { background: blue; }
  .card { border: 1px solid; }
  .card-success { border-color: green; }
  .card-error { border-color: red; }
  .header { position: fixed; }
  .navigation { z-index: 100; }
  .nav-open { transform: translateX(0); }
  .nav-closed { transform: translateX(-100%); }
  .sidebar { width: 250px; }
</style>