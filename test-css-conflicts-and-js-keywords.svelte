<script>
  function handleClick() {
    if (response.ok) {
      console.log('Success');
    }
  }
  
  async function fetchData() {
    try {
      const data = await fetch('/api');
      return data;
    } catch (error) {
      console.error(error);
    }
  }
</script>

<style>
  .container {
    padding: 10px;
    margin: 5px;
  }
  
  .user-message {
    background: blue;
    color: white;
  }
  
  .bot-message {
    background: gray;
    color: black;
  }
  
  /* Real conflict - same selector, same property */
  .container {
    padding: 20px; /* This should be flagged as conflict */
  }
</style>

<div class="container">
  <div class="user-message">User</div>
  <div class="bot-message">Bot</div>
</div>