<script>
  import Button from './Button.svelte';
  import { createEventDispatcher } from 'svelte';
  import { userStore, themeStore } from '../stores/index.js';
  
  export let title = 'Default Title';
  export let disabled = false;
  
  let userName = '';
  let isVisible = true;
  const API_URL = 'https://api.example.com';
  
  $: userData = {
    name: userName,
    theme: $themeStore
  };
  
  $: if (userName.length > 0) {
    console.log('User name changed:', userName);
  }
  
  const dispatch = createEventDispatcher();
  
  function handleClick() {
    if (!disabled) {
      dispatch('click', { userName, isVisible });
    }
  }
  
  async function loadData() {
    const module = await import('./LazyComponent.svelte');
    return module.default;
  }
</script>

<style lang="scss">
  @import '../styles/main.css';
  @import './components.scss';
  
  .container {
    padding: 1rem;
    margin: 0 auto;
    color: blue;
  }
  
  .container {
    color: red; /* This overrides the blue color above */
    background: white !important;
  }
  
  h1 {
    font-size: 2rem;
    color: $themeStore === 'dark' ? 'white' : 'black';
  }
  
  .button {
    padding: 0.5rem 1rem;
    border: none;
    background: #007acc;
  }
  
  .button:hover {
    background: #005999;
  }
</style>

<style global>
  body {
    font-family: Arial, sans-serif;
    margin: 0;
    padding: 0;
  }
  
  .global-class {
    color: green;
  }
</style>

<main class="container">
  <h1>{title}</h1>
  
  {#if isVisible}
    <div class="content">
      <p>Welcome, {userName || 'Guest'}!</p>
      <p>Theme: {$themeStore}</p>
      <p>User data: {JSON.stringify(userData)}</p>
    </div>
  {/if}
  
  <Button 
    {disabled}
    on:click={handleClick}
    class="button"
  >
    Click me
  </Button>
  
  <input 
    bind:value={userName}
    placeholder="Enter your name"
    disabled={disabled}
  />
</main>