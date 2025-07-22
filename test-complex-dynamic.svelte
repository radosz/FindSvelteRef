<script>
  let toast = { type: 'success' };
  let passwordStrength = { level: 'weak' };
  let position = 'bottom-right';
</script>

<!-- Pattern 1: toast-{type} dynamic classes -->
<div class="toast toast-{toast.type}">Toast message</div>

<!-- Pattern 2: compound selectors with dynamic parts -->
<div class="strength-fill strength-fill.{passwordStrength.level}">Password strength</div>

<!-- Pattern 3: position classes used dynamically -->
<div class="chat-widget position-{position}">Chat widget</div>

<style>
  .toast { padding: 1rem; border-radius: 4px; }
  .toast-success { background: green; color: white; }
  .toast-error { background: red; color: white; }
  .toast-warning { background: orange; color: black; }
  
  .strength-fill { height: 4px; background: #ccc; }
  .strength-fill.weak { background: red; }
  .strength-fill.medium { background: orange; }
  .strength-fill.strong { background: green; }
  
  .chat-widget { position: fixed; }
  .position-bottom-right { bottom: 20px; right: 20px; }
  .position-bottom-left { bottom: 20px; left: 20px; }
  .position-top-right { top: 20px; right: 20px; }
</style>